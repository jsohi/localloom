# LocalLoom — Integration Testing Plan

## 1. Overview

This document defines the integration testing strategy for LocalLoom. Integration tests verify that components work together correctly — API to database, API to sidecar, API to Ollama, and full end-to-end user flows.

All integration tests use **sample data** shipped in the repository under `api/src/test/resources/fixtures/` and `ml-sidecar/tests/fixtures/`. No external network calls are required to run the test suite.

> **Diagrams**: See [DIAGRAMS.md](./DIAGRAMS.md) for architecture context. See [DESIGN.md](./DESIGN.md) for data model and API details.

---

## 2. Test Layers

| Layer | Scope | Tools | Runs Against |
|-------|-------|-------|-------------|
| **Unit** | Single class/function | JUnit 5, pytest | Mocks only |
| **Integration** | Multi-component flows | Spring Boot Test, Testcontainers, pytest | Real DB, real ChromaDB |
| **End-to-End** | Full stack user scenarios | Spring Boot Test + WireMock + Testcontainers | All services running |

This document focuses on **Integration** and **End-to-End** tests.

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

## 4. Integration Test Scenarios

### 4.1 API Server — Database (Spring Boot + PostgreSQL)

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

### 4.2 API Server — ChromaDB (Spring AI + Vector Store)

**Infrastructure**: Testcontainers (ChromaDB)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Store and retrieve embeddings | Embed sample text, search by similarity | Spring AI ChromaDB integration |
| 2 | Metadata filtering | Store chunks with source_type metadata, filter on search | Metadata filter queries |
| 3 | Collection per source | Create two sources, verify separate collections | Collection isolation |
| 4 | Delete source vectors | Delete all vectors for a source, verify empty | Cleanup logic |
| 5 | Chunk indexing with TokenTextSplitter | Split long text, embed, verify chunk count and overlap | Chunking config |

### 4.3 API Server — ML Sidecar (Spring Boot + Python)

**Infrastructure**: WireMock (mock sidecar responses)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Transcription request | POST audio to `/transcribe`, verify segment parsing | MlSidecarClient |
| 2 | TTS request | POST text to `/tts`, verify WAV response handling | TTS flow |
| 3 | Sidecar health check | GET `/health`, verify status parsing | Health monitoring |
| 4 | Sidecar unavailable | Sidecar down, verify graceful degradation | Error handling |
| 5 | Transcription timeout | Slow response, verify timeout and retry | Resilience |

### 4.4 API Server — Ollama (Spring AI + OllamaChatModel)

**Infrastructure**: WireMock (mock Ollama API)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Streaming chat response | POST `/api/chat`, verify SSE token stream | ChatClient streaming |
| 2 | Model listing | GET `/api/tags`, verify model list parsing | Model management |
| 3 | Ollama unavailable | Connection refused, verify error response | Error handling |
| 4 | Embedding request | Embed text via OllamaEmbeddingModel, verify vector dimensions | Embedding integration |

### 4.5 ML Sidecar — Whisper (Python Integration)

**Infrastructure**: Real Whisper model (requires model download, CI-gated)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Transcribe 10s clip | POST `short-clip-10s.wav`, verify segments returned | Whisper pipeline |
| 2 | Transcribe silence | POST `silence-5s.wav`, verify empty/minimal segments | Edge case |
| 3 | Invalid audio format | POST non-audio file, verify 422 error | Input validation |
| 4 | Model lazy loading | First request loads model, second is faster | Lazy init |

### 4.6 ML Sidecar — Piper TTS (Python Integration)

**Infrastructure**: Real Piper model (requires model download, CI-gated)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Generate speech | POST short text, verify WAV response | TTS pipeline |
| 2 | Long text handling | POST 500-word text, verify sentence splitting + concatenation | Long input |
| 3 | Empty text | POST empty string, verify 422 error | Input validation |

### 4.7 Source Connector — Podcast

**Infrastructure**: WireMock (mock RSS feeds + audio URLs), Testcontainers (PostgreSQL, ChromaDB)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Discover episodes from RSS | Parse `sample-rss-feed.xml`, verify 3 ContentUnits created | RSS parsing |
| 2 | Empty RSS feed | Parse `sample-rss-feed-empty.xml`, verify 0 ContentUnits | Edge case |
| 3 | Malformed RSS feed | Parse `sample-rss-feed-malformed.xml`, verify error handling | Error handling |
| 4 | Fetch audio | Download audio from WireMock URL, verify WAV conversion | Audio download |
| 5 | Full podcast pipeline | RSS → discover → fetch → transcribe → embed | End-to-end connector |

### 4.8 Source Connector — File Upload

**Infrastructure**: Testcontainers (PostgreSQL, ChromaDB)

