package com.localloom.controller;

import com.localloom.model.EntityType;
import com.localloom.model.JobType;
import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.SourceRepository;
import com.localloom.service.AudioService;
import com.localloom.service.EmbeddingService;
import com.localloom.service.JobService;
import com.localloom.service.SourceImportService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

@RestController
@RequestMapping("/api/v1/sources")
public class SourceController {

  private static final Logger log = LogManager.getLogger(SourceController.class);

  public record CreateSourceRequest(
      SourceType sourceType, String name, String originUrl, String config) {}

  private final SourceRepository sourceRepository;
  private final ContentUnitRepository contentUnitRepository;
  private final AudioService audioService;
  private final EmbeddingService embeddingService;
  private final JobService jobService;
  private final SourceImportService sourceImportService;

  public SourceController(
      final SourceRepository sourceRepository,
      final ContentUnitRepository contentUnitRepository,
      final AudioService audioService,
      final EmbeddingService embeddingService,
      final JobService jobService,
      final SourceImportService sourceImportService) {
    this.sourceRepository = sourceRepository;
    this.contentUnitRepository = contentUnitRepository;
    this.audioService = audioService;
    this.embeddingService = embeddingService;
    this.jobService = jobService;
    this.sourceImportService = sourceImportService;
  }

  @Transactional
  @PostMapping
  public ResponseEntity<Map<String, UUID>> createSource(
      @RequestBody final CreateSourceRequest request) {
    log.info(
        "Creating source: type={} name='{}' url='{}'",
        request.sourceType(),
        request.name(),
        request.originUrl());

    validateCreateRequest(request);

    var source = new Source();
    source.setSourceType(request.sourceType());
    source.setName(request.name());
    source.setOriginUrl(request.originUrl());
    source.setConfig(request.config());
    source.setSyncStatus(SyncStatus.SYNCING);
    source = sourceRepository.save(source);

    final var job = jobService.createJob(JobType.SYNC, source.getId(), EntityType.SOURCE);

    // Defer async import until after the transaction commits, preventing the
    // race where the background thread queries a Source that hasn't been flushed yet.
    final var sourceId = source.getId();
    final var jobId = job.getId();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            sourceImportService.importSource(sourceId, jobId);
          }
        });

    log.info("Import scheduled after commit: sourceId={} jobId={}", sourceId, jobId);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("source_id", sourceId, "job_id", jobId));
  }

  @PostMapping("/upload")
  public ResponseEntity<Void> uploadFile(@RequestParam("file") final MultipartFile file) {
    log.info(
        "File upload received: originalFilename='{}' size={}",
        file.getOriginalFilename(),
        file.getSize());
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @GetMapping
  public List<Source> listSources() {
    return sourceRepository.findAllByOrderByCreatedAtDesc();
  }

  @GetMapping("/{id}")
  public Map<String, Object> getSource(@PathVariable final UUID id) {
    final var source =
        sourceRepository
            .findWithContentUnitsById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found: " + id));

    return Map.of("source", source, "contentUnits", source.getContentUnits());
  }

  @Transactional
  @PostMapping("/{id}/sync")
  public ResponseEntity<Map<String, UUID>> syncSource(@PathVariable final UUID id) {
    final var source =
        sourceRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found: " + id));

    log.info("Re-sync triggered for sourceId={}", id);
    source.setSyncStatus(SyncStatus.SYNCING);
    sourceRepository.save(source);

    final var job = jobService.createJob(JobType.SYNC, source.getId(), EntityType.SOURCE);
    final var sourceId = source.getId();
    final var jobId = job.getId();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            sourceImportService.importSource(sourceId, jobId);
          }
        });

    return ResponseEntity.accepted().body(Map.of("source_id", sourceId, "job_id", jobId));
  }

  @Transactional
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteSource(@PathVariable final UUID id) {
    if (!sourceRepository.existsById(id)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found: " + id);
    }
    log.info("Deleting sourceId={}", id);

    // Collect content unit IDs before cascade-deleting so we can clean up audio files on disk.
    final var contentUnitIds =
        contentUnitRepository.findBySourceId(id).stream().map(unit -> unit.getId()).toList();

    embeddingService.deleteBySource(id);
    sourceRepository.deleteById(id);

    // Delete audio files after the DB records are removed — best-effort, non-transactional.
    audioService.deleteAudioFiles(contentUnitIds);

    log.info("Source deleted: id={}", id);
    return ResponseEntity.noContent().build();
  }

  private void validateCreateRequest(final CreateSourceRequest request) {
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
