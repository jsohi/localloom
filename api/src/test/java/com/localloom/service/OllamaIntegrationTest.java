package com.localloom.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;

/**
 * Integration tests for Spring AI OllamaChatModel and OllamaEmbeddingModel using WireMock to
 * simulate the Ollama HTTP API. No Spring Boot context or Testcontainers needed — these tests
 * verify the Spring AI / Ollama protocol contract in isolation.
 *
 * <p>Covers APP-103 test scenarios: chat response, streaming chat, Ollama unavailable, and
 * embedding request with vector dimension verification.
 */
class OllamaIntegrationTest {

  private WireMockServer wireMock;
  private OllamaChatModel chatModel;
  private OllamaEmbeddingModel embeddingModel;

  @BeforeEach
  void setUp() {
    wireMock =
        new WireMockServer(
            WireMockConfiguration.wireMockConfig()
                .dynamicPort()
                .gzipDisabled(true)
                .jettyHeaderBufferSize(16384));
    wireMock.start();

    var baseUrl = "http://localhost:" + wireMock.port();

    var ollamaApi = OllamaApi.builder().baseUrl(baseUrl).build();

    chatModel =
        OllamaChatModel.builder()
            .ollamaApi(ollamaApi)
            .defaultOptions(OllamaChatOptions.builder().model("llama4:scout").build())
            .build();

    embeddingModel =
        OllamaEmbeddingModel.builder()
            .ollamaApi(ollamaApi)
            .defaultOptions(OllamaEmbeddingOptions.builder().model("nomic-embed-text").build())
            .build();
  }

  @AfterEach
  void tearDown() {
    wireMock.stop();
  }

  @Test
  void chatResponseReturnsAssistantMessage() {
    wireMock.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "model": "llama4:scout",
                          "created_at": "2026-03-30T12:00:00Z",
                          "message": {"role": "assistant", "content": "Hello! How can I help you today?"},
                          "done": true,
                          "total_duration": 1000000000,
                          "load_duration": 500000000,
                          "prompt_eval_count": 10,
                          "prompt_eval_duration": 200000000,
                          "eval_count": 8,
                          "eval_duration": 300000000
                        }
                        """)));

    var response = chatModel.call(new Prompt(List.of(new UserMessage("Hello"))));

    assertThat(response.getResult().getOutput().getText())
        .isEqualTo("Hello! How can I help you today?");
  }

  @Test
  void streamingChatResponseReturnsTokenStream() {
    var ndjsonBody =
        """
        {"model":"llama4:scout","created_at":"2026-03-30T12:00:00Z","message":{"role":"assistant","content":"Hello"},"done":false}
        {"model":"llama4:scout","created_at":"2026-03-30T12:00:00Z","message":{"role":"assistant","content":" world"},"done":false}
        {"model":"llama4:scout","created_at":"2026-03-30T12:00:00Z","message":{"role":"assistant","content":"!"},"done":false}
        {"model":"llama4:scout","created_at":"2026-03-30T12:00:00Z","message":{"role":"assistant","content":""},"done":true,"total_duration":1000000000,"load_duration":500000000,"prompt_eval_count":10,"prompt_eval_duration":200000000,"eval_count":3,"eval_duration":300000000}
        """;

    wireMock.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/x-ndjson")
                    .withHeader("Connection", "close")
                    .withBody(ndjsonBody)));

    var tokens =
        chatModel
            .stream(new Prompt(List.of(new UserMessage("Hello"))))
            .collectList()
            .block(Duration.ofSeconds(10));

    assertThat(tokens).isNotNull().isNotEmpty();

    var fullResponse =
        tokens.stream()
            .map(r -> r.getResult().getOutput().getText())
            .filter(t -> t != null && !t.isEmpty())
            .reduce("", String::concat);

    assertThat(fullResponse).isEqualTo("Hello world!");
  }

  @Test
  void chatThrowsWhenOllamaReturnsError() {
    wireMock.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(aResponse().withStatus(400).withBody("Bad Request")));

    assertThatThrownBy(() -> chatModel.call(new Prompt(List.of(new UserMessage("Hello")))))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void embeddingRequestReturnsExpectedDimensions() {
    final var dimensions = 768;
    var embeddingStr = buildEmbeddingJson(dimensions, 0.1);

    wireMock.stubFor(
        post(urlEqualTo("/api/embed"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "model": "nomic-embed-text",
                          "embeddings": [[%s]]
                        }
                        """
                            .formatted(embeddingStr))));

    var result = embeddingModel.embed(new Document("Test embedding input"));

    assertThat(result).hasSize(dimensions);
  }

  @Test
  void embeddingRequestMultipleInputsReturnsSeparateVectors() {
    final var dimensions = 768;
    var embStr1 = buildEmbeddingJson(dimensions, 0.1);
    var embStr2 = buildEmbeddingJson(dimensions, 0.2);

    wireMock.stubFor(
        post(urlEqualTo("/api/embed"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "model": "nomic-embed-text",
                          "embeddings": [[%s], [%s]]
                        }
                        """
                            .formatted(embStr1, embStr2))));

    var request =
        new EmbeddingRequest(
            List.of("First input text", "Second input text"),
            OllamaEmbeddingOptions.builder().model("nomic-embed-text").build());

    var response = embeddingModel.call(request);

    assertThat(response.getResults()).hasSize(2);
    assertThat(response.getResults().getFirst().getOutput()).hasSize(dimensions);
    assertThat(response.getResults().get(1).getOutput()).hasSize(dimensions);
  }

  private static String buildEmbeddingJson(final int dimensions, final double seed) {
    var embedding = new double[dimensions];
    for (var i = 0; i < dimensions; i++) {
      embedding[i] = Math.sin(i * seed);
    }
    return Arrays.stream(embedding)
        .mapToObj(String::valueOf)
        .collect(Collectors.joining(","));
  }
}
