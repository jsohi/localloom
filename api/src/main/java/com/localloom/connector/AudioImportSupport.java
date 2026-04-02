package com.localloom.connector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localloom.model.ContentFragment;
import com.localloom.model.ContentType;
import com.localloom.model.ContentUnit;
import com.localloom.model.ContentUnitStatus;
import com.localloom.model.FragmentType;
import com.localloom.model.Source;
import com.localloom.model.SyncStatus;
import com.localloom.repository.ContentFragmentRepository;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.SourceRepository;
import com.localloom.service.AudioService;
import com.localloom.service.EmbeddingService;
import com.localloom.service.JobService;
import com.localloom.service.MlSidecarClient;
import com.localloom.service.SourceImportService;
import com.localloom.service.dto.ResolvedEpisode;
import com.localloom.service.dto.ResolvedPodcast;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared helpers for audio-based connectors ({@link MediaConnector}, {@link YouTubeConnector}).
 * Handles episode creation, download, transcription, embedding, and source lifecycle.
 */
@Component
public class AudioImportSupport {

  private static final Logger log = LogManager.getLogger(AudioImportSupport.class);

  private final AudioService audioService;
  private final MlSidecarClient mlSidecarClient;
  private final EmbeddingService embeddingService;
  private final JobService jobService;
  private final SourceRepository sourceRepository;
  private final ContentUnitRepository contentUnitRepository;
  private final ContentFragmentRepository contentFragmentRepository;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate tx;
  private final int parallelEpisodes;
  private final SourceImportService sourceImportService;

  public AudioImportSupport(
      final AudioService audioService,
      final MlSidecarClient mlSidecarClient,
      final EmbeddingService embeddingService,
      final JobService jobService,
      final SourceRepository sourceRepository,
      final ContentUnitRepository contentUnitRepository,
      final ContentFragmentRepository contentFragmentRepository,
      final ObjectMapper objectMapper,
      final TransactionTemplate transactionTemplate,
      @Value("${localloom.import.parallel-episodes:8}") final int parallelEpisodes,
      @Lazy final SourceImportService sourceImportService) {
    this.audioService = audioService;
    this.mlSidecarClient = mlSidecarClient;
    this.embeddingService = embeddingService;
    this.jobService = jobService;
    this.sourceRepository = sourceRepository;
    this.contentUnitRepository = contentUnitRepository;
    this.contentFragmentRepository = contentFragmentRepository;
    this.objectMapper = objectMapper;
    this.tx = transactionTemplate;
    this.parallelEpisodes = parallelEpisodes;
    this.sourceImportService = sourceImportService;
    log.info("Audio import parallelism: {} concurrent episode(s)", parallelEpisodes);
  }

  /** Updates source metadata (description, icon) from the resolved podcast data. */
  public void updateSourceMetadata(final Source source, final ResolvedPodcast resolved) {
    tx.executeWithoutResult(
        s -> {
          if (source.getDescription() == null && resolved.description() != null) {
            source.setDescription(resolved.description());
          }
          if (source.getIconUrl() == null && resolved.artworkUrl() != null) {
            source.setIconUrl(resolved.artworkUrl());
          }
          source.setSyncStatus(SyncStatus.SYNCING);
          sourceRepository.save(source);
        });
  }

  /** Pair of ContentUnit and its corresponding ResolvedEpisode. */
  public record UnitEpisodePair(ContentUnit unit, ResolvedEpisode episode) {}

