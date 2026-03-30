package com.localloom.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the Python ML sidecar (Whisper transcription + Piper TTS).
 * The sidecar listens at {@code localloom.sidecar.url} (default: http://localhost:8100).
 */
@Service
public class MlSidecarClient {

    private static final Logger log = LoggerFactory.getLogger(MlSidecarClient.class);

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * A single timed transcript segment returned by the sidecar's /transcribe endpoint.
     */
    public record Segment(double start, double end, String text) {}

    /**
     * Full transcription result: the ordered list of timed segments and the total
     * audio duration in seconds.
     */
    public record TranscriptionResult(List<Segment> segments, double duration) {}

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final RestClient restClient;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public MlSidecarClient(
            RestClient.Builder restClientBuilder,
            @Value("${localloom.sidecar.url:http://localhost:8100}") String sidecarUrl) {
        this.restClient = restClientBuilder
                .baseUrl(sidecarUrl)
                .build();
        log.info("MlSidecarClient configured with base URL: {}", sidecarUrl);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sends {@code audioFile} to the sidecar's {@code POST /transcribe} endpoint
     * as a multipart/form-data request and returns the parsed transcription result.
     *
     * @param audioFile path to the WAV file to transcribe
     * @return {@link TranscriptionResult} containing timed segments and total duration
     * @throws MlSidecarException if the request fails or the response cannot be parsed
     */
    @SuppressWarnings("unchecked")
    public TranscriptionResult transcribe(Path audioFile) {
        log.info("Sending transcription request for file: {}", audioFile);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(audioFile));

        try {
            Map<String, Object> response = restClient.post()
                    .uri("/transcribe")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new MlSidecarException("Null response from sidecar /transcribe for file: " + audioFile);
            }

            List<Map<String, Object>> rawSegments =
                    (List<Map<String, Object>>) response.getOrDefault("segments", List.of());
            double duration = toDouble(response.getOrDefault("duration", 0.0));

            List<Segment> segments = rawSegments.stream()
                    .map(s -> new Segment(
                            toDouble(s.getOrDefault("start", 0.0)),
                            toDouble(s.getOrDefault("end", 0.0)),
                            (String) s.getOrDefault("text", "")))
                    .toList();

            log.info("Transcription complete: {} segment(s), duration={:.1f}s", segments.size(), duration);
            return new TranscriptionResult(segments, duration);

        } catch (RestClientException e) {
            throw new MlSidecarException("Failed to transcribe file: " + audioFile, e);
        }
    }

    /**
     * Sends text to the sidecar's {@code POST /tts} endpoint and returns the raw
     * audio bytes (WAV format).
     *
     * @param text  the text to synthesise
     * @param voice the voice identifier to use (sidecar-specific)
     * @return raw audio bytes
     * @throws MlSidecarException if the request fails
     */
    public byte[] synthesizeSpeech(String text, String voice) {
        log.info("Requesting TTS synthesis, voice={}, textLength={}", voice, text.length());

        try {
            byte[] audio = restClient.post()
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

    /**
     * Checks whether the sidecar is reachable by calling {@code GET /health}.
     *
     * @return {@code true} if the sidecar responds with HTTP 2xx; {@code false} otherwise
     */
    public boolean isHealthy() {
        try {
            restClient.get()
                    .uri("/health")
                    .retrieve()
                    .toBodilessEntity();
            log.debug("ML sidecar health check: OK");
            return true;
        } catch (RestClientException e) {
            log.warn("ML sidecar health check failed: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    // -------------------------------------------------------------------------
    // Exception type
    // -------------------------------------------------------------------------

    /**
     * Unchecked exception thrown when communication with the ML sidecar fails.
     */
    public static class MlSidecarException extends RuntimeException {

        public MlSidecarException(String message) {
            super(message);
        }

        public MlSidecarException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
