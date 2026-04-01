package com.localloom.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class MlSidecarClientTest {

  private WireMockServer wireMock;
  private MlSidecarClient client;

  @BeforeEach
  void setUp() {
    wireMock =
        new WireMockServer(
            WireMockConfiguration.wireMockConfig()
                .dynamicPort()
                .gzipDisabled(true)
                .jettyHeaderBufferSize(16384));
    wireMock.start();
    // Force HTTP/1.1 — JDK HttpClient defaults to HTTP/2 which causes RST_STREAM with WireMock.
    var httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    client =
        new MlSidecarClient(
            RestClient.builder().requestFactory(requestFactory),
            "http://localhost:" + wireMock.port());
  }

  @AfterEach
  void tearDown() {
    wireMock.stop();
  }

  @Test
  void transcribeReturnsSegments() throws IOException {
    wireMock.stubFor(
        post(urlEqualTo("/transcribe"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "segments": [
                            {"start": 0.0, "end": 4.8, "text": "Hello world"},
                            {"start": 4.8, "end": 10.0, "text": "Testing sidecar"}
                          ],
                          "duration": 10.0
                        }
                        """)));

    var tmpFile = Files.createTempFile("test-audio", ".wav");
    Files.writeString(tmpFile, "fake audio data");
    try {
      var result = client.transcribe(tmpFile);
      assertThat(result.segments()).hasSize(2);
      assertThat(result.segments().getFirst().text()).isEqualTo("Hello world");
      assertThat(result.duration()).isEqualTo(10.0);

      // Verify the multipart field name matches what the Python sidecar expects
      wireMock.verify(
          postRequestedFor(urlEqualTo("/transcribe"))
              .withRequestBodyPart(
                  new com.github.tomakehurst.wiremock.matching.MultipartValuePatternBuilder(
                          "audio_file")
                      .withBody(containing("fake audio data"))
                      .build()));
    } finally {
      Files.deleteIfExists(tmpFile);
    }
  }

  @Test
  void transcribeHandlesServerError() throws IOException {
    wireMock.stubFor(
        post(urlEqualTo("/transcribe"))
            .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

    var tmpFile = Files.createTempFile("test-audio", ".wav");
    Files.writeString(tmpFile, "fake audio data");
    try {
      assertThatThrownBy(() -> client.transcribe(tmpFile))
          .isInstanceOf(MlSidecarClient.MlSidecarException.class);
    } finally {
      Files.deleteIfExists(tmpFile);
    }
  }

  @Test
  void healthCheckReturnsTrue() {
    wireMock.stubFor(
        get(urlEqualTo("/health"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\": \"ok\"}")));

    assertThat(client.isHealthy()).isTrue();
  }

  @Test
  void healthCheckReturnsFalseWhenDown() {
    wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(503)));

    assertThat(client.isHealthy()).isFalse();
  }

  @Test
  void transcribeHandlesSlowResponse() throws IOException {
    wireMock.stubFor(
        post(urlEqualTo("/transcribe"))
            .willReturn(
                aResponse()
                    .withFixedDelay(3000)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"segments": [{"start": 0.0, "end": 1.0, "text": "Delayed"}], "duration": 1.0}
                        """)));

    var tmpFile = Files.createTempFile("test-audio", ".wav");
    Files.writeString(tmpFile, "fake audio data");
    try {
      var result = client.transcribe(tmpFile);
      assertThat(result.segments()).hasSize(1);
      assertThat(result.segments().getFirst().text()).isEqualTo("Delayed");
    } finally {
      Files.deleteIfExists(tmpFile);
    }
  }
}
