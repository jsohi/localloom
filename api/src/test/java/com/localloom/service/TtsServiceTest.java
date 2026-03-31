package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.localloom.model.Message;
import com.localloom.model.MessageRole;
import com.localloom.repository.MessageRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TtsServiceTest {

  private MessageRepository messageRepository;
  private MlSidecarClient mlSidecarClient;
  private TtsService ttsService;
  private Path tempAudioDir;

  @BeforeEach
  void setUp() throws IOException {
    messageRepository = mock(MessageRepository.class);
    mlSidecarClient = mock(MlSidecarClient.class);
    tempAudioDir = Files.createTempDirectory("localloom-tts-test");
    ttsService = new TtsService(messageRepository, mlSidecarClient, tempAudioDir.toString());
  }

  @AfterEach
  void tearDown() throws IOException {
    try (var stream = Files.walk(tempAudioDir)) {
      stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  @Test
  void generateTtsCreatesAudioFileAndUpdatesMessage() {
    var messageId = UUID.randomUUID();
    var message = new Message();
    message.setId(messageId);
    message.setRole(MessageRole.ASSISTANT);
    message.setContent("Hello, this is a test answer.");

    when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
    when(mlSidecarClient.synthesizeSpeech(eq("Hello, this is a test answer."), any()))
        .thenReturn(new byte[] {1, 2, 3, 4});
    when(messageRepository.save(any(Message.class))).thenReturn(message);

    var filename = ttsService.generateTts(messageId);

    assertThat(filename).isEqualTo("tts-" + messageId + ".wav");
    assertThat(tempAudioDir.resolve(filename)).exists();
    assertThat(message.getAudioPath()).isEqualTo(filename);
    verify(messageRepository).save(message);
    verify(mlSidecarClient).synthesizeSpeech(eq("Hello, this is a test answer."), any());
  }

  @Test
  void generateTtsReturnsExistingPathWhenAlreadyGenerated() {
    var messageId = UUID.randomUUID();
    var message = new Message();
    message.setId(messageId);
    message.setRole(MessageRole.ASSISTANT);
    message.setContent("Already generated.");
    message.setAudioPath("tts-" + messageId + ".wav");

    when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

    var filename = ttsService.generateTts(messageId);

    assertThat(filename).isEqualTo("tts-" + messageId + ".wav");
    verify(mlSidecarClient, never()).synthesizeSpeech(any(), any());
    verify(messageRepository, never()).save(any());
  }

  @Test
  void generateTtsThrowsNotFoundForMissingMessage() {
    var messageId = UUID.randomUUID();
    when(messageRepository.findById(messageId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> ttsService.generateTts(messageId))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Message not found");
  }

  @Test
  void generateTtsThrowsWhenSidecarFails() {
    var messageId = UUID.randomUUID();
    var message = new Message();
    message.setId(messageId);
    message.setRole(MessageRole.ASSISTANT);
    message.setContent("Test content");

    when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
    when(mlSidecarClient.synthesizeSpeech(any(), any()))
        .thenThrow(new MlSidecarClient.MlSidecarException("Sidecar down"));

    assertThatThrownBy(() -> ttsService.generateTts(messageId))
        .isInstanceOf(MlSidecarClient.MlSidecarException.class)
        .hasMessageContaining("Sidecar down");
  }
}
