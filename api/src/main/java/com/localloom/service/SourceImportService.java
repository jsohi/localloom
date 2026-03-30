package com.localloom.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localloom.model.ContentFragment;
import com.localloom.model.ContentType;
import com.localloom.model.ContentUnit;
import com.localloom.model.ContentUnitStatus;
import com.localloom.model.EntityType;
import com.localloom.model.FragmentType;
import com.localloom.model.JobType;
import com.localloom.model.Source;
import com.localloom.model.SyncStatus;
import com.localloom.repository.ContentFragmentRepository;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.SourceRepository;
import com.localloom.service.MlSidecarClient.TranscriptionResult;
import com.localloom.service.dto.ResolvedEpisode;
import com.localloom.service.dto.ResolvedPodcast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the full content ingestion pipeline for a given source.
 *
 * <p>The pipeline runs asynchronously (virtual-thread executor configured in
 * {@link com.localloom.config.AsyncConfig}) and proceeds through these stages
 * for each episode / content unit:
 * <ol>
 *   <li>FETCHING — download raw audio</li>
 *   <li>TRANSCRIBING — send audio to the Python ML sidecar</li>
 *   <li>Persist {@link ContentFragment} records (TIMED_SEGMENT)</li>
 *   <li>EMBEDDING — generate and store vectors via Spring AI</li>
 *   <li>INDEXED — mark the unit complete</li>
 * </ol>
 * Job progress is updated after each unit completes.
 */
@Service
public class SourceImportService {

    private static final Logger log = LoggerFactory.getLogger(SourceImportService.class);

    private final UrlResolver urlResolver;
    private final AudioService audioService;
    private final MlSidecarClient mlSidecarClient;
    private final EmbeddingService embeddingService;
    private final JobService jobService;
    private final SourceRepository sourceRepository;
    private final ContentUnitRepository contentUnitRepository;
    private final ContentFragmentRepository contentFragmentRepository;
    private final ObjectMapper objectMapper;

