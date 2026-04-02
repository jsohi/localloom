package com.localloom.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Optional API key authentication. If {@code LOCALLOOM_API_KEY} is set, all API requests must
 * include a matching {@code X-API-Key} header. If not set, all requests are allowed (local dev
 * default).
 */
@Configuration
public class SecurityConfig {

  @Component
  @Order(Ordered.HIGHEST_PRECEDENCE)
  @ConditionalOnProperty("localloom.security.api-key")
  static class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LogManager.getLogger(ApiKeyFilter.class);

    private final String apiKey;

    ApiKeyFilter(@Value("${localloom.security.api-key}") final String apiKey) {
      this.apiKey = apiKey;
      log.info("API key authentication enabled");
    }

    @Override
    protected void doFilterInternal(
        final HttpServletRequest request,
        final HttpServletResponse response,
        final FilterChain filterChain)
        throws ServletException, IOException {

      final var path = request.getRequestURI();
      // Allow actuator and health endpoints without auth
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
}
