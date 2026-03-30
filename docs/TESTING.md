# LocalLoom — Testing Plan

## 1. Overview

This document defines the full testing strategy for LocalLoom — unit tests, integration tests, and end-to-end tests across both the Java API and Python ML sidecar.

All tests run in CI without Ollama, ffmpeg, or yt-dlp:
- **Java API**: Testcontainers (PostgreSQL, ChromaDB), WireMock (ML sidecar), ONNX embeddings (`spring-ai-transformers`)
- **Python sidecar**: pytest + httpx with mocked `WhisperModel` (no model download needed)

> **Diagrams**: See [DIAGRAMS.md](./DIAGRAMS.md) for architecture context. See [DESIGN.md](./DESIGN.md) for data model and API details.

---

## 2. Test Layers

| Layer | Scope | Tools | Runs Against |
|-------|-------|-------|-------------|
| **Unit** | Single class/function, pure logic | JUnit 5 + Mockito, pytest + mock | Mocks only, no containers |
| **Integration** | Multi-component flows | Spring Boot Test, Testcontainers, WireMock, ONNX, pytest + httpx | Real PostgreSQL, real ChromaDB, mocked sidecar |
| **End-to-End** | Full ingest pipeline scenarios | Spring Boot Test + Testcontainers + WireMock | All layers: API → DB → vector store → mocked external services |

This document covers all three layers across the API and ML sidecar.

---

## 3. Sample Data

### 3.1 Fixtures Directory Structure

```
api/src/test/resources/fixtures/
├── audio/
│   ├── short-clip-10s.wav          # 10-second WAV (16kHz mono) for transcription tests
│   └── short-clip-10s.transcript.json  # Pre-computed Whisper output for this clip
├── podcasts/
│   ├── sample-rss-feed.xml         # Valid RSS feed with 3 episodes
│   ├── sample-rss-feed-empty.xml   # Valid RSS feed with 0 episodes
│   └── sample-rss-feed-malformed.xml  # Invalid XML for error handling tests
├── sources/
│   ├── confluence-page.html        # Sample Confluence page HTML
│   ├── teams-thread.json           # Sample Teams thread with 5 messages
│   ├── github-file-tree.json       # Sample GitHub repo file listing
│   └── github-java-file.java      # Sample Java source file
├── uploads/
│   ├── sample-notes.md             # Markdown file for upload testing
│   ├── sample-notes.pdf            # PDF file for upload testing
│   └── sample-notes.txt            # Plain text file for upload testing
├── embeddings/
│   └── precomputed-chunks.json     # Pre-embedded chunks for vector store tests
└── sql/
    ├── seed-sources.sql            # 3 sources (podcast, confluence, upload)
    ├── seed-content-units.sql      # 5 content units across source types
    ├── seed-content-fragments.sql  # 15 fragments with location data
    └── seed-conversations.sql      # 1 conversation with 4 messages + citations

ml-sidecar/tests/fixtures/
├── audio/
│   ├── short-clip-10s.wav          # Same 10-second clip
│   └── silence-5s.wav              # 5-second silence for edge case
└── tts/
    └── expected-output-format.wav  # Reference WAV for format validation
```

### 3.2 Sample Audio

A **10-second WAV clip** (`short-clip-10s.wav`) is the primary test audio. It contains clear English speech to produce deterministic-enough transcription output. This file is committed to the repo (~160 KB at 16kHz mono).

The pre-computed transcript (`short-clip-10s.transcript.json`) is used when tests need transcript data without running Whisper:

```json
{
  "segments": [
    {"start": 0.0, "end": 4.8, "text": "Welcome to the sample podcast episode."},
    {"start": 4.8, "end": 10.0, "text": "This is a short clip used for integration testing."}
  ],
  "duration": 10.0
}
```

### 3.3 Sample RSS Feed

`sample-rss-feed.xml` contains 3 episodes with enclosure URLs pointing to `localhost` (intercepted by WireMock in tests):

| Episode | Title | Duration |
|---------|-------|----------|
| 1 | "Getting Started with RAG" | 10s |
| 2 | "Vector Databases Explained" | 10s |
| 3 | "Local LLMs in Production" | 10s |

### 3.4 SQL Seed Data

