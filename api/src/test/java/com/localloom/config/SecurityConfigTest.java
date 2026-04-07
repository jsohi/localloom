package com.localloom.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityConfigTest {

  private static final String API_KEY = "test-key-123";

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final SecurityConfig enabledFilter = new SecurityConfig(API_KEY, objectMapper);
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private MockFilterChain chain;

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    chain = new MockFilterChain();
  }

  @AfterEach
  void clearThreadContext() {
    ThreadContext.remove(RequestIdFilter.MDC_KEY);
  }

  @Test
  void disabledFilterPassesAnyRequestThrough() throws Exception {
    final var disabledFilter = new SecurityConfig("", objectMapper);
    request.setMethod("POST");
    request.setRequestURI("/api/v1/query");

    disabledFilter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(chain.getRequest()).isSameAs(request);
  }

  @Test
  void enabledFilterPassesPreflightOptionsWithoutApiKey() throws Exception {
    request.setMethod("OPTIONS");
    request.setRequestURI("/api/v1/query");
    request.addHeader("Origin", "http://localhost:3000");
    request.addHeader("Access-Control-Request-Method", "POST");

    enabledFilter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(chain.getRequest()).isSameAs(request);
  }

  @Test
  void enabledFilterRejectsBareOptionsWithoutCorsHeaders() throws Exception {
    // Non-CORS OPTIONS (e.g. method-discovery probe) must still hit auth — only true CORS
    // preflights (Origin + Access-Control-Request-Method) are allowed through.
    request.setMethod("OPTIONS");
    request.setRequestURI("/api/v1/query");

    enabledFilter.doFilter(request, response, chain);

    assertUnauthorizedJsonResponse();
  }

  @Test
  void enabledFilterRejectsPostWithoutApiKey() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/api/v1/query");

    enabledFilter.doFilter(request, response, chain);

    assertUnauthorizedJsonResponse();
  }

  @Test
  void enabledFilterAllowsPostWithValidApiKey() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/api/v1/query");
    request.addHeader("X-API-Key", API_KEY);

    enabledFilter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(chain.getRequest()).isSameAs(request);
  }

  @Test
  void enabledFilterRejectsPostWithWrongApiKey() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/api/v1/query");
    request.addHeader("X-API-Key", "wrong-key");

    enabledFilter.doFilter(request, response, chain);

    assertUnauthorizedJsonResponse();
  }

  @Test
  void enabledFilterRejectsKeyWithSameLengthDifferentBytes() throws Exception {
    // Same length as API_KEY ("test-key-123" — 12 chars). Since matches() hashes both sides to a
    // fixed-length SHA-256 digest before comparing, the underlying compare is constant-time
    // regardless of length, but this case still locks in the "wrong-bytes-same-length" behavior.
    request.setMethod("POST");
    request.setRequestURI("/api/v1/query");
    request.addHeader("X-API-Key", "test-key-XYZ");

    enabledFilter.doFilter(request, response, chain);

    assertUnauthorizedJsonResponse();
  }

  @Test
  void enabledFilterRejectsEmptyApiKeyHeader() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/api/v1/query");
    request.addHeader("X-API-Key", "");

    enabledFilter.doFilter(request, response, chain);

    assertUnauthorizedJsonResponse();
  }

  @Test
  void unauthorizedBodyMatchesErrorResponseShape() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/api/v1/query");

    enabledFilter.doFilter(request, response, chain);

    final JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("status").asInt()).isEqualTo(401);
    assertThat(body.get("message").asText()).isEqualTo("Invalid or missing API key");
    assertThat(body.has("timestamp")).isTrue();
    // timestamp must be a parseable ISO-8601 instant (JavaTimeModule serialization)
    Instant.parse(body.get("timestamp").asText());
    assertThat(body.has("requestId")).isTrue();
    assertThat(body.get("requestId").isNull()).isTrue();
  }

  @Test
  void unauthorizedBodyIncludesRequestIdFromThreadContext() throws Exception {
    ThreadContext.put(RequestIdFilter.MDC_KEY, "abc12345");
    request.setMethod("POST");
    request.setRequestURI("/api/v1/query");

    enabledFilter.doFilter(request, response, chain);

    final JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("requestId").asText()).isEqualTo("abc12345");
  }

  private void assertUnauthorizedJsonResponse() throws Exception {
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
    assertThat(response.getContentAsString()).contains("Invalid or missing API key");
    assertThat(chain.getRequest()).isNull();
  }
}
