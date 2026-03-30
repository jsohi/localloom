package com.localloom;

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
}
