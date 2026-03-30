package com.localloom.controller;

import com.localloom.service.OllamaService;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

  private static final Logger log = LogManager.getLogger(ModelController.class);

  private final OllamaService ollamaService;

  public ModelController(final OllamaService ollamaService) {
    this.ollamaService = ollamaService;
  }

  @GetMapping("/llm")
  public List<OllamaService.ModelInfo> listModels() {
    log.debug("Listing LLM models");
    return ollamaService.listModels();
  }

  @PostMapping("/llm/pull")
  public ResponseEntity<Map<String, String>> pullModel() {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
        .body(Map.of("message", "Model pull not yet implemented"));
  }

  @GetMapping("/llm/health")
  public ResponseEntity<Map<String, Object>> health() {
    var healthy = ollamaService.isHealthy();
    var body =
        Map.<String, Object>of(
            "status", healthy ? "ok" : "unreachable", "baseUrl", ollamaService.getBaseUrl());

    if (healthy) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }
}
