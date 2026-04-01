package com.localloom.connector;

import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.service.WebPageService;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

/** Connector for web pages. Delegates to {@link WebPageService}. */
@Service
public class WebPageConnector implements SourceConnector {

  private final WebPageService webPageService;

  public WebPageConnector(final WebPageService webPageService) {
    this.webPageService = webPageService;
  }

  @Override
  public SourceType sourceType() {
    return SourceType.WEB_PAGE;
  }

  @Override
  public CompletableFuture<Void> importSource(
      final Source source, final UUID jobId, final Integer maxItems) {
    return webPageService.importWebPage(source.getId(), jobId);
  }
}
