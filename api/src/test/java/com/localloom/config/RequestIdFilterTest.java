package com.localloom.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();

  @AfterEach
  void cleanup() {
    ThreadContext.clearAll();
  }

  @Test
  void generatesRequestIdWhenHeaderMissing() throws Exception {
    final var request = new MockHttpServletRequest();
    final var response = new MockHttpServletResponse();
    final var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader("X-Request-Id")).hasSize(8).matches("[a-f0-9]+");
  }

  @Test
  void passesValidRequestIdThrough() throws Exception {
    final var request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "abc12345");
    final var response = new MockHttpServletResponse();
    final var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc12345");
  }

  @Test
  void rejectsRequestIdTooLong() throws Exception {
    final var request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "a".repeat(37));
    final var response = new MockHttpServletResponse();
    final var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader("X-Request-Id")).hasSize(8).isNotEqualTo("a".repeat(8));
  }

  @Test
  void rejectsRequestIdWithInvalidChars() throws Exception {
    final var request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "evil\ninjection");
    final var response = new MockHttpServletResponse();
    final var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader("X-Request-Id")).hasSize(8).matches("[a-f0-9]+");
  }

  @Test
  void clearsThreadContextAfterRequest() throws Exception {
    final var request = new MockHttpServletRequest();
    final var response = new MockHttpServletResponse();
    final var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(ThreadContext.get("requestId")).isNull();
  }

  @Test
  void acceptsUuidFormatRequestId() throws Exception {
    final var request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "550e8400-e29b-41d4-a716-446655440000");
    final var response = new MockHttpServletResponse();
    final var chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader("X-Request-Id"))
        .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
  }
}