Seed scripts populate a known database state for tests that start mid-pipeline (e.g., testing RAG without running ingestion). All IDs use deterministic UUIDs (`00000000-0000-0000-0000-00000000000X`) for easy assertion.

---

## 4. Unit Tests (No Containers)

Fast tests with mocks only — no Docker, no network.

### 4.0a Java — UrlResolver

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Detect YouTube URL | `youtube.com/watch?v=xxx`, `youtu.be/xxx` → `YOUTUBE` | URL type detection |
| 2 | Detect Apple Podcasts URL | `podcasts.apple.com/...` → `APPLE_PODCASTS` | URL type detection |
| 3 | Detect Spotify URL | `open.spotify.com/show/...` → `SPOTIFY` | URL type detection |
| 4 | Detect RSS URL | `https://example.com/feed.xml` → `RSS` | Fallback detection |
| 5 | Resolve RSS feed | Mock RestClient → RSS XML → parse episodes | RSS parsing logic |
| 6 | Resolve Apple Podcasts | Mock iTunes Lookup API response → extract feed URL | iTunes API parsing |
| 7 | Resolve Spotify | Mock oEmbed API → podcast name → mock iTunes search → feed URL | Spotify→iTunes chain |

### 4.0b Java — AudioService

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Derive extension from URL | `.mp3`, `.wav`, `.m4a`, URL with query params, no extension → `.mp3` | `deriveExtension()` |
| 2 | Constants | MAX_RETRIES = 3, RETRY_DELAY_MS = 2000, PROCESS_TIMEOUT = 30 min | Config sanity |

### 4.0c Java — EmbeddingService Filter Logic

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Single sourceId filter | One UUID → `eq("source_id", ...)` | Simple filter |
| 2 | Multiple sourceId filter | Two UUIDs → `or(eq, eq)` wrapped in `group()` | OR chain |
| 3 | Combined sourceId + sourceType | Both filters → `and(group(sourceIdOp), group(sourceTypeOp))` | AND combination |
| 4 | Null/empty inputs | null list, empty list → returns null (no filter) | Edge cases |
| 5 | Single sourceType | One type → `eq("source_type", ...)` | Enum-to-string mapping |

### 4.0d Python — WhisperService

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Lazy model loading | First call loads WhisperModel, second reuses cached | `_models` dict cache |
| 2 | Model override | `transcribe(path, model="base")` loads `base` not default | Model name routing |
| 3 | Default model from config | No model param → uses `settings.whisper_model` | Config integration |
| 4 | Transcription result parsing | Mock `WhisperModel.transcribe()` → verify `TranscriptionResult` segments | Pydantic model |

### 4.0e Python — Config

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Default settings | `Settings()` has expected defaults (port 8100, model large-v3-turbo) | Defaults |
| 2 | Env override | Set `LOCALLOOM_WHISPER_MODEL=base` → settings picks it up | Env prefix |

---

## 5. Integration Test Scenarios

### 5.1 API Server — Database (Spring Boot + PostgreSQL)

**Infrastructure**: Testcontainers (PostgreSQL)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Flyway migrations run cleanly | Start app with empty DB, all migrations apply | Schema correctness |
| 2 | Source CRUD | Create, read, update, delete Source via repository | JPA mapping, JSONB config |
| 3 | ContentUnit lifecycle | Create ContentUnit, transition through status states | Status enum, FK constraints |
| 4 | ContentFragment ordering | Insert fragments, verify sequence_index ordering | Fragment model |
| 5 | Conversation + Message persistence | Save conversation with messages and JSONB citations | JSONB serialization |
| 6 | Job progress tracking | Create job with entity_type (SOURCE/CONTENT_UNIT), update progress, mark complete/error | Job lifecycle |
| 7 | Cascade delete | Delete Source, verify ContentUnits and fragments are removed | FK cascade rules |

### 5.2 API Server — ChromaDB (Spring AI + Vector Store)

**Infrastructure**: Testcontainers (ChromaDB)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Store and retrieve embeddings | Embed sample text, search by similarity | Spring AI ChromaDB integration |
| 2 | Metadata filtering | Store chunks with source_type metadata, filter on search | Metadata filter queries |
| 3 | Collection per source | Create two sources, verify separate collections | Collection isolation |
| 4 | Delete source vectors | Delete all vectors for a source, verify empty | Cleanup logic |
| 5 | Chunk indexing with TokenTextSplitter | Split long text, embed, verify chunk count and overlap | Chunking config |

