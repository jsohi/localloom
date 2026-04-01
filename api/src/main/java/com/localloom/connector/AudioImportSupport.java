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
import com.localloom.service.dto.ResolvedEpisode;
import com.localloom.service.dto.ResolvedPodcast;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

  public AudioImportSupport(
      final AudioService audioService,
      final MlSidecarClient mlSidecarClient,
      final EmbeddingService embeddingService,
      final JobService jobService,
      final SourceRepository sourceRepository,
      final ContentUnitRepository contentUnitRepository,
      final ContentFragmentRepository contentFragmentRepository,
      final ObjectMapper objectMapper,
      final TransactionTemplate transactionTemplate) {
    this.audioService = audioService;
    this.mlSidecarClient = mlSidecarClient;
    this.embeddingService = embeddingService;
    this.jobService = jobService;
    this.sourceRepository = sourceRepository;
    this.contentUnitRepository = contentUnitRepository;
    this.contentFragmentRepository = contentFragmentRepository;
    this.objectMapper = objectMapper;
    this.tx = transactionTemplate;
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

  /** Creates one {@link ContentUnit} per episode, linked to the given source. */
  public List<ContentUnit> createContentUnits(
      final Source source, final List<ResolvedEpisode> episodes) {
    return tx.execute(
        status -> {
          final var units = new ArrayList<ContentUnit>(episodes.size());
          for (final var episode : episodes) {
            final var unit = new ContentUnit();
            unit.setSource(source);
            unit.setTitle(episode.title());
            unit.setContentType(ContentType.AUDIO);
            unit.setExternalId(episode.externalId());
            unit.setExternalUrl(episode.audioUrl());
            unit.setStatus(ContentUnitStatus.PENDING);
            unit.setPublishedAt(episode.publishedAt());
            unit.setMetadata(buildEpisodeMetadata(episode));
            units.add(contentUnitRepository.save(unit));
          }
          return units;
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

    setUnitStatus(unit, ContentUnitStatus.FETCHING);
    log.debug("Downloading audio for contentUnitId={} url={}", contentUnitId, audioUrl);
    final var wavFile = audioService.downloadAndConvert(audioUrl, contentUnitId, isYoutube);

    setUnitStatus(unit, ContentUnitStatus.TRANSCRIBING);
    log.debug("Transcribing contentUnitId={}", contentUnitId);
    final var transcription = mlSidecarClient.transcribe(wavFile);

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
    log.debug("Embedding contentUnitId={}", contentUnitId);
    embeddingService.embedContent(
        source.getId(),
        contentUnitId,
        unit.getTitle(),
        source.getSourceType(),
        ContentType.AUDIO,
        fragments);

    setUnitStatus(unit, ContentUnitStatus.INDEXED);
    log.info("Episode indexed: contentUnitId={} title='{}'", contentUnitId, unit.getTitle());
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
      jobService.completeJob(jobId);
      return;
    }

    final var units = createContentUnits(source, episodes);
    log.info("Created {} ContentUnit(s) for sourceId={}", units.size(), source.getId());

    final var total = units.size();
    var completed = 0;
    final var errors = new ArrayList<String>();

    for (var i = 0; i < total; i++) {
      final var unit = units.get(i);
      final var episode = episodes.get(i);
      try {
        processEpisode(source, unit, episode, isYoutube);
      } catch (Exception e) {
        log.error(
            "Failed to process episode '{}' (contentUnitId={}): {}",
            episode.title(),
            unit.getId(),
            e.getMessage(),
            e);
        errors.add(episode.title() + ": " + e.getMessage());
        setUnitStatus(unit, ContentUnitStatus.ERROR);
      }
      completed++;
      jobService.updateProgress(jobId, (double) completed / total);
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
