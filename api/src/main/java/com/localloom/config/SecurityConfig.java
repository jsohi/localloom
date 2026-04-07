package com.localloom.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localloom.controller.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Optional API key authentication. If {@code LOCALLOOM_API_KEY} is set to a non-empty value, all
 * API requests must include a matching {@code X-API-Key} header. If not set or empty, all requests
 * are allowed (local dev default).
 *
 * <p>Ordered after {@link RequestIdFilter} (which sits at {@link Ordered#HIGHEST_PRECEDENCE}) so
 * that the request id is already in {@link ThreadContext} when this filter writes a 401 — that
 * keeps the manual unauthorized response shape consistent with {@link
 * com.localloom.controller.GlobalExceptionHandler}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityConfig extends OncePerRequestFilter {

  private static final Logger log = LogManager.getLogger(SecurityConfig.class);

  private final byte[] apiKeyHash;
  private final boolean enabled;
  private final ObjectMapper objectMapper;

  public SecurityConfig(
      @Value("${localloom.security.api-key:}") final String apiKey,
      final ObjectMapper objectMapper) {
    this.enabled = apiKey != null && !apiKey.isBlank();
    // Hash once at construction to a fixed-length digest, then drop the plaintext. This both
    // (a) makes the byte-by-byte compare in matches() truly constant-time across all inputs
    // (no length leak — every comparison is over 32 bytes) and (b) avoids leaving the raw
    // API-key bytes sitting in the heap for the lifetime of the JVM.
    if (enabled) {
      final var plaintext = apiKey.getBytes(StandardCharsets.UTF_8);
      this.apiKeyHash = sha256(plaintext);
      Arrays.fill(plaintext, (byte) 0);
      log.info("API key authentication enabled");
    } else {
      this.apiKeyHash = new byte[0];
    }
    this.objectMapper = objectMapper;
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

    if (CorsUtils.isPreFlightRequest(request)) {
      // Browser CORS preflight cannot carry X-API-Key, so authenticating it would 401 every
      // cross-origin request before the real call ever fires. Narrowed via CorsUtils so non-CORS
      // OPTIONS probes (e.g. method discovery) still hit the auth check.
      filterChain.doFilter(request, response);
      return;
    }

    final var path = request.getRequestURI();
    if (path.startsWith("/actuator") || path.equals("/api/v1/health")) {
      filterChain.doFilter(request, response);
      return;
    }

    final var provided = request.getHeader("X-API-Key");
    if (matches(provided)) {
      filterChain.doFilter(request, response);
    } else {
      writeUnauthorized(response);
    }
  }

  /**
   * Constant-time API-key comparison. The provided value is hashed to the same fixed-length digest
   * as the configured key and the two digests are compared with {@link MessageDigest#isEqual}, so
   * neither the key bytes nor its length can be recovered from response timing.
   */
  private boolean matches(final String provided) {
    if (provided == null) {
      return false;
    }
    final var providedBytes = provided.getBytes(StandardCharsets.UTF_8);
    final var providedHash = sha256(providedBytes);
    Arrays.fill(providedBytes, (byte) 0);
    return MessageDigest.isEqual(apiKeyHash, providedHash);
  }

  private static byte[] sha256(final byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (final NoSuchAlgorithmException e) {
      // SHA-256 is mandated by every JRE; this branch is unreachable.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private void writeUnauthorized(final HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json;charset=UTF-8");
    final var body =
        new ErrorResponse(
            HttpServletResponse.SC_UNAUTHORIZED,
            "Invalid or missing API key",
            Instant.now(),
            ThreadContext.get(RequestIdFilter.MDC_KEY));
    objectMapper.writeValue(response.getWriter(), body);
  }
}