### 5.3 API Server — ML Sidecar (Spring Boot + Python)

**Infrastructure**: WireMock (mock sidecar responses)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Transcription request | POST audio to `/transcribe`, verify segment parsing | MlSidecarClient |
| 2 | TTS request | POST text to `/tts`, verify WAV response handling | TTS flow |
| 3 | Sidecar health check | GET `/health`, verify status parsing | Health monitoring |
| 4 | Sidecar unavailable | Sidecar down, verify graceful degradation | Error handling |
| 5 | Transcription timeout | Slow response, verify timeout and retry | Resilience |

### 5.4 API Server — Embeddings (Spring AI + ONNX TransformersEmbeddingModel)

**Infrastructure**: `spring-ai-transformers` (ONNX `all-MiniLM-L6-v2`), Testcontainers (ChromaDB)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Embed and search | Embed sample text via ONNX, similarity search returns relevant results | Real embedding pipeline |
| 2 | Metadata filtering | Store chunks with source_type, filter on search | FilterExpressionBuilder |
| 3 | Delete by source | Delete all vectors for a source, verify empty | Cleanup logic |
| 4 | Chunk indexing | Split long text via TokenTextSplitter, verify chunk count and metadata | Chunking config |

> **Note**: Ollama ChatModel tests (RAG, streaming) will be added in Phase 2 when RAG service is implemented. ChatModel will be mocked via WireMock in CI.

### 5.5 ML Sidecar — API Tests (Python, no ML models)

**Infrastructure**: pytest + httpx `AsyncClient` with FastAPI `TestClient` (no Docker, no models)

These tests mock `whisper_service` to avoid downloading/loading Whisper models in CI.

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Health endpoint | GET `/health` → `{"status": "ok"}` | Router wiring |
| 2 | Transcribe success | POST `/transcribe` with WAV, mock whisper_service → segments | Endpoint parsing, temp file lifecycle |
| 3 | Transcribe empty file | POST `/transcribe` with 0 bytes → 422 | Input validation |
| 4 | Transcribe bad content-type | POST `/transcribe` with `text/plain` → 422 | Content-type guard |
| 5 | Transcribe service error | POST `/transcribe`, mock raises exception → 500 | Error handling |
| 6 | TTS not implemented | POST `/tts` → 501 | Stub returns correct status |
| 7 | Config defaults | Verify `Settings()` defaults match expected values | Pydantic settings |
| 8 | Config env override | Set `LOCALLOOM_WHISPER_MODEL=base`, verify settings pick it up | Env prefix |

### 5.6 ML Sidecar — Whisper Integration (Python, gated)

**Infrastructure**: Real Whisper model (requires model download, CI-gated via `@pytest.mark.ml`)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Transcribe 10s clip | POST `short-clip-10s.wav`, verify segments returned with timestamps | Whisper pipeline end-to-end |
| 2 | Transcribe silence | POST `silence-5s.wav`, verify empty/minimal segments | Edge case |
| 3 | Model lazy loading | First transcription loads model, second reuses cached instance | Lazy init singleton |
| 4 | Model override | POST with `model=base`, verify different model used | Request param routing |

### 5.7 ML Sidecar — Piper TTS Integration (Future, requires APP-91)

**Infrastructure**: Real Piper model (requires model download, CI-gated via `@pytest.mark.ml`)

TTS endpoint is currently a 501 stub. Tests will be added when Piper integration is implemented.

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Generate speech | POST short text, verify WAV response | TTS pipeline |
| 2 | Long text handling | POST 500-word text, verify output | Sentence splitting |
| 3 | Empty text | POST empty string, verify 422 error | Input validation |

### 5.8 Source Connector — Podcast

**Infrastructure**: WireMock (mock RSS feeds + audio URLs), Testcontainers (PostgreSQL, ChromaDB)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Discover episodes from RSS | Parse `sample-rss-feed.xml`, verify 3 ContentUnits created | RSS parsing |
| 2 | Empty RSS feed | Parse `sample-rss-feed-empty.xml`, verify 0 ContentUnits | Edge case |
| 3 | Malformed RSS feed | Parse `sample-rss-feed-malformed.xml`, verify error handling | Error handling |
| 4 | Fetch audio | Download audio from WireMock URL, verify WAV conversion | Audio download |
| 5 | Full podcast pipeline | RSS → discover → fetch → transcribe → embed | End-to-end connector |

