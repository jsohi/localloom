package com.localloom.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Downloads podcast audio from YouTube (via yt-dlp) or HTTP (RSS enclosures),
 * then converts to 16 kHz mono WAV using ffmpeg.
 */
@Service
public class AudioService {

    private static final Logger log = LoggerFactory.getLogger(AudioService.class);

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2_000L;
    private static final long PROCESS_TIMEOUT_MINUTES = 30L;
    private static final int STREAM_BUFFER_SIZE = 8 * 1024; // 8 KB

    private final Path audioDir;
    private final RestClient restClient;

    public AudioService(
            @Value("${localloom.audio.dir:data/audio}") final String audioDir,
            final RestClient.Builder restClientBuilder) {
        this.audioDir = Paths.get(audioDir);
        this.restClient = restClientBuilder.build();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(audioDir);
        log.info("Audio directory initialised at {}", audioDir.toAbsolutePath());
        validateDependencies();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Full pipeline: download from {@code audioUrl} and convert to 16 kHz mono WAV.
     *
     * @param audioUrl       source URL (YouTube watch URL or direct HTTP audio URL)
     * @param contentUnitId  identifier used for the output file name
     * @param isYoutube      {@code true} to use yt-dlp; {@code false} for plain HTTP
     * @return path to the converted WAV file
     */
    public Path downloadAndConvert(final String audioUrl, final UUID contentUnitId, final boolean isYoutube) {
        log.info("Starting download+convert pipeline for contentUnit={} url={} youtube={}",
                contentUnitId, audioUrl, isYoutube);

        final var downloaded = isYoutube
                ? downloadYoutube(audioUrl, contentUnitId)
                : downloadHttp(audioUrl, contentUnitId);

        // If yt-dlp already wrote a WAV we can still normalise it through ffmpeg
        // to guarantee 16 kHz / mono / PCM.
        return convertToWav(downloaded, contentUnitId);
    }

    /**
     * Downloads YouTube audio via yt-dlp as a WAV file.
     *
     * <p>Command: {@code yt-dlp -x --audio-format wav -o <outputPath> <url>}
     *
     * @param url           YouTube watch URL
     * @param contentUnitId used to derive the output file name
     * @return path to the downloaded file (may not yet be 16 kHz mono)
     */
    public Path downloadYoutube(final String url, final UUID contentUnitId) {
        final var outputPath = audioDir.resolve(contentUnitId + "_raw.wav");
        final var command = List.of(
                "yt-dlp",
                "-x", "--audio-format", "wav",
                "-o", outputPath.toAbsolutePath().toString(),
                url);

        log.info("Downloading YouTube audio: contentUnit={} url={}", contentUnitId, url);
        runWithRetry(command, outputPath, "yt-dlp download");
        return outputPath;
    }

    /**
     * Downloads an HTTP audio enclosure (e.g. from an RSS feed) by streaming
     * the response body directly to disk.
     *
     * @param audioUrl      direct URL to the audio file
     * @param contentUnitId used to derive the output file name
     * @return path to the downloaded file
     */
    public Path downloadHttp(final String audioUrl, final UUID contentUnitId) {
        // Derive extension from URL if present; fall back to .mp3
        final var extension = deriveExtension(audioUrl);
        final var outputPath = audioDir.resolve(contentUnitId + "_raw" + extension);

        log.info("Downloading HTTP audio: contentUnit={} url={}", contentUnitId, audioUrl);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            log.debug("HTTP download attempt {}/{} for contentUnit={}", attempt, MAX_RETRIES, contentUnitId);
            try {
                restClient.get()
                        .uri(audioUrl)
                        .exchange((final var request, final var response) -> {
                            try (final var in = response.getBody();
                                 final var out = Files.newOutputStream(outputPath,
                                         StandardOpenOption.CREATE,
                                         StandardOpenOption.TRUNCATE_EXISTING)) {
                                final var buf = new byte[STREAM_BUFFER_SIZE];
                                int read;
                                while ((read = in.read(buf)) != -1) {
                                    out.write(buf, 0, read);
                                }
                            }
                            return outputPath;
                        });

                log.info("HTTP download completed: contentUnit={} path={}", contentUnitId, outputPath);
                return outputPath;

            } catch (Exception e) {
                log.warn("HTTP download attempt {}/{} failed for contentUnit={}: {}",
                        attempt, MAX_RETRIES, contentUnitId, e.getMessage());
                deleteQuietly(outputPath);

                if (attempt == MAX_RETRIES) {
                    throw new AudioServiceException(
                            "HTTP download failed after " + MAX_RETRIES + " attempts for " + audioUrl, e);
                }
                sleepQuietly(RETRY_DELAY_MS);
            }
        }
        // Unreachable — the loop above always either returns or throws.
        throw new IllegalStateException("Unexpected fall-through in downloadHttp");
    }

