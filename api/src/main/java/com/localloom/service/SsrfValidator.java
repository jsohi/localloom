package com.localloom.service;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates URLs against SSRF attacks by blocking requests to private, loopback, and link-local IP
 * addresses. Resolves ALL DNS records for the hostname and blocks if ANY point to an internal
 * address, mitigating DNS rebinding attacks.
 *
 * <p>Hosts listed in {@code localloom.security.ssrf-allowed-hosts} bypass the check (e.g.
 * Docker-internal hostnames in test environments).
 */
@Component
public class SsrfValidator {

  private static final Logger log = LogManager.getLogger(SsrfValidator.class);

  private final Set<String> allowedHosts;

  public SsrfValidator(
      @Value("${localloom.security.ssrf-allowed-hosts:}") final List<String> allowedHosts) {
    this.allowedHosts = Set.copyOf(allowedHosts);
    if (!this.allowedHosts.isEmpty()) {
      log.info("SSRF allowed hosts: {}", this.allowedHosts);
    }
  }

  /**
   * Validates that the given URL does not resolve to a private/internal IP address.
   *
   * @throws IllegalStateException if the URL resolves to an internal address
   */
  public void validate(final String url) {
    try {
      final var host = URI.create(url).getHost();
      if (host == null || host.isBlank()) return;
      if (allowedHosts.contains(host)) return;

      final var addresses = InetAddress.getAllByName(host);
      for (final var addr : addresses) {
        if (addr.isLoopbackAddress()
            || addr.isSiteLocalAddress()
            || addr.isLinkLocalAddress()
            || addr.isAnyLocalAddress()) {
          throw new IllegalStateException(
              "Refusing to fetch from internal/private address: "
                  + host
                  + " ("
                  + addr.getHostAddress()
                  + ")");
        }
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (java.net.UnknownHostException e) {
      // DNS resolution failed — let the downstream HTTP client handle the error
      log.debug(
          "DNS resolution failed for SSRF check (will fail at request time): {}", e.getMessage());
    } catch (IllegalArgumentException e) {
      // Malformed URL — block it rather than silently passing
      throw new IllegalStateException("Malformed URL blocked by SSRF check: " + url, e);
    }
  }
}
