package com.localloom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("localloom.connectors")
public record ConnectorProperties(
    ConnectorConfig podcast,
    ConnectorConfig fileUpload,
    ConnectorConfig webPage,
    ConnectorConfig teams,
    ConnectorConfig github) {

  public record ConnectorConfig(boolean enabled) {}
}
