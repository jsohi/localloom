package com.localloom.config;

import java.io.IOException;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RequestIdInterceptor implements ClientHttpRequestInterceptor {

  @Bean
  RestClientCustomizer requestIdCustomizer() {
    return (RestClient.Builder builder) -> builder.requestInterceptor(this);
  }

  @Override
  public ClientHttpResponse intercept(
      final HttpRequest request, final byte[] body, final ClientHttpRequestExecution execution)
      throws IOException {
    final var requestId = ThreadContext.get(RequestIdFilter.MDC_KEY);
    if (requestId != null) {
      request.getHeaders().set(RequestIdFilter.HEADER, requestId);
    }
    return execution.execute(request, body);
  }
}
