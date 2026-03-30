package com.localloom.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

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
}
