package com.localloom.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

  @Bean
  public CacheManager cacheManager() {
    final var manager = new CaffeineCacheManager("models", "connectors");
    manager.setCaffeine(
        Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(100));
    return manager;
  }
}
