package com.localloom.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityConfigTest {

  private static final String API_KEY = "test-key-123";

  private final SecurityConfig enabledFilter = new SecurityConfig(API_KEY);
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private MockFilterChain chain;

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    chain = new MockFilterChain();
  }

  @Test
  void disabledFilterPassesAnyRequestThrough() throws Exception {
    final var disabledFilter = new SecurityConfig("");
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

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void enabledFilterRejectsPostWithoutApiKey() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/api/v1/query");

    enabledFilter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(chain.getRequest()).isNull();
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

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(chain.getRequest()).isNull();
  }
}
