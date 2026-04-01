package com.localloom.controller;

import com.localloom.model.SourceType;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorController {

  public record ConnectorInfo(SourceType type, String name, boolean enabled) {}

  private final boolean podcastEnabled;
  private final boolean fileUploadEnabled;
  private final boolean webPageEnabled;
  private final boolean teamsEnabled;
  private final boolean githubEnabled;

  public ConnectorController(
      @Value("${localloom.connectors.podcast.enabled:true}") final boolean podcastEnabled,
      @Value("${localloom.connectors.file-upload.enabled:true}") final boolean fileUploadEnabled,
      @Value("${localloom.connectors.web-page.enabled:false}") final boolean webPageEnabled,
      @Value("${localloom.connectors.teams.enabled:false}") final boolean teamsEnabled,
      @Value("${localloom.connectors.github.enabled:false}") final boolean githubEnabled) {
    this.podcastEnabled = podcastEnabled;
    this.fileUploadEnabled = fileUploadEnabled;
    this.webPageEnabled = webPageEnabled;
    this.teamsEnabled = teamsEnabled;
    this.githubEnabled = githubEnabled;
  }

  @GetMapping
  public List<ConnectorInfo> listConnectors() {
    return List.of(
        new ConnectorInfo(SourceType.PODCAST, "Podcast", podcastEnabled),
        new ConnectorInfo(SourceType.FILE_UPLOAD, "File Upload", fileUploadEnabled),
        new ConnectorInfo(SourceType.WEB_PAGE, "Web Page", webPageEnabled),
        new ConnectorInfo(SourceType.GITHUB, "GitHub", githubEnabled),
        new ConnectorInfo(SourceType.TEAMS, "Teams", teamsEnabled));
  }
}
