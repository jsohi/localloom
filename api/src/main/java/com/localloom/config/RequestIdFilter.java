package com.localloom.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements Filter {

  private static final String HEADER = "X-Request-Id";
  private static final String MDC_KEY = "requestId";
  private static final Pattern VALID_ID = Pattern.compile("[a-zA-Z0-9\\-]+");

  @Override
  public void doFilter(
      final ServletRequest request, final ServletResponse response, final FilterChain chain)
      throws IOException, ServletException {
    try {
      final var httpRequest = (HttpServletRequest) request;
      var requestId = httpRequest.getHeader(HEADER);
      if (requestId == null
          || requestId.isBlank()
          || requestId.length() > 36
          || !VALID_ID.matcher(requestId).matches()) {
        requestId = UUID.randomUUID().toString().substring(0, 8);
      }
      ThreadContext.put(MDC_KEY, requestId);
      ((HttpServletResponse) response).setHeader(HEADER, requestId);
      chain.doFilter(request, response);
    } finally {
      ThreadContext.remove(MDC_KEY);
    }
  }
}
