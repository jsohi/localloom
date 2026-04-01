package com.localloom.controller;

import com.localloom.config.ConnectorProperties;
import com.localloom.model.SourceType;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/connectors")
@EnableConfigurationProperties(ConnectorProperties.class)
public class ConnectorController {

  public record ConnectorInfo(SourceType type, String name, boolean enabled) {}

  private final ConnectorProperties connectors;

  public ConnectorController(final ConnectorProperties connectors) {
    this.connectors = connectors;
  }

  @GetMapping
  public List<ConnectorInfo> listConnectors() {
    return List.of(
        new ConnectorInfo(SourceType.PODCAST, "Podcast", connectors.podcast().enabled()),
        new ConnectorInfo(SourceType.FILE_UPLOAD, "File Upload", connectors.fileUpload().enabled()),
        new ConnectorInfo(SourceType.WEB_PAGE, "Web Page", connectors.webPage().enabled()),
        new ConnectorInfo(SourceType.GITHUB, "GitHub", connectors.github().enabled()),
        new ConnectorInfo(SourceType.TEAMS, "Teams", connectors.teams().enabled()));
  }
}
