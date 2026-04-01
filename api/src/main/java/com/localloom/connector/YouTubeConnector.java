package com.localloom.connector;

import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.service.UrlResolver;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Connector for YouTube videos. Resolves metadata via yt-dlp, downloads audio, transcribes via the
 * ML sidecar, and embeds into the vector store.
 */
@Service
public class YouTubeConnector implements SourceConnector {

  private static final Logger log = LogManager.getLogger(YouTubeConnector.class);

  private final UrlResolver urlResolver;
  private final AudioImportSupport audioSupport;

  public YouTubeConnector(final UrlResolver urlResolver, final AudioImportSupport audioSupport) {
    this.urlResolver = urlResolver;
    this.audioSupport = audioSupport;
  }

  @Override
  public SourceType sourceType() {
    return SourceType.YOUTUBE;
  }

  @Override
  public CompletableFuture<Void> importSource(
      final Source source, final UUID jobId, final Integer maxItems) {
    final var sourceId = source.getId();

    try {
      log.info(
          "Resolving YouTube URL for source '{}': {}", source.getName(), source.getOriginUrl());
      final var resolved = urlResolver.resolve(source.getOriginUrl());

      // YouTube videos are typically single episodes; maxItems applies to playlists
      var episodes = resolved.episodes();
      if (maxItems != null && maxItems > 0 && episodes.size() > maxItems) {
        log.info(
            "Limiting import to {} of {} videos for sourceId={}",
            maxItems,
            episodes.size(),
            sourceId);
        episodes = episodes.subList(0, maxItems);
      }

      audioSupport.importEpisodes(source, jobId, resolved, episodes, true);

    } catch (Exception e) {
      log.error(
          "YouTube import failed: sourceId={} jobId={}: {}", sourceId, jobId, e.getMessage(), e);
      audioSupport.failSource(sourceId, jobId, e.getMessage());
    }

    return CompletableFuture.completedFuture(null);
  }
}
