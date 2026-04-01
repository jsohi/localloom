package com.localloom.connector;

import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.SourceRepository;
import com.localloom.service.JobService;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Connector for file uploads. File processing is handled at upload time by {@link
 * com.localloom.service.FileUploadService}, so this connector simply marks the source as idle.
 */
@Service
public class FileUploadConnector implements SourceConnector {

  private static final Logger log = LogManager.getLogger(FileUploadConnector.class);

  private final SourceRepository sourceRepository;
  private final JobService jobService;
  private final TransactionTemplate tx;

  public FileUploadConnector(
      final SourceRepository sourceRepository,
      final JobService jobService,
      final TransactionTemplate transactionTemplate) {
    this.sourceRepository = sourceRepository;
    this.jobService = jobService;
    this.tx = transactionTemplate;
  }

  @Override
  public SourceType sourceType() {
    return SourceType.FILE_UPLOAD;
  }

  @Override
  public CompletableFuture<Void> importSource(
      final Source source, final UUID jobId, final Integer maxItems) {
    log.info("File upload connector (no-op import): sourceId={}", source.getId());
    tx.executeWithoutResult(
        s -> {
          source.setSyncStatus(SyncStatus.IDLE);
          sourceRepository.save(source);
        });
    jobService.completeJob(jobId);
    return CompletableFuture.completedFuture(null);
  }
}
