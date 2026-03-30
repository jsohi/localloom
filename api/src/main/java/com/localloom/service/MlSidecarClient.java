package com.localloom.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class MlSidecarClient {

  private static final Logger log = LogManager.getLogger(MlSidecarClient.class);

  public record Segment(double start, double end, String text) {}

  public record TranscriptionResult(List<Segment> segments, double duration) {}

  private final RestClient restClient;

  public MlSidecarClient(
      final RestClient.Builder restClientBuilder,
      @Value("${localloom.sidecar.url:http://localhost:8100}") final String sidecarUrl) {
    this.restClient = restClientBuilder.baseUrl(sidecarUrl).build();
    log.info("MlSidecarClient configured with base URL: {}", sidecarUrl);
  }

  @SuppressWarnings("unchecked")
  public TranscriptionResult transcribe(final Path audioFile) {
    log.info("Sending transcription request for file: {}", audioFile);

    final var body = new LinkedMultiValueMap<String, Object>();
    body.add("file", new FileSystemResource(audioFile));

    try {
      final var response =
          restClient
              .post()
              .uri("/transcribe")
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(body)
              .retrieve()
              .body(Map.class);

      if (response == null) {
        throw new MlSidecarException(
            "Null response from sidecar /transcribe for file: " + audioFile);
      }

      final var rawSegments =
          (List<Map<String, Object>>) response.getOrDefault("segments", List.of());
      final var duration = toDouble(response.getOrDefault("duration", 0.0));

      final var segments =
          rawSegments.stream()
              .map(
                  s ->
                      new Segment(
                          toDouble(s.getOrDefault("start", 0.0)),
                          toDouble(s.getOrDefault("end", 0.0)),
                          (String) s.getOrDefault("text", "")))
              .toList();

      log.info("Transcription complete: {} segment(s), duration={}s", segments.size(), duration);
      return new TranscriptionResult(segments, duration);

    } catch (RestClientException e) {
      throw new MlSidecarException("Failed to transcribe file: " + audioFile, e);
    }
  }

  public byte[] synthesizeSpeech(final String text, final String voice) {
    log.info("Requesting TTS synthesis, voice={}, textLength={}", voice, text.length());

    try {
      final var audio =
          restClient
              .post()
              .uri("/tts")
              .contentType(MediaType.APPLICATION_JSON)
              .body(Map.of("text", text, "voice", voice))
              .retrieve()
              .body(byte[].class);

      if (audio == null || audio.length == 0) {
        throw new MlSidecarException("Empty TTS response from sidecar for voice: " + voice);
      }

      log.info("TTS synthesis complete: {} bytes", audio.length);
      return audio;

    } catch (RestClientException e) {
      throw new MlSidecarException("TTS request failed for voice: " + voice, e);
    }
  }

  public boolean isHealthy() {
    try {
      restClient.get().uri("/health").retrieve().toBodilessEntity();
      log.debug("ML sidecar health check: OK");
      return true;
    } catch (RestClientException e) {
      log.warn("ML sidecar health check failed: {}", e.getMessage());
      return false;
    }
  }

  private static double toDouble(final Object value) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    return 0.0;
  }

  public static class MlSidecarException extends RuntimeException {

    public MlSidecarException(final String message) {
      super(message);
    }

    public MlSidecarException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
