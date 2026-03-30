package com.localloom;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.chromadb.ChromaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>("postgres:16-alpine");
  }

  @Bean
  @ServiceConnection
  ChromaDBContainer chromadb() {
    return new ChromaDBContainer("chromadb/chroma:1.0.12");
  }

  /** Stub ChatModel for tests — avoids needing Ollama or @MockitoBean in every test class. */
  @Bean
  ChatModel chatModel() {
    return new ChatModel() {
      @Override
      public ChatResponse call(final Prompt prompt) {
        return new ChatResponse(
            java.util.List.of(new Generation(new AssistantMessage("Test response"))));
      }
    };
  }
}
