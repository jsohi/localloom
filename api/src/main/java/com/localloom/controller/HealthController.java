package com.localloom.controller;

import com.localloom.service.MlSidecarClient;
import com.localloom.service.OllamaService;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Comprehensive health endpoint checking all service dependencies. */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

  private static final Logger log = LogManager.getLogger(HealthController.class);

  private final DataSource dataSource;
  private final OllamaService ollamaService;
  private final MlSidecarClient mlSidecarClient;

  public HealthController(
      final DataSource dataSource,
      final OllamaService ollamaService,
      final MlSidecarClient mlSidecarClient) {
    this.dataSource = dataSource;
    this.ollamaService = ollamaService;
    this.mlSidecarClient = mlSidecarClient;
  }

  @GetMapping
  public Map<String, Object> health() {
    final var components = new LinkedHashMap<String, String>();

    components.put("postgres", checkPostgres());
    components.put("ollama", checkOllama());
    components.put("sidecar", checkSidecar());

    final var allUp = components.values().stream().allMatch("UP"::equals);
    final var result = new LinkedHashMap<String, Object>();
    result.put("status", allUp ? "UP" : "DEGRADED");
    result.put("components", components);
    return result;
  }

  private String checkPostgres() {
    try (final var conn = dataSource.getConnection()) {
      conn.isValid(3);
      return "UP";
    } catch (Exception e) {
      log.warn("Postgres health check failed: {}", e.getMessage());
      return "DOWN";
    }
  }

  private String checkOllama() {
    try {
      return ollamaService.isHealthy() ? "UP" : "DOWN";
    } catch (Exception e) {
      log.warn("Ollama health check failed: {}", e.getMessage());
      return "DOWN";
    }
  }

  private String checkSidecar() {
    try {
      return mlSidecarClient.isHealthy() ? "UP" : "DOWN";
    } catch (Exception e) {
      log.warn("ML Sidecar health check failed: {}", e.getMessage());
      return "DOWN";
    }
  }
}
