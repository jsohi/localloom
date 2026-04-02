package com.localloom.service;

import com.localloom.repository.MessageRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TtsService {

  private static final Logger log = LogManager.getLogger(TtsService.class);
  private static final String DEFAULT_VOICE = "en_US-lessac-high";

  private final MessageRepository messageRepository;
  private final MlSidecarClient mlSidecarClient;
  private final Path audioDirectory;

  public TtsService(
      final MessageRepository messageRepository,
      final MlSidecarClient mlSidecarClient,
      @Value("${localloom.audio.dir:data/audio}") final String audioDir) {
    this.messageRepository = messageRepository;
    this.mlSidecarClient = mlSidecarClient;
    this.audioDirectory = Path.of(audioDir);
  }

  public String generateTts(final UUID messageId) {
    log.info("Generating TTS for messageId={}", messageId);

    final var message =
        messageRepository
            .findById(messageId)
            .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));

    if (message.getAudioPath() != null) {
      log.info("TTS already exists for messageId={}, returning existing path", messageId);
      return message.getAudioPath();
    }

    final var audioBytes = mlSidecarClient.synthesizeSpeech(message.getContent(), DEFAULT_VOICE);

    final var filename = "tts-" + messageId + ".wav";
    final var filePath = audioDirectory.resolve(filename);

    try {
      Files.createDirectories(audioDirectory);
      Files.write(filePath, audioBytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write TTS audio file: " + filePath, e);
    }

    message.setAudioPath(filename);
    messageRepository.save(message);

    log.info(
        "TTS audio saved: messageId={} filename={} bytes={}",
        messageId,
        filename,
        audioBytes.length);
    return filename;
  }
}