  /**
   * Creates one {@link ContentUnit} per episode, skipping episodes that are already indexed
   * (matched by externalId within the same source). Returns paired units+episodes to maintain
   * alignment after filtering.
   */
  public List<UnitEpisodePair> createContentUnits(
      final Source source, final List<ResolvedEpisode> episodes) {
    return tx.execute(
        status -> {
          final var existing = contentUnitRepository.findBySourceId(source.getId());
          // Only skip episodes that are successfully INDEXED — retry ERROR/PENDING ones
          final var indexedIds =
              existing.stream()
                  .filter(
                      u -> u.getExternalId() != null && u.getStatus() == ContentUnitStatus.INDEXED)
                  .map(ContentUnit::getExternalId)
                  .collect(java.util.stream.Collectors.toSet());

          // Delete failed/pending units so they can be retried cleanly
          final var failedUnits =
              existing.stream()
                  .filter(
                      u ->
                          u.getStatus() == ContentUnitStatus.ERROR
                              || u.getStatus() == ContentUnitStatus.PENDING)
                  .toList();
          if (!failedUnits.isEmpty()) {
            contentUnitRepository.deleteAll(failedUnits);
            log.info("Deleted {} failed/pending unit(s) for retry", failedUnits.size());
          }

          final var pairs = new ArrayList<UnitEpisodePair>(episodes.size());
          var skipped = 0;
          for (final var episode : episodes) {
            if (episode.externalId() != null && indexedIds.contains(episode.externalId())) {
              skipped++;
              continue;
            }
            final var unit = new ContentUnit();
            unit.setSource(source);
            unit.setTitle(episode.title());
            unit.setContentType(ContentType.AUDIO);
            unit.setExternalId(episode.externalId());
            unit.setExternalUrl(episode.audioUrl());
            unit.setStatus(ContentUnitStatus.PENDING);
            unit.setPublishedAt(episode.publishedAt());
            unit.setMetadata(buildEpisodeMetadata(episode));
            pairs.add(new UnitEpisodePair(contentUnitRepository.save(unit), episode));
          }
          if (skipped > 0) {
            log.info(
                "Skipped {} already-indexed episode(s) for sourceId={}", skipped, source.getId());
          }
          return pairs;
        });
  }

  /**
   * Processes a single episode: download → convert → transcribe → save fragments → embed.
   *
   * @param isYoutube if true, uses yt-dlp for audio download
   */
  public void processEpisode(
      final Source source,
      final ContentUnit unit,
      final ResolvedEpisode episode,
      final boolean isYoutube) {
    final var contentUnitId = unit.getId();
    final var audioUrl = episode.audioUrl();

    if (audioUrl == null || audioUrl.isBlank()) {
      throw new IllegalStateException("No audio URL for episode: " + episode.title());
    }

    final var totalStart = System.currentTimeMillis();
    checkCancelled(source.getId(), episode.title());

    setUnitStatus(unit, ContentUnitStatus.FETCHING);
    final var dlStart = System.currentTimeMillis();
    log.debug("Downloading audio for contentUnitId={} url={}", contentUnitId, audioUrl);
    final var wavFile = audioService.downloadAndConvert(audioUrl, contentUnitId, isYoutube);
    final var dlMs = System.currentTimeMillis() - dlStart;

    checkCancelled(source.getId(), episode.title());

    setUnitStatus(unit, ContentUnitStatus.TRANSCRIBING);
    final var txStart = System.currentTimeMillis();
    log.debug("Transcribing contentUnitId={}", contentUnitId);
    final var transcription = mlSidecarClient.transcribe(wavFile);
    final var txMs = System.currentTimeMillis() - txStart;

    checkCancelled(source.getId(), episode.title());

    final var fragments = saveFragments(unit, transcription);

    final var rawText =
        transcription.segments().stream()
            .map(MlSidecarClient.Segment::text)
            .collect(Collectors.joining(" "));
    tx.executeWithoutResult(
        s -> {
          unit.setRawText(rawText);
          contentUnitRepository.save(unit);
        });

    setUnitStatus(unit, ContentUnitStatus.EMBEDDING);
    final var emStart = System.currentTimeMillis();
    log.debug("Embedding contentUnitId={}", contentUnitId);
    embeddingService.embedContent(
        source.getId(),
        contentUnitId,
        unit.getTitle(),
        source.getSourceType(),
        ContentType.AUDIO,
        fragments);
    final var emMs = System.currentTimeMillis() - emStart;

    final var totalMs = System.currentTimeMillis() - totalStart;
    setUnitStatus(unit, ContentUnitStatus.INDEXED);
    log.info(
        "Episode indexed: '{}' — total={}s (download={}s, transcribe={}s, embed={}s)",
        unit.getTitle(),
        totalMs / 1000,
        dlMs / 1000,
        txMs / 1000,
        emMs / 1000);
  }

