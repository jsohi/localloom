package com.localloom.connector;

import com.localloom.model.Source;
import com.localloom.model.SourceType;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Strategy interface for source-type-specific import pipelines. */
public interface SourceConnector {

  /** The source type this connector handles. */
  SourceType sourceType();

  /**
   * Imports the given source asynchronously.
   *
   * @param source the source to import
   * @param jobId the job tracking this import
   * @param maxItems optional limit on items to import (null or 0 = unlimited)
   */
  CompletableFuture<Void> importSource(Source source, UUID jobId, Integer maxItems);
}
