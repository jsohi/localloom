package com.localloom.controller;

import com.localloom.service.TtsService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class TtsController {

  private static final Logger log = LogManager.getLogger(TtsController.class);

  private final TtsService ttsService;
  private final Path audioDirectory;

  public TtsController(
      final TtsService ttsService,
      @Value("${localloom.audio.dir:data/audio}") final String audioDir) {
    this.ttsService = ttsService;
    this.audioDirectory = Path.of(audioDir);
  }

  @PostMapping("/messages/{id}/tts")
  public ResponseEntity<Map<String, String>> generateTts(@PathVariable final UUID id) {
    log.info("TTS generation requested for messageId={}", id);

    try {
      final var filename = ttsService.generateTts(id);
      final var audioUrl = "/api/v1/audio/" + filename;
      return ResponseEntity.ok(Map.of("audio_url", audioUrl));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    }
  }

  @GetMapping("/audio/{filename}")
  public ResponseEntity<Resource> serveAudio(@PathVariable final String filename) {
    log.debug("Serving audio file: {}", filename);

    final var filePath = audioDirectory.resolve(filename).normalize();

    // Prevent path traversal
    if (!filePath.startsWith(audioDirectory)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
    }

    if (!Files.exists(filePath)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio file not found: " + filename);
    }

    final var resource = new FileSystemResource(filePath);

    String contentType;
    try {
      contentType = Files.probeContentType(filePath);
    } catch (IOException e) {
      contentType = null;
    }
    if (contentType == null) {
      contentType = "audio/wav";
    }

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, contentType)
        .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
        .body(resource);
  }
}