  /**
   * Runs the full audio import loop: update metadata, create units, process each episode, track
   * progress.
   */
  public void importEpisodes(
      final Source source,
      final UUID jobId,
      final ResolvedPodcast resolved,
      final List<ResolvedEpisode> episodes,
      final boolean isYoutube) {
    updateSourceMetadata(source, resolved);

    if (episodes.isEmpty()) {
      log.warn("No episodes found for sourceId={}", source.getId());
      finishSource(source, SyncStatus.IDLE);
      jobService.completeJob(jobId);
      return;
    }

    final var pairs = createContentUnits(source, episodes);
    final var total = pairs.size();

    if (total == 0) {
      log.info("All episodes already indexed for sourceId={}", source.getId());
      finishSource(source, SyncStatus.IDLE);
      jobService.completeJob(jobId);
      return;
    }

    log.info(
        "Processing {} new episode(s) with parallelism={} for sourceId={}",
        total,
        parallelEpisodes,
        source.getId());

    final var completedCount = new AtomicInteger(0);
    final var errors = new ConcurrentLinkedQueue<String>();
    final var semaphore = new Semaphore(parallelEpisodes);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var futures = new ArrayList<CompletableFuture<Void>>(total);

      for (final var pair : pairs) {
        final var unit = pair.unit();
        final var episode = pair.episode();
        futures.add(
            CompletableFuture.runAsync(
                () -> {
                  try {
                    semaphore.acquire();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.add(episode.title() + ": interrupted");
                    return;
                  }
                  try {
                    processEpisode(source, unit, episode, isYoutube);
                  } catch (Exception e) {
                    if (sourceImportService.isCancelled(source.getId())) {
                      log.info("Episode skipped (import cancelled): '{}'", episode.title());
                    } else {
                      log.error(
                          "Failed to process episode '{}' (contentUnitId={}): {}",
                          episode.title(),
                          unit.getId(),
                          e.getMessage(),
                          e);
                      errors.add(episode.title() + ": " + e.getMessage());
                      setUnitStatus(unit, ContentUnitStatus.ERROR);
                    }
                  } finally {
                    semaphore.release();
                    final var done = completedCount.incrementAndGet();
                    jobService.updateProgress(jobId, (double) done / total);
                  }
                },
                executor));
      }

      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    if (errors.isEmpty()) {
      finishSource(source, SyncStatus.IDLE);
      jobService.completeJob(jobId);
      log.info("Import complete: sourceId={} jobId={}", source.getId(), jobId);
    } else {
      final var errorSummary = errors.size() + " episode(s) failed: " + String.join("; ", errors);
      finishSource(source, SyncStatus.ERROR);
      jobService.failJob(jobId, errorSummary);
      log.warn(
          "Import finished with errors: sourceId={} jobId={} errors={}",
          source.getId(),
          jobId,
          errorSummary);
    }
  }

  public void finishSource(final Source source, final SyncStatus status) {
    tx.executeWithoutResult(
        s -> {
          source.setSyncStatus(status);
          if (status == SyncStatus.IDLE) {
            source.setLastSyncedAt(Instant.now());
          }
          sourceRepository.save(source);
        });
  }

  public void failSource(final UUID sourceId, final UUID jobId, final String message) {
    sourceRepository.findById(sourceId).ifPresent(s -> finishSource(s, SyncStatus.ERROR));
    jobService.failJob(jobId, message);
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void checkCancelled(final UUID sourceId, final String episodeTitle) {
    if (sourceImportService.isCancelled(sourceId)) {
      throw new IllegalStateException("Import cancelled for episode: " + episodeTitle);
    }
  }

  private void setUnitStatus(final ContentUnit unit, final ContentUnitStatus status) {
    tx.executeWithoutResult(
        s -> {
          unit.setStatus(status);
          contentUnitRepository.save(unit);
        });
  }

  private List<ContentFragment> saveFragments(
      final ContentUnit unit, final MlSidecarClient.TranscriptionResult transcription) {
    return tx.execute(
        status -> {
          final var fragments = new ArrayList<ContentFragment>(transcription.segments().size());
          var idx = 0;
          for (final var segment : transcription.segments()) {
            final var fragment = new ContentFragment();
            fragment.setContentUnit(unit);
            fragment.setFragmentType(FragmentType.TIMED_SEGMENT);
            fragment.setSequenceIndex(idx++);
            fragment.setText(segment.text().strip());
            fragment.setLocation(buildSegmentLocation(segment));
            fragments.add(contentFragmentRepository.save(fragment));
          }
          return fragments;
        });
  }

  private String buildEpisodeMetadata(final ResolvedEpisode episode) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "audio_url", episode.audioUrl() != null ? episode.audioUrl() : "",
              "duration_seconds",
                  episode.durationSeconds() != null ? episode.durationSeconds() : 0));
    } catch (JsonProcessingException e) {
      log.warn("Could not serialise episode metadata: {}", e.getMessage());
      return "{}";
    }
  }

  private String buildSegmentLocation(final MlSidecarClient.Segment segment) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "start_time", segment.start(),
              "end_time", segment.end()));
    } catch (JsonProcessingException e) {
      log.warn("Could not serialise segment location: {}", e.getMessage());
      return "{}";
    }
  }
}
