package com.localloom.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    @Bean
    TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter(500, 50, 5, 1000, true);
    }
}