### 5.9 Source Connector — File Upload

**Infrastructure**: Testcontainers (PostgreSQL, ChromaDB)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Upload markdown file | POST multipart, verify Source + ContentUnit created | Upload flow |
| 2 | Upload PDF file | POST PDF, verify text extraction | PDF handling |
| 3 | Upload unsupported type | POST `.exe`, verify rejection | Validation |
| 4 | Full upload pipeline | Upload → extract → embed → query | End-to-end connector |

---

## 6. End-to-End Test Scenarios

These tests exercise the full stack from API request through to database + vector store, using real (containerized) dependencies.

**Infrastructure**: Testcontainers (PostgreSQL, ChromaDB), WireMock (sidecar, external feeds), ONNX embeddings

### 6.1 Phase 1 — Ingest Pipeline E2E (PR #13, APP-109)

These are testable now — all required services are implemented.

| # | Scenario | Flow | Key Assertions |
|---|----------|------|-----------------|
| E2E-1 | **Podcast import pipeline** | `POST /api/v1/sources` → UrlResolver (mocked) → AudioService (mocked) → WireMock `/transcribe` → EmbeddingService → ChromaDB | Source SYNCING → IDLE, ContentUnits INDEXED, fragments saved, vectors searchable in ChromaDB, Job COMPLETE with progress 1.0 |
| E2E-2 | **Source deletion cleanup** | Import podcast → embed → `DELETE /api/v1/sources/{id}` → verify DB + ChromaDB + audio files cleaned up | No orphaned records, vectors, or files |
| E2E-3 | **Job progress tracking** | `POST /api/v1/sources` → poll `GET /api/v1/jobs/{id}` → verify progress 0.0 → 1.0 | Progress monotonically increases, final status COMPLETE |
| E2E-4 | **Partial failure** | Import 2 episodes, WireMock fails transcription for episode 2 → verify episode 1 INDEXED, episode 2 ERROR, Job FAILED with error summary | Partial success preserved, error isolated |
| E2E-5 | **Re-sync** | Import → complete → `POST /api/v1/sources/{id}/sync` → verify new Job created, source re-imported | Second import idempotent |
| E2E-6 | **Error recovery** | Import → sidecar completely down → Job FAILED → sidecar recovers → re-sync succeeds | New Job reaches COMPLETE |

### 6.2 Phase 2 — RAG E2E (Future, requires APP-83)

Blocked on RAG service + conversation/chat controller implementation.

| # | Scenario | Flow | Key Assertions |
|---|----------|------|-----------------|
| E2E-7 | **Podcast import → RAG query** | Import podcast → embed → ask question via chat API → answer with citations | Answer contains citation with episode title and timestamp |
| E2E-8 | **File upload → query** | Upload markdown → extract → embed → ask question → answer cites file | Answer references uploaded file |
| E2E-9 | **Multi-source query** | Import podcast + upload file → query across both | Citations from both PODCAST and FILE_UPLOAD types |
| E2E-10 | **Conversation continuity** | Ask question → follow-up in same conversation → context maintained | Second answer references history |

### 6.3 Phase 4+ — Advanced E2E (Future)

| # | Scenario | Flow | Key Assertions |
|---|----------|------|-----------------|
| E2E-11 | **TTS pipeline** | Query → answer → synthesize speech via sidecar `/tts` | WAV audio returned |
| E2E-12 | **Batch import** | Import 10 sources concurrently, verify all complete | No deadlocks, all Jobs finish |

---

## 7. Test Infrastructure

### 7.1 Java Test Dependencies

```kotlin
// build.gradle.kts — already declared
testImplementation("org.springframework.boot:spring-boot-starter-test")
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:postgresql")
testImplementation("org.testcontainers:chromadb")
testImplementation("com.github.tomakehurst:wiremock-standalone:3.13.0")

// Added for integration tests
testImplementation("org.springframework.ai:spring-ai-test")
testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
testImplementation("org.springframework.ai:spring-ai-transformers")  // ONNX local embeddings
```