| # | Test | Description | Verifies |
|---|------|-------------|----------|
| 1 | Upload markdown file | POST multipart, verify Source + ContentUnit created | Upload flow |
| 2 | Upload PDF file | POST PDF, verify text extraction | PDF handling |
| 3 | Upload unsupported type | POST `.exe`, verify rejection | Validation |
| 4 | Full upload pipeline | Upload → extract → embed → query | End-to-end connector |

---

## 5. End-to-End Test Scenarios

These tests exercise the full stack from API request to database + vector store, using real (containerized) dependencies.

**Infrastructure**: Testcontainers (PostgreSQL, ChromaDB), WireMock (Ollama, sidecar, external feeds)

| # | Scenario | Flow | Key Assertions |
|---|----------|------|-----------------|
| E2E-1 | **Podcast import → query** | Add podcast URL → discover episodes → fetch audio → transcribe → embed → ask question → get answer with citations | Answer contains citation with episode title and timestamp |
| E2E-2 | **File upload → query** | Upload markdown file → extract → embed → ask question → get answer | Answer cites uploaded file with section |
| E2E-3 | **Multi-source query** | Import podcast + upload file → query across both → verify citations from both source types | Citations include both PODCAST and FILE_UPLOAD types |
| E2E-4 | **Conversation continuity** | Ask question → get answer → ask follow-up in same conversation → verify context maintained | Second answer references conversation history |
| E2E-5 | **Source deletion cleanup** | Import podcast → embed → delete source → verify DB + ChromaDB cleaned up | No orphaned records or vectors |
| E2E-6 | **Job progress tracking** | Start podcast import → poll job status → verify progress updates 0.0 → 1.0 | Progress monotonically increases |
| E2E-7 | **Error recovery** | Start import → sidecar fails mid-transcription → verify job marked ERROR → retry succeeds | Retry creates new job, reaches INDEXED |

---

## 6. Test Infrastructure

### 6.1 Java (Spring Boot Test)

```kotlin
// build.gradle.kts
testImplementation("org.springframework.boot:spring-boot-starter-test")
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:postgresql")
testImplementation("org.testcontainers:chromadb")
testImplementation("org.wiremock:wiremock-standalone:3.x")
```

Base test class:

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
abstract class IntegrationTestBase {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static ChromaDBContainer chromadb = new ChromaDBContainer("chromadb/chroma:latest");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.ai.vectorstore.chroma.url",
            () -> "http://" + chromadb.getHost() + ":" + chromadb.getMappedPort(8000));
    }
}
```

### 6.2 Python (pytest)

```toml
# pyproject.toml [project.optional-dependencies]
test = ["pytest", "pytest-asyncio", "httpx"]
```

### 6.3 WireMock Stubs

WireMock stubs are stored alongside fixtures:

```
api/src/test/resources/wiremock/
├── ollama/
│   ├── chat-streaming.json       # Streaming chat response
│   └── tags.json                 # Model list
├── sidecar/
│   ├── transcribe-success.json   # Whisper response
│   ├── tts-success.json          # TTS response
│   └── health.json               # Health check
└── feeds/
    └── audio-download.json       # Serves short-clip-10s.wav
```

---

## 7. CI/CD Considerations

| Concern | Approach |
|---------|----------|
| **Speed** | Integration tests use Testcontainers with reusable containers where possible |
| **ML model tests** | Whisper/Piper tests are gated behind a `@Tag("ml")` / `@pytest.mark.ml` marker — skipped in fast CI, run in nightly |
| **Ollama tests** | All Ollama interactions are mocked via WireMock in CI; optional `@Tag("ollama")` tests hit real Ollama locally |
| **Sample audio size** | 10-second clip (~160 KB) is small enough for git; larger test audio is downloaded on demand |
| **Parallelism** | Each test class gets its own Testcontainers instance to avoid cross-test pollution |

### Test Commands

```bash
# All integration tests (no ML models required)
cd api && ./gradlew test

# Include ML model tests (requires Whisper + Piper downloaded)
cd api && ./gradlew test -Dinclude.tags=ml

# Python sidecar tests
cd ml-sidecar && uv run pytest

# Python ML tests only (requires models)
cd ml-sidecar && uv run pytest -m ml
```

---

## 8. Phase Mapping

Tests are implemented alongside the feature they verify:

| Phase | Label | Integration Tests |
|-------|-------|-------------------|
| Phase 1 — Foundation | `phase-1-foundation` | DB CRUD (4.1), Sidecar client (4.3), Whisper (4.5), Podcast connector (4.7) |
| Phase 2 — RAG | `phase-2-rag` | ChromaDB (4.2), Ollama (4.4), E2E query (E2E-1, E2E-2) |
| Phase 3 — UI | `phase-3-ui` | Frontend API contract tests (not covered here — see frontend test plan) |
| Phase 4 — TTS | `phase-4-tts` | Piper TTS (4.6), TTS orchestration (4.3 #2) |
| Phase 5 — Polish | `phase-5-polish` | Multi-source (E2E-3), Error recovery (E2E-7), Batch import |
