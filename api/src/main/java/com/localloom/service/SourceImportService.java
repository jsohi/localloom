package com.localloom.service;

import com.localloom.connector.SourceConnector;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.SourceRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Orchestrates source imports by dispatching to the appropriate {@link SourceConnector} based on
 * the source type. Connectors are auto-discovered via Spring DI.
 */
@Service
public class SourceImportService {

  private static final Logger log = LogManager.getLogger(SourceImportService.class);

  private final Map<SourceType, SourceConnector> connectors;
  private final SourceRepository sourceRepository;
  private final JobService jobService;

  public SourceImportService(
      final List<SourceConnector> connectorList,
      final SourceRepository sourceRepository,
      final JobService jobService) {
    this.connectors =
        connectorList.stream().collect(Collectors.toMap(SourceConnector::sourceType, c -> c));
    this.sourceRepository = sourceRepository;
    this.jobService = jobService;

    log.info("Registered {} source connector(s): {}", connectors.size(), connectors.keySet());
  }

  @Async
  public CompletableFuture<Void> importSource(final UUID sourceId, final UUID jobId) {
    return importSource(sourceId, jobId, null);
  }

  @Async
  public CompletableFuture<Void> importSource(
      final UUID sourceId, final UUID jobId, final Integer maxEpisodes) {
    log.info("Starting import pipeline: sourceId={} jobId={}", sourceId, jobId);

    try {
      final var source =
          sourceRepository
              .findById(sourceId)
              .orElseThrow(() -> new IllegalArgumentException("Source not found: " + sourceId));

      final var connector = connectors.get(source.getSourceType());
      if (connector == null) {
        final var msg = "No connector registered for type: " + source.getSourceType();
        log.error(msg);
        source.setSyncStatus(SyncStatus.ERROR);
        sourceRepository.save(source);
        jobService.failJob(jobId, msg);
        return CompletableFuture.completedFuture(null);
      }

      return connector.importSource(source, jobId, maxEpisodes);

    } catch (Exception e) {
      log.error(
          "Import pipeline failed fatally: sourceId={} jobId={}: {}",
          sourceId,
          jobId,
          e.getMessage(),
          e);
      sourceRepository
          .findById(sourceId)
          .ifPresent(
              s -> {
                s.setSyncStatus(SyncStatus.ERROR);
                sourceRepository.save(s);
              });
      jobService.failJob(jobId, e.getMessage());
    }

    return CompletableFuture.completedFuture(null);
  }
}