All Spring AI deps managed by `spring-ai-bom:1.0.0`.

### 7.2 Embedding Strategy — ONNX (No Ollama)

Industry standard for Spring AI testing: use `spring-ai-transformers` with the `all-MiniLM-L6-v2` ONNX model. This produces **real embeddings** on CPU — similarity search actually works in tests without Ollama.

```java
@TestConfiguration
class TestEmbeddingConfig {
    @Bean
    TransformersEmbeddingModel embeddingModel() {
        return new TransformersEmbeddingModel();
        // Defaults to all-MiniLM-L6-v2, downloads + caches on first use
    }
}
```

### 7.3 Shared Test Configuration

```java
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    ChromaDBContainer chromadb(DynamicPropertyRegistry registry) {
        var container = new ChromaDBContainer("chromadb/chroma:latest");
        registry.add("spring.ai.vectorstore.chroma.url",
            () -> "http://" + container.getHost() + ":" + container.getMappedPort(8000));
        return container;
    }
}
```

### 7.4 Mocking Strategy

| Dependency | Strategy | Rationale |
|------------|----------|-----------|
| PostgreSQL | Testcontainers `@ServiceConnection` | Real DB, Flyway migrations, JPA |
| ChromaDB | Testcontainers + `@DynamicPropertySource` | Real vector store operations |
| EmbeddingModel | `TransformersEmbeddingModel` (ONNX) | Real embeddings, no Ollama |
| Ollama ChatModel | `@MockBean` | Not needed for ingest pipeline |
| ML Sidecar | WireMock | Mock `/transcribe`, `/tts`, `/health` |
| AudioService | `@MockBean` | ffmpeg/yt-dlp not available in CI |
| UrlResolver | `@MockBean` | Control test data, no external HTTP |

### 7.5 Python (pytest)

```toml
# pyproject.toml [project.optional-dependencies]
test = ["pytest", "pytest-asyncio", "httpx"]
```

---

## 8. CI/CD Considerations

| Concern | Approach |
|---------|----------|
| **Speed** | Testcontainers reused across tests in same JVM via `@ServiceConnection` |
| **Ollama** | Not used — ONNX `TransformersEmbeddingModel` replaces Ollama in tests |
| **ffmpeg/yt-dlp** | Not available in CI — `AudioService` mocked via `@MockBean` |
| **ML model tests** | Python Whisper/Piper tests gated behind `@pytest.mark.ml` — skipped in fast CI |
| **ONNX model cache** | `all-MiniLM-L6-v2` (~90 MB) downloaded on first test run, cached in `$TMPDIR/onnx-zoo` |
| **Docker** | Required for Testcontainers; ubuntu-latest CI runners have Docker pre-installed |

### Test Commands

```bash
# All API integration tests (Docker required, no Ollama/ffmpeg/yt-dlp needed)
cd api && ./gradlew test

# Python sidecar tests (no ML models)
cd ml-sidecar && uv run pytest --ignore=tests/ml

# Python ML tests only (requires Whisper model downloaded)
cd ml-sidecar && uv run pytest -m ml

# Full project (Makefile)
make test
```

---

## 9. Phase Mapping

Tests are implemented alongside the feature they verify:

| Phase | Label | Integration Tests | Linear |
|-------|-------|-------------------|--------|
| Phase 1 — Foundation | `phase-1-foundation` | Unit (4.0a–4.0e), DB CRUD (5.1), Embeddings/ChromaDB (5.4), Sidecar client (5.3), Sidecar API (5.5), Pipeline (5.8), E2E (6.1), Job lifecycle | APP-109 (PR #13) |
| Phase 2 — RAG | `phase-2-rag` | Ollama chat via WireMock, RAG query E2E (6.2) | APP-83 + tests |
| Phase 3 — UI | `phase-3-ui` | Frontend API contract tests | APP-75 + tests |
| Phase 4 — TTS | `phase-4-tts` | Piper TTS (5.7), TTS orchestration (5.3 #2) | Future |
| Phase 5 — Polish | `phase-5-polish` | Multi-source (E2E-9), Error recovery, Batch import (E2E-12) | Future |
