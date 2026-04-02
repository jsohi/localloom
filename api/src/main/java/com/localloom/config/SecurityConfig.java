package com.localloom.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Optional API key authentication. If {@code LOCALLOOM_API_KEY} is set to a non-empty value, all
 * API requests must include a matching {@code X-API-Key} header. If not set or empty, all requests
 * are allowed (local dev default).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityConfig extends OncePerRequestFilter {

  private static final Logger log = LogManager.getLogger(SecurityConfig.class);

  private final String apiKey;
  private final boolean enabled;

  public SecurityConfig(@Value("${localloom.security.api-key:}") final String apiKey) {
    this.apiKey = apiKey;
    this.enabled = apiKey != null && !apiKey.isBlank();
    if (enabled) {
      log.info("API key authentication enabled");
    }
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {

    if (!enabled) {
      filterChain.doFilter(request, response);
      return;
    }

    final var path = request.getRequestURI();
    if (path.startsWith("/actuator") || path.equals("/api/v1/health")) {
      filterChain.doFilter(request, response);
      return;
    }

    final var provided = request.getHeader("X-API-Key");
    if (apiKey.equals(provided)) {
      filterChain.doFilter(request, response);
    } else {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write("{\"status\":401,\"message\":\"Invalid or missing API key\"}");
    }
  }
}
