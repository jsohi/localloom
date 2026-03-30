package com.localloom.controller;

import com.localloom.model.EntityType;
import com.localloom.model.Job;
import com.localloom.model.JobType;
import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.SourceRepository;
import com.localloom.service.EmbeddingService;
import com.localloom.service.JobService;
import com.localloom.service.SourceImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for source management.
 *
 * <pre>
 * POST   /api/v1/sources            — create a source from URL and kick off import
 * POST   /api/v1/sources/upload     — file upload (stub)
 * GET    /api/v1/sources            — list all sources
 * GET    /api/v1/sources/{id}       — source detail with content units
 * POST   /api/v1/sources/{id}/sync  — trigger re-sync
 * DELETE /api/v1/sources/{id}       — delete source and all associated data
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/sources")
public class SourceController {

    private static final Logger log = LoggerFactory.getLogger(SourceController.class);

    /**
     * Request body for {@code POST /api/v1/sources}.
     *
     * @param sourceType the type of source (e.g. PODCAST, CONFLUENCE)
     * @param name       display name for the source
     * @param originUrl  original URL (RSS feed, podcast page, etc.)
     * @param config     optional connector-specific configuration as a JSON string
     */
    public record CreateSourceRequest(
            SourceType sourceType,
            String name,
            String originUrl,
            String config) {}

    private final SourceRepository sourceRepository;
    private final ContentUnitRepository contentUnitRepository;
    private final EmbeddingService embeddingService;
    private final JobService jobService;
    private final SourceImportService sourceImportService;

    public SourceController(
            SourceRepository sourceRepository,
            ContentUnitRepository contentUnitRepository,
            EmbeddingService embeddingService,
            JobService jobService,
            SourceImportService sourceImportService) {
        this.sourceRepository = sourceRepository;
        this.contentUnitRepository = contentUnitRepository;
        this.embeddingService = embeddingService;
        this.jobService = jobService;
        this.sourceImportService = sourceImportService;
    }

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    /**
     * Creates a new source from a URL and immediately kicks off a background
     * import job.
     *
     * @return {@code 201 Created} with {@code {source_id, job_id}}
     */
    @PostMapping
    public ResponseEntity<Map<String, UUID>> createSource(@RequestBody CreateSourceRequest request) {
        log.info("Creating source: type={} name='{}' url='{}'",
                request.sourceType(), request.name(), request.originUrl());

        validateCreateRequest(request);

        Source source = new Source();
        source.setSourceType(request.sourceType());
        source.setName(request.name());
        source.setOriginUrl(request.originUrl());
        source.setConfig(request.config());
        source.setSyncStatus(SyncStatus.SYNCING);
        source = sourceRepository.save(source);

        Job job = jobService.createJob(JobType.SYNC, source.getId(), EntityType.SOURCE);

        sourceImportService.importSource(source.getId(), job.getId());

        log.info("Import triggered: sourceId={} jobId={}", source.getId(), job.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("source_id", source.getId(), "job_id", job.getId()));
    }

    /**
     * Stub endpoint for multipart file uploads. Returns {@code 501 Not Implemented}
     * until the file-upload connector is built.
     */
    @PostMapping("/upload")
    public ResponseEntity<Void> uploadFile(@RequestParam("file") MultipartFile file) {
        log.info("File upload received: originalFilename='{}' size={}", file.getOriginalFilename(), file.getSize());
        // TODO: implement file-upload connector (APP-82 or similar)
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /**
     * Returns all sources ordered by creation time (newest first).
     */
    @GetMapping
    public List<Source> listSources() {
        return sourceRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .toList();
    }

    /**
     * Returns a single source with its associated content units.
     *
     * @throws ResponseStatusException {@code 404} if the source does not exist
     */
    @GetMapping("/{id}")
    public Map<String, Object> getSource(@PathVariable UUID id) {
        Source source = sourceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Source not found: " + id));

        return Map.of(
                "source", source,
                "contentUnits", contentUnitRepository.findBySourceId(id));
    }

    /**
     * Triggers a re-sync of an existing source.
     *
     * @return {@code 202 Accepted} with {@code {source_id, job_id}}
     * @throws ResponseStatusException {@code 404} if the source does not exist
     */
    @PostMapping("/{id}/sync")
    public ResponseEntity<Map<String, UUID>> syncSource(@PathVariable UUID id) {
        Source source = sourceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Source not found: " + id));

        log.info("Re-sync triggered for sourceId={}", id);
        source.setSyncStatus(SyncStatus.SYNCING);
        sourceRepository.save(source);

        Job job = jobService.createJob(JobType.SYNC, source.getId(), EntityType.SOURCE);
        sourceImportService.importSource(source.getId(), job.getId());

        return ResponseEntity.accepted()
                .body(Map.of("source_id", source.getId(), "job_id", job.getId()));
    }

    /**
     * Deletes a source together with all its content units and vector embeddings.
     *
     * @return {@code 204 No Content}
     * @throws ResponseStatusException {@code 404} if the source does not exist
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSource(@PathVariable UUID id) {
        if (!sourceRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found: " + id);
        }
        log.info("Deleting sourceId={}", id);
        embeddingService.deleteBySource(id);
        sourceRepository.deleteById(id);
        log.info("Source deleted: id={}", id);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void validateCreateRequest(CreateSourceRequest request) {
        if (request.sourceType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceType is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (request.originUrl() == null || request.originUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "originUrl is required");
        }
    }
}
