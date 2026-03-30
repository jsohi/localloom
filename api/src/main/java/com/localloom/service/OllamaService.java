package com.localloom.service;

import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class OllamaService {

  private static final Logger log = LogManager.getLogger(OllamaService.class);

  public record ModelInfo(String name, String model, long size, String modifiedAt) {}

  private final RestClient restClient;
  private final String baseUrl;

  public OllamaService(
      final RestClient.Builder restClientBuilder,
      @Value("${spring.ai.ollama.base-url:http://localhost:11434}") final String baseUrl) {
    this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    this.baseUrl = baseUrl;
    log.info("OllamaService configured with base URL: {}", baseUrl);
  }

  @SuppressWarnings("unchecked")
  public List<ModelInfo> listModels() {
    log.debug("Listing available Ollama models");

    try {
      final var response = restClient.get().uri("/api/tags").retrieve().body(Map.class);

      if (response == null) {
        return List.of();
      }

      final var rawModels = (List<Map<String, Object>>) response.getOrDefault("models", List.of());

      var models =
          rawModels.stream()
              .map(
                  m ->
                      new ModelInfo(
                          (String) m.getOrDefault("name", ""),
                          (String) m.getOrDefault("model", ""),
                          toLong(m.getOrDefault("size", 0L)),
                          (String) m.getOrDefault("modified_at", "")))
              .toList();

      log.debug("Found {} Ollama model(s)", models.size());
      return models;

    } catch (RestClientException e) {
      throw new OllamaException("Failed to list Ollama models", e);
    }
  }

  public boolean isHealthy() {
    try {
      restClient.get().uri("/api/tags").retrieve().toBodilessEntity();
      log.debug("Ollama health check: OK");
      return true;
    } catch (RestClientException e) {
      log.warn("Ollama health check failed: {}", e.getMessage());
      return false;
    }
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  private static long toLong(final Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    return 0L;
  }

  public static class OllamaException extends RuntimeException {

    public OllamaException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
