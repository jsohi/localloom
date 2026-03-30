package com.localloom.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localloom.service.OllamaService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

  private static final Logger log = LogManager.getLogger(SpringAiConfig.class);

  @Bean
  TokenTextSplitter tokenTextSplitter() {
    return TokenTextSplitter.builder()
        .withChunkSize(500)
        .withMinChunkSizeChars(50)
        .withMinChunkLengthToEmbed(5)
        .withMaxNumChunks(1000)
        .withKeepSeparator(true)
        .build();
  }

  /** Jackson 2.x ObjectMapper — required by SourceImportService (SB4 ships Jackson 3.x). */
  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  @Bean
  @ConditionalOnBean(ChatModel.class)
  ChatClient chatClient(
      final ChatModel chatModel,
      @Value("${localloom.chat.system-prompt}") final String systemPrompt) {
    return ChatClient.builder(chatModel).defaultSystem(systemPrompt).build();
  }

  @Bean
  ApplicationRunner ollamaHealthCheck(final OllamaService ollamaService) {
    return args -> {
      if (ollamaService.isHealthy()) {
        log.info("Ollama is reachable at {}", ollamaService.getBaseUrl());
      } else {
        log.warn(
            "Ollama is not reachable at {} — chat features will be unavailable",
            ollamaService.getBaseUrl());
      }
    };
  }
}
