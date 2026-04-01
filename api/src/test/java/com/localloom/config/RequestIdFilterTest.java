package com.localloom.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();
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
  void cleanup() {
    ThreadContext.clearAll();
  }

  @Test
  void generatesRequestIdWhenHeaderMissing() throws Exception {
    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(RequestIdFilter.HEADER)).hasSize(8).matches("[a-f0-9]+");
  }

  @Test
  void passesValidRequestIdThrough() throws Exception {
    request.addHeader(RequestIdFilter.HEADER, "abc12345");

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc12345");
  }

  @Test
  void rejectsRequestIdTooLong() throws Exception {
    request.addHeader(RequestIdFilter.HEADER, "a".repeat(37));

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(RequestIdFilter.HEADER)).hasSize(8).isNotEqualTo("a".repeat(8));
  }

  @Test
  void rejectsRequestIdWithInvalidChars() throws Exception {
    request.addHeader(RequestIdFilter.HEADER, "evil\ninjection");

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(RequestIdFilter.HEADER)).hasSize(8).matches("[a-f0-9]+");
  }

  @Test
  void clearsThreadContextAfterRequest() throws Exception {
    filter.doFilter(request, response, chain);

    assertThat(ThreadContext.get(RequestIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void acceptsUuidFormatRequestId() throws Exception {
    request.addHeader(RequestIdFilter.HEADER, "550e8400-e29b-41d4-a716-446655440000");

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(RequestIdFilter.HEADER))
        .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
  }
}
