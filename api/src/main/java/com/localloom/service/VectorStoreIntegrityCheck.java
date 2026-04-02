package com.localloom.service;

import com.localloom.model.ContentUnitStatus;
import com.localloom.model.EntityType;
import com.localloom.model.JobStatus;
import com.localloom.model.JobType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.JobRepository;
import com.localloom.repository.SourceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Detects Postgres/ChromaDB sync drift at startup. If sources are marked INDEXED in Postgres but
 * ChromaDB has no vectors (e.g. volume was recreated), automatically triggers a re-sync for each
 * affected source.
 */
@Component
public class VectorStoreIntegrityCheck {

  private static final Logger log = LogManager.getLogger(VectorStoreIntegrityCheck.class);

  private final SourceRepository sourceRepository;
  private final ContentUnitRepository contentUnitRepository;
  private final EmbeddingService embeddingService;
  private final JobService jobService;
  private final JobRepository jobRepository;
  private final SourceImportService sourceImportService;
  private final TransactionTemplate tx;

  public VectorStoreIntegrityCheck(
      final SourceRepository sourceRepository,
      final ContentUnitRepository contentUnitRepository,
      final EmbeddingService embeddingService,
      final JobService jobService,
      final JobRepository jobRepository,
      final SourceImportService sourceImportService,
      final TransactionTemplate transactionTemplate) {
    this.sourceRepository = sourceRepository;
    this.contentUnitRepository = contentUnitRepository;
    this.embeddingService = embeddingService;
    this.jobService = jobService;
    this.jobRepository = jobRepository;
    this.sourceImportService = sourceImportService;
    this.tx = transactionTemplate;
  }

  // Track sources already triggered for re-sync to avoid double-triggering
  private final java.util.Set<UUID> resyncedSourceIds = new java.util.HashSet<>();

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    cleanupStaleJobs();
    retryFailedEpisodes();
    checkVectorStoreIntegrity();
    resyncedSourceIds.clear();
  }

  private void cleanupStaleJobs() {
    tx.executeWithoutResult(
        s -> {
          final var staleStatuses = List.of(JobStatus.PENDING, JobStatus.RUNNING);
          final var staleJobs = jobRepository.findByStatusInOrderByCreatedAtAsc(staleStatuses);
          if (staleJobs.isEmpty()) return;

          for (final var job : staleJobs) {
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Stale job cleaned up at startup");
            job.setCompletedAt(java.time.Instant.now());
            jobRepository.save(job);
          }
          log.info("Cleaned up {} stale job(s) at startup", staleJobs.size());
        });

    // Reset sources stuck in SYNCING — they can't be legitimately syncing at boot
    tx.executeWithoutResult(
        s -> {
          final var stuckSources =
              sourceRepository.findAllByOrderByCreatedAtDesc().stream()
                  .filter(src -> src.getSyncStatus() == SyncStatus.SYNCING)
                  .toList();
          for (final var src : stuckSources) {
            src.setSyncStatus(SyncStatus.ERROR);
            sourceRepository.save(src);
          }
          if (!stuckSources.isEmpty()) {
            log.info("Reset {} stuck SYNCING source(s) to ERROR", stuckSources.size());
          }
        });
  }

  private void retryFailedEpisodes() {
    final var sources = sourceRepository.findAllByOrderByCreatedAtDesc();
    final var toRetry = new ArrayList<String>();

    for (final var source : sources) {
      if (source.getSyncStatus() == SyncStatus.SYNCING) continue;
      try {
        final var units = contentUnitRepository.findBySourceId(source.getId());
        final var failedCount =
            units.stream().filter(u -> u.getStatus() != ContentUnitStatus.INDEXED).count();

        if (failedCount > 0) {
          toRetry.add(source.getName() + " (" + failedCount + " episodes)");
          resyncedSourceIds.add(source.getId());
          triggerResync(source.getId());
        }
      } catch (Exception e) {
        log.warn("Failed to check episodes for source '{}': {}", source.getName(), e.getMessage());
      }
    }

    if (toRetry.isEmpty()) {
      log.debug("No failed episodes to retry");
    } else {
      log.info("Auto-retrying {} source(s) with failed episodes: {}", toRetry.size(), toRetry);
    }
  }

  private void checkVectorStoreIntegrity() {
    final var indexedSources =
        sourceRepository.findAllByOrderByCreatedAtDesc().stream()
            .filter(s -> s.getSyncStatus() == SyncStatus.IDLE)
            .toList();

    if (indexedSources.isEmpty()) {
      log.debug("Vector store integrity check: no idle sources to verify");
      return;
    }

    final var resynced = new ArrayList<String>();

    for (final var source : indexedSources) {
      if (resyncedSourceIds.contains(source.getId()))
        continue; // already triggered by retryFailedEpisodes
      try {
        final var units = contentUnitRepository.findBySourceId(source.getId());
        final var indexedUnits =
            units.stream().filter(u -> u.getStatus() == ContentUnitStatus.INDEXED).toList();

        if (indexedUnits.isEmpty()) continue;

        // Spot-check: search for any vector belonging to this source
        final var results = embeddingService.search("test", 1, List.of(source.getId()), null);

        if (results.isEmpty()) {
          resynced.add(source.getName());
          log.warn(
              "Vector store drift: source '{}' (id={}) has {} INDEXED unit(s) but no vectors in "
                  + "ChromaDB. Triggering auto re-sync.",
              source.getName(),
              source.getId(),
              indexedUnits.size());

          triggerResync(source.getId());
        }
      } catch (Exception e) {
        log.warn(
            "Integrity check failed for source '{}' (id={}): {}",
            source.getName(),
            source.getId(),
            e.getMessage());
      }
    }

    if (resynced.isEmpty()) {
      log.info("Vector store integrity check passed: {} source(s) verified", indexedSources.size());
    } else {
      log.warn(
          "Vector store integrity check: auto re-syncing {} source(s): {}",
          resynced.size(),
          resynced);
    }
  }

  private void triggerResync(final UUID sourceId) {
    // Commit the source status + job creation first, then kick off async import.
    // importSource runs on a separate thread and needs the committed data visible.
    final var jobId =
        tx.execute(
            s -> {
              final var source =
                  sourceRepository
                      .findById(sourceId)
                      .orElseThrow(
                          () -> new IllegalStateException("Source not found: " + sourceId));
              source.setSyncStatus(SyncStatus.SYNCING);
              sourceRepository.save(source);

              return jobService.createJob(JobType.SYNC, sourceId, EntityType.SOURCE).getId();
            });

    sourceImportService.importSource(sourceId, jobId);
  }
}