    /**
     * Converts {@code inputFile} to a 16 kHz mono WAV using ffmpeg.
     *
     * <p>Command: {@code ffmpeg -i <input> -ar 16000 -ac 1 -f wav <output>}
     *
     * @param inputFile     source audio file
     * @param contentUnitId used to derive the output file name
     * @return path to the converted WAV file
     */
    public Path convertToWav(final Path inputFile, final UUID contentUnitId) {
        final var outputPath = audioDir.resolve(contentUnitId + ".wav");
        final var command = List.of(
                "ffmpeg",
                "-y",               // overwrite without prompt
                "-i", inputFile.toAbsolutePath().toString(),
                "-ar", "16000",
                "-ac", "1",
                "-f", "wav",
                outputPath.toAbsolutePath().toString());

        log.info("Converting to 16kHz mono WAV: contentUnit={} input={}", contentUnitId, inputFile);
        runWithRetry(command, outputPath, "ffmpeg conversion");

        // Clean up the intermediate raw download if it differs from the output
        if (!inputFile.equals(outputPath)) {
            deleteQuietly(inputFile);
        }

        return outputPath;
    }

    /**
     * Verifies that {@code ffmpeg} and {@code yt-dlp} are available on {@code PATH}.
     *
     * @throws AudioServiceException if either binary is missing
     */
    public void validateDependencies() {
        checkBinary("ffmpeg");
        checkBinary("yt-dlp");
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Runs the given command up to {@link #MAX_RETRIES} times, deleting any
     * partial output file between failed attempts.
     */
    private void runWithRetry(final List<String> command, final Path outputPath, final String description) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            log.debug("{} attempt {}/{}: {}", description, attempt, MAX_RETRIES, command);
            try {
                runProcess(command);
                return; // success
            } catch (AudioServiceException e) {
                log.warn("{} attempt {}/{} failed: {}", description, attempt, MAX_RETRIES, e.getMessage());
                deleteQuietly(outputPath);

                if (attempt == MAX_RETRIES) {
                    throw new AudioServiceException(
                            description + " failed after " + MAX_RETRIES + " attempts", e);
                }
                sleepQuietly(RETRY_DELAY_MS);
            }
        }
    }

    /**
     * Starts the process, redirects stderr to the SLF4J logger, and waits for
     * completion within {@link #PROCESS_TIMEOUT_MINUTES} minutes.
     */
    private void runProcess(final List<String> command) {
        final var pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true); // merge stderr into stdout so we capture both

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new AudioServiceException("Failed to start process: " + command.getFirst(), e);
        }

        // Drain stdout/stderr in a virtual thread to avoid blocking
        final var logThread = Thread.ofVirtual().start(() -> {
            try (final var is = process.getInputStream()) {
                final var buf = new byte[STREAM_BUFFER_SIZE];
                int read;
                final var line = new StringBuilder();
                while ((read = is.read(buf)) != -1) {
                    final var chunk = new String(buf, 0, read);
                    line.append(chunk);
                    // Flush complete lines to logger
                    int nl;
                    while ((nl = line.indexOf("\n")) >= 0) {
                        log.debug("[{}] {}", command.getFirst(), line.substring(0, nl).stripTrailing());
                        line.delete(0, nl + 1);
                    }
                }
                if (!line.isEmpty()) {
                    log.debug("[{}] {}", command.getFirst(), line.toString().stripTrailing());
                }
            } catch (IOException ignored) {
                // Process ended; stream closed — nothing actionable
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AudioServiceException("Process interrupted: " + command.getFirst(), e);
        }

        // Wait for log thread to finish draining
        try {
            logThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!finished) {
            process.destroyForcibly();
            throw new AudioServiceException(
                    "Process timed out after " + PROCESS_TIMEOUT_MINUTES + " minutes: " + command.getFirst());
        }

        final var exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new AudioServiceException(
                    "Process exited with code " + exitCode + ": " + command.getFirst());
        }
    }

    private void checkBinary(final String binary) {
        final var command = List.of(binary, "--version");
        try {
            final var p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            final var finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                throw new AudioServiceException(
                        "Dependency check failed for '" + binary + "'. Ensure it is installed and on PATH.");
            }
            log.debug("Dependency check passed: {}", binary);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AudioServiceException(
                    "Dependency '" + binary + "' not found on PATH. Install it before starting the application.", e);
        }
    }

    private static String deriveExtension(final String url) {
        final var q = url.indexOf('?');
        final var path = (q >= 0) ? url.substring(0, q) : url;
        final var dot = path.lastIndexOf('.');
        if (dot >= 0 && (path.length() - dot) <= 5) {
            return path.substring(dot); // e.g. ".mp3"
        }
        return ".mp3";
    }

    private static void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete partial file {}: {}", path, e.getMessage());
        }
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Exception type
    // -------------------------------------------------------------------------

    /**
     * Unchecked exception thrown when an audio download or conversion step fails.
     */
    public static class AudioServiceException extends RuntimeException {

        public AudioServiceException(final String message) {
            super(message);
        }

        public AudioServiceException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
