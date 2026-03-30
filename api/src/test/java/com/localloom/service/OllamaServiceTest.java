package com.localloom.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.http.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class OllamaServiceTest {

  private WireMockServer wireMock;
  private OllamaService ollamaService;

  @BeforeEach
  void setUp() {
    wireMock =
        new WireMockServer(
            WireMockConfiguration.wireMockConfig()
                .dynamicPort()
                .gzipDisabled(true)
                .jettyHeaderBufferSize(16384));
    wireMock.start();
    var httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    ollamaService =
        new OllamaService(
            RestClient.builder().requestFactory(requestFactory),
            "http://localhost:" + wireMock.port());
  }

  @AfterEach
  void tearDown() {
    wireMock.stop();
  }

  @Test
  void listModelsReturnsModels() {
    wireMock.stubFor(
        get(urlEqualTo("/api/tags"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "models": [
                            {"name": "llama4:scout", "model": "llama4:scout", "size": 5000000000, "modified_at": "2026-03-20T10:00:00Z"},
                            {"name": "nomic-embed-text:latest", "model": "nomic-embed-text:latest", "size": 274000000, "modified_at": "2026-03-15T08:00:00Z"}
                          ]
                        }
                        """)));

    var models = ollamaService.listModels();

    assertThat(models).hasSize(2);
    assertThat(models.getFirst().name()).isEqualTo("llama4:scout");
    assertThat(models.getFirst().size()).isEqualTo(5000000000L);
    assertThat(models.get(1).name()).isEqualTo("nomic-embed-text:latest");
  }

  @Test
  void listModelsReturnsEmptyWhenNoModels() {
    wireMock.stubFor(
        get(urlEqualTo("/api/tags"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"models\": []}")));

    var models = ollamaService.listModels();

    assertThat(models).isEmpty();
  }

  @Test
  void listModelsThrowsWhenOllamaDown() {
    wireMock.stubFor(
        get(urlEqualTo("/api/tags")).willReturn(aResponse().withStatus(500).withBody("error")));

    assertThatThrownBy(() -> ollamaService.listModels())
        .isInstanceOf(OllamaService.OllamaException.class);
  }

  @Test
  void isHealthyReturnsTrueWhenReachable() {
    wireMock.stubFor(
        get(urlEqualTo("/api/tags"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"models\": []}")));

    assertThat(ollamaService.isHealthy()).isTrue();
  }

  @Test
  void isHealthyReturnsFalseWhenUnreachable() {
    wireMock.stubFor(get(urlEqualTo("/api/tags")).willReturn(aResponse().withStatus(503)));

    assertThat(ollamaService.isHealthy()).isFalse();
  }
}
