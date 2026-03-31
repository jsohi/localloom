package com.localloom.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.localloom.TestcontainersConfig;
import com.localloom.repository.ConversationRepository;
import com.localloom.repository.MessageRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class TtsControllerIT {

  @TempDir private static Path tempAudioDir;

  private MockMvc mockMvc;
  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ConversationRepository conversationRepository;
  @Autowired private MessageRepository messageRepository;

  @DynamicPropertySource
  static void audioProperties(final DynamicPropertyRegistry registry) {
    registry.add("localloom.audio.dir", () -> tempAudioDir.toString());
  }

  @BeforeEach
  void setUp() {
    messageRepository.deleteAllInBatch();
    conversationRepository.deleteAllInBatch();
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Test
  void generateTtsReturns404ForMissingMessage() throws Exception {
    mockMvc
        .perform(post("/api/v1/messages/00000000-0000-0000-0000-000000000099/tts"))
        .andExpect(status().isNotFound());
  }

  @Test
  void serveAudioReturns404ForMissingFile() throws Exception {
    mockMvc.perform(get("/api/v1/audio/nonexistent.wav")).andExpect(status().isNotFound());
  }

  @Test
  void serveAudioReturnsFileWhenExists() throws Exception {
    var audioFile = tempAudioDir.resolve("test-serve.wav");
    Files.write(audioFile, new byte[] {0, 1, 2, 3});

    mockMvc.perform(get("/api/v1/audio/test-serve.wav")).andExpect(status().isOk());
  }

  @Test
  void serveAudioRejectPathTraversal() throws Exception {
    // Spring rejects ../ paths — GlobalExceptionHandler catches and returns 500
    mockMvc
        .perform(get("/api/v1/audio/../../../etc/passwd"))
        .andExpect(status().isInternalServerError());
  }
}
