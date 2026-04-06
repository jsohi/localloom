package com.localloom.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Custom business metrics for LocalLoom. */
@Configuration
public class MetricsConfig {

  @Bean
  public Counter episodesProcessedCounter(final MeterRegistry registry) {
    return Counter.builder("localloom.episodes.processed")
        .description("Total episodes processed")
        .register(registry);
  }

  @Bean
  public Timer episodeProcessingTimer(final MeterRegistry registry) {
    return Timer.builder("localloom.episodes.processing.duration")
        .description("Episode processing duration")
        .register(registry);
  }

  @Bean
  public Timer queryTimer(final MeterRegistry registry) {
    return Timer.builder("localloom.queries.duration")
        .description("RAG query duration")
        .register(registry);
  }

  @Bean
  public Timer ttsTimer(final MeterRegistry registry) {
    return Timer.builder("localloom.tts.duration")
        .description("TTS generation duration")
        .register(registry);
  }
}
