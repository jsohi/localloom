package com.localloom.connector;

import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.service.UrlResolver;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Connector for RSS feeds, Apple Podcasts, and Spotify sources. Resolves the feed URL, downloads
 * audio for each episode, transcribes via the ML sidecar, and embeds into the vector store.
 */
@Service
public class MediaConnector implements SourceConnector {

  private static final Logger log = LogManager.getLogger(MediaConnector.class);

  private final UrlResolver urlResolver;
  private final AudioImportSupport audioSupport;
  private final int defaultMaxEpisodes;

  public MediaConnector(
      final UrlResolver urlResolver,
      final AudioImportSupport audioSupport,
      @Value("${localloom.import.max-episodes:0}") final int defaultMaxEpisodes) {
    this.urlResolver = urlResolver;
    this.audioSupport = audioSupport;
    this.defaultMaxEpisodes = defaultMaxEpisodes;
  }

  @Override
  public SourceType sourceType() {
    return SourceType.MEDIA;
  }

  @Override
  public CompletableFuture<Void> importSource(
      final Source source, final UUID jobId, final Integer maxItems) {
    final var sourceId = source.getId();

    try {
      log.info("Resolving URL for source '{}': {}", source.getName(), source.getOriginUrl());
      final var resolved = urlResolver.resolve(source.getOriginUrl());

      final var limit = maxItems != null ? maxItems : defaultMaxEpisodes;
      var episodes = resolved.episodes();
      if (limit > 0 && episodes.size() > limit) {
        log.info(
            "Limiting import to {} of {} episodes for sourceId={}",
            limit,
            episodes.size(),
            sourceId);
        episodes = episodes.subList(0, limit);
      }

      audioSupport.importEpisodes(source, jobId, resolved, episodes, false);

    } catch (Exception e) {
      log.error(
          "Media import failed: sourceId={} jobId={}: {}", sourceId, jobId, e.getMessage(), e);
      audioSupport.failSource(sourceId, jobId, e.getMessage());
    }

    return CompletableFuture.completedFuture(null);
  }
}
