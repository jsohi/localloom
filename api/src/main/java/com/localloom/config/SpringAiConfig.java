package com.localloom.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    @Bean
    TokenTextSplitter tokenTextSplitter() {
        // ~500 tokens default chunk size, 50 token overlap, min chunk size 5,
        // max chunk size 1000, keep separator enabled
        return new TokenTextSplitter(500, 50, 5, 1000, true);
    }
}