    public SourceImportService(
            UrlResolver urlResolver,
            AudioService audioService,
            MlSidecarClient mlSidecarClient,
            EmbeddingService embeddingService,
            JobService jobService,
            SourceRepository sourceRepository,
            ContentUnitRepository contentUnitRepository,
            ContentFragmentRepository contentFragmentRepository,
            ObjectMapper objectMapper) {
        this.urlResolver = urlResolver;
        this.audioService = audioService;
        this.mlSidecarClient = mlSidecarClient;
        this.embeddingService = embeddingService;
        this.jobService = jobService;
        this.sourceRepository = sourceRepository;
        this.contentUnitRepository = contentUnitRepository;
        this.contentFragmentRepository = contentFragmentRepository;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the full import pipeline for the source identified by {@code sourceId}.
     * Executes on the virtual-thread async executor (see {@link com.localloom.config.AsyncConfig}).
     *
     * <p>On success the source's {@code syncStatus} is set to {@code IDLE} and
     * {@code lastSyncedAt} is updated. On failure the source is set to
     * {@code SyncStatus.ERROR} and the job is marked failed.
     *
     * @param sourceId the UUID of the source to import
     * @param jobId    the UUID of the pre-created {@link com.localloom.model.Job} tracking this work
     * @return a {@link CompletableFuture} that completes when the pipeline finishes
     */
    @Async
    public CompletableFuture<Void> importSource(UUID sourceId, UUID jobId) {
        log.info("Starting import pipeline: sourceId={} jobId={}", sourceId, jobId);

        try {
            Source source = sourceRepository.findById(sourceId)
                    .orElseThrow(() -> new IllegalArgumentException("Source not found: " + sourceId));

            // Stage 1: resolve the source URL to a ResolvedPodcast + episode list
            log.info("Resolving URL for source '{}': {}", source.getName(), source.getOriginUrl());
            ResolvedPodcast resolved = urlResolver.resolve(source.getOriginUrl());

            // Update source metadata from the resolved podcast
            updateSourceMetadata(source, resolved);

            List<ResolvedEpisode> episodes = resolved.episodes();
            if (episodes.isEmpty()) {
                log.warn("No episodes found for sourceId={}", sourceId);
                jobService.completeJob(jobId);
                return CompletableFuture.completedFuture(null);
            }

            // Stage 2: create ContentUnit records (PENDING) for each episode
            List<ContentUnit> units = createContentUnits(source, episodes);
            log.info("Created {} ContentUnit(s) for sourceId={}", units.size(), sourceId);

            // Stage 3–5: process each episode through the full sub-pipeline
            boolean isYoutube = urlResolver.detectType(source.getOriginUrl())
                    == UrlResolver.UrlType.YOUTUBE;

            int total = units.size();
            int completed = 0;
            List<String> errors = new ArrayList<>();

            for (int i = 0; i < total; i++) {
                ContentUnit unit = units.get(i);
                ResolvedEpisode episode = episodes.get(i);
                try {
                    processEpisode(source, unit, episode, isYoutube);
                } catch (Exception e) {
                    log.error("Failed to process episode '{}' (contentUnitId={}): {}",
                            episode.title(), unit.getId(), e.getMessage(), e);
                    errors.add(episode.title() + ": " + e.getMessage());
                    setUnitStatus(unit, ContentUnitStatus.ERROR);
                }
                completed++;
                double progress = (double) completed / total;
                jobService.updateProgress(jobId, progress);
            }

            // Finalise
            if (errors.isEmpty()) {
                finishSource(source, SyncStatus.IDLE);
                jobService.completeJob(jobId);
                log.info("Import pipeline complete: sourceId={} jobId={}", sourceId, jobId);
            } else {
                String errorSummary = errors.size() + " episode(s) failed: " + String.join("; ", errors);
                finishSource(source, SyncStatus.ERROR);
                jobService.failJob(jobId, errorSummary);
                log.warn("Import pipeline finished with errors: sourceId={} jobId={} errors={}",
                        sourceId, jobId, errorSummary);
            }

        } catch (Exception e) {
            log.error("Import pipeline failed fatally: sourceId={} jobId={}: {}", sourceId, jobId, e.getMessage(), e);
            sourceRepository.findById(sourceId)
                    .ifPresent(s -> finishSource(s, SyncStatus.ERROR));
            jobService.failJob(jobId, e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    // -------------------------------------------------------------------------
    // Pipeline stages
    // -------------------------------------------------------------------------

    /**
     * Processes a single episode through FETCHING → TRANSCRIBING → EMBEDDING → INDEXED.
     */
    private void processEpisode(Source source, ContentUnit unit, ResolvedEpisode episode, boolean isYoutube) {
        UUID contentUnitId = unit.getId();
        String audioUrl = episode.audioUrl();

        if (audioUrl == null || audioUrl.isBlank()) {
            throw new IllegalStateException("No audio URL for episode: " + episode.title());
        }

        // 3a. FETCHING
        setUnitStatus(unit, ContentUnitStatus.FETCHING);
        log.debug("Downloading audio for contentUnitId={} url={}", contentUnitId, audioUrl);
        Path wavFile = audioService.downloadAndConvert(audioUrl, contentUnitId, isYoutube);

        // 3b. TRANSCRIBING
        setUnitStatus(unit, ContentUnitStatus.TRANSCRIBING);
        log.debug("Transcribing contentUnitId={}", contentUnitId);
        TranscriptionResult transcription = mlSidecarClient.transcribe(wavFile);

        // 3c. Persist transcript fragments
        List<ContentFragment> fragments = saveFragments(unit, transcription);

        // Save raw concatenated text to the ContentUnit for display
        String rawText = transcription.segments().stream()
                .map(MlSidecarClient.Segment::text)
                .reduce("", (a, b) -> a + " " + b)
                .strip();
        unit.setRawText(rawText);
        contentUnitRepository.save(unit);

        // 3d. EMBEDDING
        setUnitStatus(unit, ContentUnitStatus.EMBEDDING);
        log.debug("Embedding contentUnitId={}", contentUnitId);
        embeddingService.embedContent(
                source.getId(),
                contentUnitId,
                unit.getTitle(),
                source.getSourceType(),
                ContentType.AUDIO,
                fragments);

        // 3e. INDEXED
        setUnitStatus(unit, ContentUnitStatus.INDEXED);
        log.info("Episode indexed: contentUnitId={} title='{}'", contentUnitId, unit.getTitle());
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /**
     * Persists ContentUnit records in PENDING state for each resolved episode.
     * Returns the list in the same order as {@code episodes}.
     */
    @Transactional
    List<ContentUnit> createContentUnits(Source source, List<ResolvedEpisode> episodes) {
        List<ContentUnit> units = new ArrayList<>(episodes.size());
        for (ResolvedEpisode episode : episodes) {
            ContentUnit unit = new ContentUnit();
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
    }

    /**
     * Saves the transcript segments as {@link ContentFragment} records of type
     * {@link FragmentType#TIMED_SEGMENT}.
     */
    @Transactional
    List<ContentFragment> saveFragments(ContentUnit unit, TranscriptionResult transcription) {
        List<ContentFragment> fragments = new ArrayList<>(transcription.segments().size());
        int idx = 0;
        for (MlSidecarClient.Segment segment : transcription.segments()) {
            ContentFragment fragment = new ContentFragment();
            fragment.setContentUnit(unit);
            fragment.setFragmentType(FragmentType.TIMED_SEGMENT);
            fragment.setSequenceIndex(idx++);
            fragment.setText(segment.text().strip());
            fragment.setLocation(buildSegmentLocation(segment));
            fragments.add(contentFragmentRepository.save(fragment));
        }
        return fragments;
    }

    @Transactional
    void setUnitStatus(ContentUnit unit, ContentUnitStatus status) {
        unit.setStatus(status);
        contentUnitRepository.save(unit);
    }

    @Transactional
    void updateSourceMetadata(Source source, ResolvedPodcast resolved) {
        if (source.getDescription() == null && resolved.description() != null) {
            source.setDescription(resolved.description());
        }
        if (source.getIconUrl() == null && resolved.artworkUrl() != null) {
            source.setIconUrl(resolved.artworkUrl());
        }
        source.setSyncStatus(SyncStatus.SYNCING);
        sourceRepository.save(source);
    }

    @Transactional
    void finishSource(Source source, SyncStatus status) {
        source.setSyncStatus(status);
        if (status == SyncStatus.IDLE) {
            source.setLastSyncedAt(Instant.now());
        }
        sourceRepository.save(source);
    }

    private String buildEpisodeMetadata(ResolvedEpisode episode) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "audio_url", episode.audioUrl() != null ? episode.audioUrl() : "",
                    "duration_seconds", episode.durationSeconds() != null ? episode.durationSeconds() : 0
            ));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise episode metadata: {}", e.getMessage());
            return "{}";
        }
    }

    private String buildSegmentLocation(MlSidecarClient.Segment segment) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "start_time", segment.start(),
                    "end_time", segment.end()
            ));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise segment location: {}", e.getMessage());
            return "{}";
        }
    }
}
