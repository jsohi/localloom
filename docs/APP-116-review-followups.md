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
- **Issue:** Gemini flagged that no Java code references `cors-origins`, so configured origins don't actually get applied. **Confirmed during APP-117**: `grep` for `cors-origins` / `CORS_ORIGINS` / `allowedOrigins` in `api/src/main/java` returns zero matches. The variable is dead config.
- **Context:** the commit `e257092` explicitly removed `WebConfig` CORS ("breaks proxy, use wildcard for local"). The current design routes CORS through the frontend dev proxy, not through Spring. So this is intentional dead config — but the docs (and the env var being present in `application.yml`) imply it works.
- **Decision needed:**
  - **(a)** Delete `localloom.security.cors-origins` from `application.yml` and from `docs/CONFIGURATION.md` as dead config. Cleanest. Recommended.
  - **(b)** Re-wire into a `WebMvcConfigurer` bean that doesn't break the proxy. Heavier; only if you actually want server-side CORS gating in addition to proxy gating.

### 8. ml-sidecar pytest: 8 drifted tests — **[fixed in APP-117]**
- **Was:** 8 pre-existing failing sidecar tests blocking a strict `make push` pipeline.
- **Files fixed in `ml-sidecar/tests/`:**
  - `test_config.py::test_default_settings` — assertion updated from `en_US-lessac-medium` → `en_US-lessac-high` to match the actual default in `app/config.py`.
  - `test_tts_service.py::test_lazy_voice_loading` / `test_different_voices_loaded_separately` / `test_synthesize_splits_long_text` — added `@patch("app.services.tts_service.TTSService._ensure_voice_downloaded")` so the synthetic voice names (`test-voice`, `voice-a`, `voice-b`) bypass the `_VOICE_PATHS` allowlist check that was added in APP-114.
  - `test_whisper_service.py` (4 tests) — rewrote to patch `WhisperService._get_pool` instead of `WhisperModel`. The original patch target was a module-level class that gets imported inside `_transcribe_in_worker`, which runs in a `ProcessPoolExecutor` subprocess where the patch does not propagate. Patching at the pool level mocks `pool.submit` to return a fake future, so we can assert on what the service forwards without spinning up a real worker or loading a model.
- **Result:** `30 passed, 0 failed` for `uv run pytest --ignore=tests/ml`.
- **Push pipeline:** `scripts/push.sh` Step 4/5 no longer suppresses sidecar pytest with `|| true` — the suppression and the comment have been removed.
- **Status:** RESOLVED in APP-117. Leaving the entry in the followups doc for historical traceability.

### 9. `scripts/restore.sh` swallows Postgres restore exit code (pre-existing)
- **File:** `scripts/restore.sh:79`
- **Code:**
  ```bash
  gunzip -c "$PG_FILE" | docker exec -i "$PG_CONTAINER" psql -U "${POSTGRES_USER:-localloom}" -d "${POSTGRES_DB:-localloom}" -q --set ON_ERROR_STOP=on 2>&1 | tail -1 || true
  ```
- **Issue:** the trailing `|| true` discards the exit status of an *actual data restore operation*. If the Postgres restore fails partway through, the script silently reports success and the user thinks they have a recovered database. The exact opposite of what disaster-recovery tooling should do.
- **Severity:** HIGH for a pre-prod release path. Disaster recovery tooling that lies is worse than no tooling.
- **Pre-existing:** not introduced by APP-117. Found during the `|| true` audit.
- **Fix:** capture the exit status with `PIPESTATUS` (since `tail` is the last command in the pipeline, the psql exit is at `${PIPESTATUS[1]}`) and propagate it. Or restructure to write `psql` output to a temp file and check the exit before any tee/tail.
  ```bash
  set -o pipefail  # so the pipeline as a whole fails if psql does
  gunzip -c "$PG_FILE" \
    | docker exec -i "$PG_CONTAINER" psql -U ... -d ... -q --set ON_ERROR_STOP=on 2>&1 \
    | tail -1
  ```
  (Drop the `|| true`. With `set -o pipefail` already at the top of the script — verify — the pipeline failure will propagate.)

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
- [ ] #7 MEDIUM — `cors-origins` is confirmed dead config; delete or rewire
- [x] #8 MEDIUM — **fixed in APP-117**: 8 drifted ml-sidecar tests rewritten, `|| true` removed from `scripts/push.sh` Step 4/5
- [ ] #9 HIGH — `scripts/restore.sh` swallows Postgres restore exit code via `|| true`; disaster recovery silently lies on failure

## Source

Gemini Code Assist review on PR #53, commit `e257092`, 2026-04-06T20:18:32Z.
Original comments: https://github.com/jsohi/localloom/pull/53
