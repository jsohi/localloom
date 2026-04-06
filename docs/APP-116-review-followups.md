# APP-116 — Post-merge review follow-ups

Gemini review findings on PR #53 that were **deferred at merge time** (2026-04-06) and need to be triaged against `main` before being fixed.

Each item must be validated against current code before fixing — some may already be stale due to the CORS simplification in `e257092` ("Fix E2E — remove WebConfig CORS") and `20d6a3e` ("default to wildcard origin").

---

## HIGH — security-sensitive

### 1. API-key filter blocks CORS preflight `OPTIONS`
- **File:** `api/src/main/java/com/localloom/config/SecurityConfig.java` (~line 48)
- **Issue:** When `LOCALLOOM_API_KEY` is set, browsers send a preflight `OPTIONS` request without custom headers. The filter rejects it with 401, so the real cross-origin request never fires. Cross-origin UI calls will break the moment API-key auth is enabled.
- **Suggested fix:**
  ```java
  if (!enabled || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
    filterChain.doFilter(request, response);
    return;
  }
  ```
- **Test:** add an integration test that enables `LOCALLOOM_API_KEY` and sends an `OPTIONS` preflight from a different origin.

---

## MEDIUM

### 2. Timing attack on API-key comparison
- **File:** `api/src/main/java/com/localloom/config/SecurityConfig.java` (~line 58)
- **Issue:** Uses `String.equals()` which short-circuits on first differing char. Leaks timing info.
- **Suggested fix:**
  ```java
  final var provided = request.getHeader("X-API-Key");
  final boolean matches = provided != null && java.security.MessageDigest.isEqual(
      apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
      provided.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if (matches) { filterChain.doFilter(request, response); ... }
  ```

### 3. Manual 401 JSON response is inconsistent with `ErrorResponse`
- **File:** `api/src/main/java/com/localloom/config/SecurityConfig.java` (~line 62)
- **Issue:** The hand-written `{"status":401,"message":...}` payload omits `timestamp` and `requestId` that `GlobalExceptionHandler` includes. Clients get a differently-shaped error depending on which layer rejected them.
- **Fix:** either serialize an `ErrorResponse` DTO via the configured `ObjectMapper` bean, or delegate to an `AuthenticationEntryPoint`.

### 4. `requestId` is never populated in Log4j2 `ThreadContext`
- **File:** `api/src/main/java/com/localloom/controller/GlobalExceptionHandler.java` (~line 56)
- **Issue:** Code reads `requestId` from `ThreadContext`, but no Java-side filter/interceptor puts it there. Result is always `null`, defeating the correlation-ID hardening goal.
- **Fix:** add a `OncePerRequestFilter` (or reuse the sidecar's `RequestIdMiddleware` pattern) that generates/propagates `X-Request-Id` and pushes it to `ThreadContext.put("requestId", id)` at the start of the request and `remove(...)` at the end.
- **Related:** the ML sidecar already propagates `X-Request-Id` (see `ml-sidecar/app/main.py` RequestIdMiddleware) — the Java side needs the matching piece.

### 5. DB health check ignores `conn.isValid(3)` result
- **File:** `api/src/main/java/com/localloom/controller/HealthController.java` (~line 52)
- **Issue:** Returns `UP` as long as a connection can be obtained, even if the connection is stale/invalid.
- **Fix:**
  ```java
  return conn.isValid(3) ? "UP" : "DOWN";
  ```

### 6. Redundant manual validation in `SourceController`
- **File:** `api/src/main/java/com/localloom/controller/SourceController.java` (~line 86)
- **Issue:** `DetectUrlRequest.url` is `@NotBlank` and the method has `@Valid`, so Spring rejects blank URLs before the method body runs. The explicit `if (url.isBlank())` check is dead code.
- **Fix:** delete the manual `if` block.

### 7. `localloom.security.cors-origins` defined but unused
- **File:** `api/src/main/resources/application.yml` (~line 82)
- **Issue:** Gemini flagged that no Java code references `cors-origins`, so configured origins don't actually get applied.
- **⚠️ Validate first:** the recent commit `e257092` explicitly removed `WebConfig` CORS ("breaks proxy, use wildcard for local"). This finding may be **intentionally stale** — the current design routes CORS through the frontend proxy, not through Spring. Before "fixing," confirm with the author whether `cors-origins` should be:
  - **(a)** deleted from `application.yml` as dead config, or
  - **(b)** re-wired into a `WebMvcConfigurer` bean that doesn't break the proxy.

---

## Validation checklist before fixing each item

1. `git log --oneline e257092..HEAD -- <file>` — has the code moved since the review?
2. Re-read the actual file — is the line/concern still present?
3. If valid → fix + test + new commit.
4. If stale → note the resolution here and mark `[stale]` next to the heading.

## Status tracker

- [ ] #1 HIGH — CORS preflight OPTIONS skip
- [ ] #2 MEDIUM — constant-time API-key compare
- [ ] #3 MEDIUM — error shape consistency in SecurityConfig
- [ ] #4 MEDIUM — request-ID filter wiring
- [ ] #5 MEDIUM — DB health `conn.isValid`
- [ ] #6 MEDIUM — remove redundant validation in SourceController
- [ ] #7 MEDIUM — validate whether cors-origins is dead config (possibly stale)

## Source

Gemini Code Assist review on PR #53, commit `e257092`, 2026-04-06T20:18:32Z.
Original comments: https://github.com/jsohi/localloom/pull/53
