# LocalLoom — Design Document

## 1. Overview

**LocalLoom** is a privacy-first, locally-running application that transforms multiple knowledge sources into a unified, searchable, queryable knowledge base. Users connect sources — podcasts, Confluence pages, Microsoft Teams messages, GitHub repositories, and file uploads — and the system ingests, indexes, and semantically links the content. A local LLM answers questions across all sources, delivering responses in both text and audio format.

Everything runs on the user's machine. No cloud APIs unless the user explicitly configures an external connector.

### Problem Statement

Knowledge workers accumulate critical information across disconnected tools — team wikis, chat threads, codebases, podcast episodes, shared documents. Searching for an answer means context-switching between five or more applications, each with its own search silo. Manual cross-referencing doesn't scale.

### Solution

LocalLoom creates a unified personal knowledge base by:
1. Connecting to diverse sources: podcast feeds, Confluence spaces, MS Teams channels, GitHub repos, and local file uploads
2. Extracting content — transcribing audio via Whisper, pulling pages, threads, and code
3. Indexing all content into a vector database for semantic search
4. Answering natural language questions using RAG (Retrieval-Augmented Generation) with a local LLM, citing across source types
5. Optionally reading answers aloud via local text-to-speech

### Target User

- Podcast enthusiasts who want to search across their listening history
- Researchers who use podcasts and written sources as material
- Teams who need to search across wikis, chat, and code in one place
- Developers who want to query codebases alongside documentation
- Knowledge workers who aggregate information from many tools
- Content creators who want to reference or repurpose content
- Anyone who values data privacy and local-first tools

> **Diagrams**: See [DIAGRAMS.md](./DIAGRAMS.md) for interactive Mermaid diagrams of architecture, data flows, ER model, and service communication.

---

## 2. Architecture

### 2.1 System Architecture

LocalLoom uses a **polyglot microservice architecture** with three main components:

```
┌─────────────────────────────────────────────────────────────────┐
│                        User's Machine                           │
│                                                                 │
│  ┌──────────────┐    ┌──────────────────┐    ┌───────────────┐ │
│  │   Frontend    │───▶│   API Server      │───▶│  ML Sidecar    │ │
│  │  Next.js 14   │    │  Spring Boot 4.0  │    │  FastAPI       │ │
│  │  :3000        │◀───│  :8080            │◀───│  :8100         │ │
│  └──────────────┘    │                    │    │  (audio only)  │ │
│                       │ ConnectorRegistry  │    └───────┬───────┘ │
│                       │ SourceConnectors   │            │         │
│                       └───────┬───────────┘    ┌──────┴──────┐  │
│                               │                │  ChromaDB    │  │
│                     ┌─────────┼────────┐       │  (embedded)  │  │
│                     ▼         ▼        ▼       └─────────────┘  │
│               ┌────────┐ ┌───────┐ ┌───────┐                    │
│               │   DB    │ │ Ollama│ │ Files │                    │
│               │PostgreSQL│ │:11434│ │ Store │                    │
│               └────────┘ └───────┘ └───────┘                    │
└─────────────────────────────────────────────────────────────────┘
```

> **Note:** Spring Boot and Java versions shown are target versions for this project. Adjust to the latest stable release at implementation time.

### 2.2 Component Responsibilities

#### API Server (Java / Spring Boot 4.0)
The central orchestrator. Handles all user-facing requests, business logic, and coordinates between services.

| Responsibility | Details |
|---------------|---------|
| REST API | All endpoints consumed by the frontend |
| Source Connector Orchestration | Discover, fetch, and extract content from all source types |
| Connector Registry | Auto-discovers enabled `SourceConnector` beans, routes operations by type |
| Job Management | Background task orchestration with progress tracking |
| LLM Communication | Spring AI `OllamaChatModel` for streaming inference |
| Embeddings | Spring AI `OllamaEmbeddingModel` for vector generation |
| Vector Search | Spring AI `ChromaDbVectorStore` for RAG retrieval |
| RAG Pipeline | Spring AI `RetrievalAugmentationAdvisor` for prompt augmentation |
| Text Chunking | Spring AI `TokenTextSplitter` for content chunking |
| Data Persistence | CRUD operations via Spring Data JPA |
| ML Orchestration | Delegates transcription and TTS to Python sidecar |
| File Serving | Serves audio files and TTS output to frontend |

**Why Java + Spring AI?** Spring AI brings native Ollama chat/embedding, ChromaDB vector store, RAG advisors, and document chunking — all auto-configured in Spring Boot. This eliminates the need for Python to handle embeddings, vector search, and chunking. Spring Boot 4.0's virtual threads handle concurrent I/O efficiently.

#### ML Sidecar (Python / FastAPI)
A lightweight internal service exposing ML capabilities for audio sources only. Not directly accessible from the frontend.

| Responsibility | Details |
|---------------|---------|
| Transcription | Whisper inference on audio files |
| TTS | Text-to-speech synthesis via Piper |

**Why Python?** Whisper and Piper TTS have no production-quality Java equivalents. The sidecar is minimal — just 2 endpoints. Embeddings, chunking, and vector search are handled by Spring AI in the Java layer.

**Note:** The Python sidecar is only needed for audio-based sources (podcasts). Text-based sources (Confluence, Teams, GitHub, file uploads) are handled entirely in the Java layer. With Spring AI, the sidecar only handles tasks that require Python-native ML libraries (Whisper for speech-to-text, Piper for text-to-speech).

#### Frontend (Next.js 14 + TypeScript)
The user interface. Communicates exclusively with the Spring Boot API server.

| Responsibility | Details |
|---------------|---------|
| Source Management | Connect, browse, sync, and delete sources of all types |
| Content Viewer | View transcripts, pages, threads, code — with navigation |
| Chat Interface | Question input, streaming answer display, polymorphic citations |
| Audio Playback | Play TTS responses |
| Settings | Model configuration, connector toggles, storage management |

### 2.3 Why This Architecture?

**Why not Python-only?**
While Python is the default for AI projects, the orchestration layer (HTTP routing, job scheduling, database management, file serving) benefits from Java's type safety, threading model, and mature ecosystem. Spring Boot 4.0's virtual threads handle thousands of concurrent I/O operations efficiently.

**Why not Java-only?**
The ML libraries (Whisper, Piper) don't have production-quality Java equivalents. Calling them via a Python sidecar is the industry-standard pattern (used by LinkedIn, Netflix, Uber for ML features).

**Why not a monolith?**
The sidecar pattern allows independent scaling and deployment. The Python sidecar can be restarted (e.g., after a model change) without affecting the API server. Each service can be developed and tested independently.

---

## 3. Data Model

### 3.1 Entity Relationship Diagram

```
┌──────────────────┐     ┌──────────────────────┐     ┌─────────────────────┐
│      Source       │     │     ContentUnit       │     │   ContentFragment    │
├──────────────────┤     ├──────────────────────┤     ├─────────────────────┤
│ id (UUID)         │──┐  │ id (UUID)             │──┐  │ id (BIGINT)          │
│ name              │  └─▶│ source_id (FK)        │  └─▶│ content_unit_id (FK) │
│ description       │     │ title                 │     │ fragment_type (ENUM) │
│ source_type (ENUM)│     │ content_type (ENUM)   │     │ sequence_index (INT) │
│ origin_url        │     │ external_id           │     │ text (TEXT)          │
│ icon_url          │     │ external_url          │     │ location (JSONB)     │
│ config (JSONB)    │     │ status (ENUM)         │     └─────────────────────┘
│ sync_status (ENUM)│     │ raw_text (TEXT)        │
│ last_synced_at    │     │ metadata (JSONB)       │     ┌─────────────────────┐
│ created_at        │     │ published_at           │     │       Job            │
└──────────────────┘     │ created_at             │     ├─────────────────────┤
                          └──────────────────────┘     │ id (UUID)            │
                                                        │ type (ENUM)          │
┌──────────────────┐     ┌──────────────────┐          │ entity_id (UUID)     │
│  Conversation     │     │   Message         │         │ status (ENUM)        │
├──────────────────┤     ├──────────────────┤          │ progress (DOUBLE)    │
│ id (UUID)         │──┐  │ id (UUID)         │         │ error_message        │
│ title             │  └─▶│ conv_id (FK)      │         │ created_at           │
│ created_at        │     │ role (ENUM)       │         │ completed_at         │
│ updated_at        │     │ content           │         └─────────────────────┘
└──────────────────┘     │ sources (JSONB)   │
                          │ audio_path        │
                          │ created_at        │
                          └──────────────────┘
```

### 3.2 Entity Details

**Source**
- `source_type`: `PODCAST`, `CONFLUENCE`, `TEAMS`, `GITHUB`, `FILE_UPLOAD`
- `origin_url`: original URL or path provided by user (RSS feed, Confluence space URL, Teams channel URL, GitHub repo URL, or null for uploads)
- `icon_url`: visual icon for the source (artwork, favicon, etc.)
- `config` (JSONB): connector-specific configuration — credentials, filters, sync preferences
- `sync_status`: `IDLE`, `SYNCING`, `ERROR`
- `last_synced_at`: timestamp of last successful sync

Example `config` values per source type:
```json
// PODCAST
{"feed_url": "https://feeds.example.com/pod.xml", "source_url": "https://youtube.com/...", "download_format": "audio"}

// CONFLUENCE
{"base_url": "https://myteam.atlassian.net", "space_key": "ENG", "api_token": "encrypted:...", "include_labels": ["architecture"]}

// TEAMS
{"tenant_id": "...", "channel_id": "...", "access_token": "encrypted:...", "since_days": 90}

// GITHUB
{"repo_url": "https://github.com/org/repo", "branch": "main", "include_patterns": ["*.md", "*.java"], "access_token": "encrypted:..."}

// FILE_UPLOAD
{"upload_dir": "data/uploads/src_abc123"}
```

**ContentUnit**
- `content_type`: `AUDIO`, `PAGE`, `MESSAGE_THREAD`, `CODE_FILE`, `TEXT_FILE`
- `external_id`: identifier from the external system (episode GUID, page ID, thread ID, file path)
- `external_url`: link back to the original content in its source system
- `status` lifecycle varies by content type (see Section 4)
- `raw_text`: full extracted text for display
- `metadata` (JSONB): type-specific data

Example `metadata` values per content type:
```json
// AUDIO (podcast episode)
{"duration_seconds": 3621, "audio_url": "https://...", "audio_path": "data/audio/ep123.wav", "author": "Host Name"}

// PAGE (Confluence)
{"space_key": "ENG", "page_version": 12, "labels": ["api", "design"], "last_modified_by": "jane.doe"}

// MESSAGE_THREAD (Teams)
{"channel_name": "general", "participants": ["alice", "bob"], "message_count": 14, "thread_started_by": "alice"}

// CODE_FILE (GitHub)
{"file_path": "src/main/java/App.java", "language": "java", "sha": "abc123", "size_bytes": 4200}

// TEXT_FILE (upload)
{"original_filename": "notes.pdf", "mime_type": "application/pdf", "size_bytes": 102400}
```

**ContentFragment**
- `fragment_type`: `TIMED_SEGMENT`, `SECTION`, `MESSAGE`, `CODE_BLOCK`, `TEXT_BLOCK`
- `sequence_index`: ordering within the content unit
- `location` (JSONB): type-specific location within the content

Example `location` values per fragment type:
```json
// TIMED_SEGMENT (podcast transcript)
{"start_time": 125.4, "end_time": 155.2}

// SECTION (Confluence page)
{"heading": "API Design", "heading_level": 2, "anchor": "api-design"}

// MESSAGE (Teams thread)
{"author": "alice", "sent_at": "2025-12-01T10:30:00Z", "message_id": "msg_123"}

// CODE_BLOCK (GitHub file)
{"start_line": 10, "end_line": 45, "function_name": "processRequest"}

// TEXT_BLOCK (uploaded file)
{"page_number": 3, "paragraph_index": 2}
```

**Job**
- `type`: `FETCH`, `TRANSCRIBE`, `EXTRACT`, `EMBED`, `SYNC`
- `progress`: 0.0 to 1.0, updated during processing
- Frontend polls job status for progress display

**Conversation / Message**
- `role`: `USER` or `ASSISTANT`
- `sources` (JSONB): polymorphic citation array with `source_type` discriminator:
```json
[
  {"source_type": "PODCAST", "content_unit_id": "...", "title": "Episode 42", "start_time": 125.4, "end_time": 155.2, "snippet": "..."},
  {"source_type": "CONFLUENCE", "content_unit_id": "...", "title": "API Design", "section": "Authentication", "snippet": "..."},
  {"source_type": "GITHUB", "content_unit_id": "...", "title": "App.java", "file_path": "src/main/java/App.java", "start_line": 10, "end_line": 45, "snippet": "..."}
]
```
- `audio_path`: path to TTS-generated audio file

### 3.3 Vector Store Schema (ChromaDB)

Each source gets its own ChromaDB collection: `source_{source_id}`

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | `{content_unit_id}_{chunk_index}` |
| `document` | string | Chunk text (~500 tokens) |
| `embedding` | float[] | Vector (dimension depends on Ollama embedding model, e.g. nomic-embed-text) |
| `metadata.source_id` | string | Source UUID |
| `metadata.source_type` | string | `PODCAST`, `CONFLUENCE`, etc. |
| `metadata.content_unit_id` | string | ContentUnit UUID |
| `metadata.content_type` | string | `AUDIO`, `PAGE`, etc. |
| `metadata.content_unit_title` | string | For citation display |
| `metadata.location` | string | JSON-encoded location (timestamp, heading, line range, etc.) |
| `metadata.chunk_index` | int | Position in content unit |

---

## 4. Data Flow

### 4.1 Content Ingestion Pipeline

All source types follow a generic four-stage pipeline. Stage 3 varies depending on content type.

```
User connects source or uploads file
       │
       ▼
┌─────────────────────┐
│ 1. Discover          │  Spring Boot (SourceConnector)
│                      │
│  Connector queries   │
│  external system:    │
│  RSS feed episodes,  │
│  Confluence pages,   │
│  Teams threads,      │
│  GitHub file tree,   │
│  uploaded file list  │
│                      │
│  Creates ContentUnit │
│  records (PENDING)   │
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│ 2. Fetch             │  Spring Boot (background job)
│                      │
│  Podcast: download   │
│  audio via yt-dlp/   │
│  HTTP, convert to    │
│  16kHz mono WAV      │
│                      │
│  Confluence: REST    │
│  API → page HTML     │
│                      │
│  Teams: Graph API    │
│  → thread messages   │
│                      │
│  GitHub: clone/pull  │
│  or API → raw files  │
│                      │
│  Upload: already     │
│  local (skip)        │
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│ 3. Extract           │  Varies by content type
│                      │
│  Audio → Python      │
│  Sidecar (Whisper)   │
│  → timed segments    │
│                      │
│  HTML → strip tags,  │
│  split by headings   │
│  → sections          │
│                      │
│  Threads → group     │
│  messages by thread  │
│  → messages          │
│                      │
│  Code → parse by     │
│  function/class      │
│  → code blocks       │
│                      │
│  Text → extract      │
│  text (PDF/docx/md)  │
│  → text blocks       │
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│ 4. Embed + Index     │  Spring Boot (Spring AI)
│                      │
│  TokenTextSplitter   │
│  (type-aware config) │
│  OllamaEmbeddingModel│
│  → ChromaDbVectorStore│
│                      │
│  Mark ContentUnit    │
│  status = INDEXED    │
│  Update Source       │
│  sync_status = IDLE  │
└─────────────────────┘
```

#### Status Lifecycle by Content Type

- **Audio** (podcast episodes): `PENDING` → `FETCHING` → `TRANSCRIBING` → `EMBEDDING` → `INDEXED`
- **Text** (Confluence, Teams, GitHub): `PENDING` → `FETCHING` → `EXTRACTING` → `EMBEDDING` → `INDEXED`
- **Upload** (file uploads): `PENDING` → `EXTRACTING` → `EMBEDDING` → `INDEXED`

Any stage can transition to `ERROR` with a message stored on the Job.

### 4.2 RAG Query Pipeline

```
User asks: "What did we decide about API authentication?"
       │
       ▼
┌─────────────────────────┐
│ 1. Embed + Search        │  Spring Boot (Spring AI)
│    RetrievalAugmentation │
│    Advisor embeds query  │
│    and searches VectorStore│
│    Filters by source_ids │
│    and/or source_types   │
└─────────┬───────────────┘
          ▼
┌─────────────────────────┐
│ 2. Vector Search         │  Spring AI (ChromaDbVectorStore)
│                          │
│  Similarity search       │
│  top_k = 5               │
│  Filter by source_id(s)  │
│  and/or source_type(s)   │
│  Returns ranked chunks   │
│  with metadata           │
└─────────┬───────────────┘
          ▼
┌─────────────────────────┐
│ 3. Build RAG Prompt      │  Spring Boot
│                          │
│  System: "Answer using   │
│  ONLY provided context.  │
│  Cite source + location."│
│                          │
│  Context: [5 chunks      │
│  with source info and    │
│  typed location data]    │
│                          │
│  User: original question │
└─────────┬───────────────┘
          ▼
┌─────────────────────────┐
│ 4. LLM Inference        │  Spring Boot → Ollama
│                          │
│  POST /api/chat          │
│  stream: true            │
│  model: llama4:scout     │
│                          │
│  Stream tokens via SSE   │
│  to frontend             │
└─────────┬───────────────┘
          ▼
┌─────────────────────────┐
│ 5. Save + Optional TTS   │  Spring Boot
│                          │
│  Save to conversation    │
│  history (PostgreSQL)    │
│  with polymorphic        │
│  citations               │
│                          │
│  If TTS requested:       │
│  POST /tts to sidecar    │
│  Serve audio to frontend │
└─────────────────────────┘
```

---

## 5. API Design

### 5.1 Public API (Spring Boot :8080)

All endpoints prefixed with `/api/v1/`.

#### Connectors

| Method | Endpoint | Description | Response |
|--------|----------|-------------|----------|
| GET | `/connectors` | List available connectors with status | `[{type, name, enabled, configured}]` |

**Example Response:**
```json
[
  {"type": "PODCAST", "name": "Podcast (RSS / YouTube / Apple / Spotify)", "enabled": true, "configured": true},
  {"type": "FILE_UPLOAD", "name": "File Upload", "enabled": true, "configured": true},
  {"type": "CONFLUENCE", "name": "Confluence", "enabled": false, "configured": false},
  {"type": "TEAMS", "name": "Microsoft Teams", "enabled": false, "configured": false},
  {"type": "GITHUB", "name": "GitHub", "enabled": false, "configured": false}
]
```

#### Sources

| Method | Endpoint | Description | Request | Response |
|--------|----------|-------------|---------|----------|
| POST | `/sources` | Create/connect a source | `{source_type, name?, origin_url?, config?}` | `{job_id, source_id}` |
| POST | `/sources/upload` | Upload a file as a source | `multipart/form-data {file, name?}` | `{job_id, source_id}` |
| GET | `/sources` | List all sources | — | `[{id, name, source_type, icon_url, content_unit_count, indexed_count, sync_status}]` |
| GET | `/sources/{id}` | Source detail | — | `{id, name, source_type, description, content_units: [...]}` |
| POST | `/sources/{id}/sync` | Trigger re-sync | — | `{job_id}` |
| DELETE | `/sources/{id}` | Delete source + all data | — | `204 No Content` |

#### Content Units

| Method | Endpoint | Description | Request | Response |
|--------|----------|-------------|---------|----------|
| GET | `/content-units/{id}` | Content unit detail | — | `{id, title, content_type, status, published_at}` |
| GET | `/content-units/{id}/content` | Full content (transcript, page, code) | — | `{fragments: [{type, sequence_index, text, location}]}` |
| POST | `/content-units/{id}/reprocess` | Re-extract and re-embed | — | `{job_id}` |

#### Query (RAG)

| Method | Endpoint | Description | Request | Response |
|--------|----------|-------------|---------|----------|
| POST | `/query` | Ask a question | `{question, source_ids?[], source_types?[], conversation_id?}` | SSE stream |
| POST | `/query/{id}/tts` | Generate TTS for answer | — | `{job_id, audio_url}` |

**SSE Stream Events:**
```
event: token
data: {"content": "Based on"}

event: token
data: {"content": " the discussion"}

event: sources
data: {"sources": [{"source_type": "PODCAST", "title": "Episode 42", "start_time": 125.4, "snippet": "..."}, {"source_type": "CONFLUENCE", "title": "API Design", "section": "Auth", "snippet": "..."}]}

event: done
data: {"message_id": "uuid"}
```

#### Conversations

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/conversations` | List conversations |
| GET | `/conversations/{id}` | Get messages in conversation |
| DELETE | `/conversations/{id}` | Delete conversation |

#### Jobs

| Method | Endpoint | Description | Response |
|--------|----------|-------------|----------|
| GET | `/jobs` | List active/recent jobs | `[{id, type, status, progress}]` |
| GET | `/jobs/{id}` | Job detail | `{id, type, status, progress, error_message}` |

#### Settings & Models

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/settings` | Current settings |
| PUT | `/settings` | Update settings |
| GET | `/models/llm` | List Ollama models |
| POST | `/models/llm/pull` | Pull new Ollama model |

### 5.2 Internal Sidecar API (Python :8100)

Not exposed to frontend. Called only by Spring Boot. Only used for audio source processing.

```
POST /transcribe
  Body: multipart/form-data {audio_file, model?: string}
  Response: {segments: [{start: float, end: float, text: string}], duration: float}

POST /tts
  Body: {text: string, voice?: string}
  Response: audio/wav binary

GET /health
  Response: {status: "ok", models_loaded: {...}}

Note: Embedding, chunking, and vector search are handled by Spring AI
in the Java layer — no longer routed through the Python sidecar.
```

---

## 6. Tech Stack Details

### 6.1 Version Matrix

| Component | Version | Purpose |
|-----------|---------|---------|
| **Java** | 25 | Latest JDK with virtual threads, pattern matching |
| **Spring Boot** | 4.0 | Web framework, DI, JPA, async |
| **Gradle** | 9.4.1 | Build tool |
| **Log4j2** | 2.25.3 | Structured logging (replaces Logback) |
| **Flyway** | Latest (Spring Boot managed) | Database migrations |
| **Spring Data JPA** | (Spring Boot managed) | ORM / repository layer |
| **Spring AI** | Latest (Spring Boot managed) | Ollama chat/embedding, RAG advisors, vector store, chunking |
| **spring-ai-ollama** | (Spring AI managed) | Ollama chat + embedding model integration |
| **spring-ai-chromadb** | (Spring AI managed) | ChromaDB vector store client |
| **Python** | 3.11+ | ML sidecar runtime (Whisper + TTS only) |
| **FastAPI** | 0.115+ | Sidecar REST framework |
| **faster-whisper** | Latest | Speech-to-text |
| **Piper TTS** | Latest | Text-to-speech |
| **Next.js** | 14+ | Frontend framework |
| **TypeScript** | 5.x | Frontend language |
| **Tailwind CSS** | 3.4+ | Styling |
| **shadcn/ui** | Latest | UI component library |
| **TanStack Query** | v5 | Server state management |
| **Ollama** | 0.5+ | Local LLM runtime |
| **PostgreSQL** | 16+ | Primary database |
| **ffmpeg** | Latest | Audio conversion |
| **yt-dlp** | Latest | YouTube audio extraction |

### 6.2 ML Model Recommendations

> **Recommended baseline:** 48 GB unified memory (e.g., Apple M-series). This comfortably runs `llama4:scout` alongside embeddings, ChromaDB, and the full stack.

| Model | Size | RAM | Use Case |
|-------|------|-----|----------|
| `llama4:scout` | ~17 GB | 24 GB | Default LLM — Llama 4 Scout, good quality, fast on 48 GB+ machines |
| `llama4:maverick` | ~50 GB | 64 GB | Premium LLM — Llama 4 Maverick, requires 64 GB+ RAM (M2/M3/M4 Max/Ultra) |
| `large-v3-turbo` (Whisper) | ~3 GB | 4 GB | Default transcription — near-best quality, 2-3x faster |
| `nomic-embed-text` | ~275 MB | 500 MB | Embeddings via Ollama — 768-dim, good quality |
| `en_US-amy-medium` (Piper) | 60 MB | 100 MB | TTS — natural voice, fast |

**Memory budget (48 GB system):**
| Component | Estimated RAM |
|-----------|--------------|
| `llama4:scout` | ~24 GB |
| `nomic-embed-text` | ~500 MB |
| Whisper `large-v3-turbo` | ~4 GB |
| PostgreSQL + ChromaDB | ~2 GB |
| Spring Boot + Python Sidecar | ~2 GB |
| OS + headroom | ~15.5 GB |

### 6.3 Log4j2 Configuration

Replace Spring Boot's default Logback with Log4j2 for:
- **Async appenders**: non-blocking logging for high-throughput operations
- **Structured JSON logging**: machine-parseable logs for debugging
- **Performance**: Log4j2's garbage-free logging reduces GC pressure

```xml
<!-- log4j2-spring.xml -->
<Configuration>
  <Appenders>
    <Console name="Console" target="SYSTEM_OUT">
      <PatternLayout pattern="%d{ISO8601} [%t] %-5level %logger{36} - %msg%n"/>
    </Console>
    <Async name="AsyncConsole">
      <AppenderRef ref="Console"/>
    </Async>
  </Appenders>
  <Loggers>
    <Logger name="com.localloom" level="DEBUG"/>
    <Logger name="org.springframework" level="INFO"/>
    <Root level="WARN">
      <AppenderRef ref="AsyncConsole"/>
    </Root>
  </Loggers>
</Configuration>
```

---

## 7. Chunking Strategy

### Text Chunking for RAG

Content is split into overlapping chunks for embedding and retrieval. The base strategy is shared, with per-source-type adjustments:

- **Chunk size**: ~500 tokens (~375 words)
- **Overlap**: 50 tokens — prevents losing context at boundaries
- **Boundary**: Split at sentence ends (`. `) after reaching 400 tokens
- **Metadata**: Each chunk carries `source_id`, `source_type`, `content_unit_id`, `content_type`, `location`, `chunk_index`

### Per-Type Chunking Notes

| Source Type | Strategy |
|-------------|----------|
| **Podcast (Audio)** | 500-token chunks at sentence boundaries (~2-3 minutes of speech). Unchanged from base strategy. |
| **Confluence (Pages)** | Chunk by heading sections. Each H2/H3 section becomes a fragment; long sections are further split at 500 tokens. |
| **GitHub (Code)** | Chunk by function/class boundaries using language-aware parsing. Whole functions kept intact where possible; large functions split at logical breakpoints. |
| **Teams (Messages)** | Group messages by thread. Chunk by conversation turns; each chunk includes enough context (author, timestamp) for citation. |
| **File Upload (Text)** | Standard 500-token split at sentence boundaries. PDF/docx converted to text first. |

### RAG Prompt Template

```
System: You are a knowledge assistant for LocalLoom. Answer the user's
question using ONLY the provided source excerpts.

Rules:
- Cite sources using the format matching each source type
- If the excerpts don't contain enough information, say so clearly
- Be concise and direct
- Do not make up information not present in the excerpts

Context:
---
[Source: "{content_unit_title}" — {source_type_label}, {location_label}]
{chunk_text}
---

User: {question}
```

---

## 8. Source Connector Configuration

### application.yml

```yaml
localloom:
  connectors:
    podcast:
      enabled: true    # enabled by default
    file-upload:
      enabled: true    # enabled by default
    confluence:
      enabled: false   # user opts in via Settings UI
    teams:
      enabled: false   # user opts in via Settings UI
    github:
      enabled: false   # user opts in via Settings UI
```

- **Podcast** and **File Upload** are enabled by default — they require no external credentials.
- **Confluence**, **Teams**, and **GitHub** are disabled by default. The user must explicitly enable them in the Settings UI and provide the required credentials (API tokens, tenant IDs, etc.).
- Each connector bean uses `@ConditionalOnProperty` so disabled connectors are never instantiated.
- The Settings UI exposes toggle switches for each connector alongside credential input fields. Credentials are stored in `Source.config` (JSONB) and encrypted at rest.

---

## 9. Source Connector Interface

### SourceConnector

Each source type is implemented as a Spring bean implementing the `SourceConnector` interface:

```java
public interface SourceConnector {
    SourceType sourceType();
    Source createSource(CreateSourceRequest request);
    List<ContentUnit> discoverContentUnits(Source source);
    void fetchContent(ContentUnit unit);
    List<ContentFragment> extractFragments(ContentUnit unit);
}
```

- `sourceType()` — returns the `SourceType` enum this connector handles
- `createSource()` — validates input, resolves metadata, persists a new `Source`
- `discoverContentUnits()` — queries the external system (RSS feed, Confluence API, GitHub tree, etc.) and creates `ContentUnit` records
- `fetchContent()` — downloads/retrieves raw content for a single content unit
- `extractFragments()` — parses raw content into `ContentFragment` records (transcript segments, page sections, code blocks, etc.)

### ConnectorRegistry

```java
@Component
public class ConnectorRegistry {
    private final Map<SourceType, SourceConnector> connectors;

    public ConnectorRegistry(List<SourceConnector> beans) {
        this.connectors = beans.stream()
            .collect(Collectors.toMap(SourceConnector::sourceType, Function.identity()));
    }

    public SourceConnector getConnector(SourceType type) { ... }
    public List<SourceType> getEnabledTypes() { ... }
}
```

The registry auto-discovers all enabled `SourceConnector` beans at startup. Each connector is annotated with `@ConditionalOnProperty(prefix = "localloom.connectors.<type>", name = "enabled")`, so only user-enabled connectors are registered.

---

## 10. Citation System

### Polymorphic Citations

Each source type has a distinct citation format, rendered by a `CitationFormatter` strategy:

```java
public interface CitationFormatter {
    SourceType sourceType();
    String formatCitation(ContentUnit unit, ContentFragment fragment);
}
```

#### Citation Formats

| Source Type | Citation Format | Example |
|-------------|----------------|---------|
| Podcast | `[Episode: "title", start-end]` | `[Episode: "AI Safety Deep Dive", 2:05-2:35]` |
| Confluence | `[Page: "title", section "heading"]` | `[Page: "API Design Guide", section "Authentication"]` |
| Teams | `[Thread: "title", message by author]` | `[Thread: "Deploy plan", message by alice]` |
| GitHub | `[File: "path", lines N-M]` | `[File: "src/main/java/App.java", lines 10-45]` |
| File Upload | `[File: "filename", page/section]` | `[File: "notes.pdf", page 3]` |

The frontend renders each citation type with an appropriate link or action (jump to timestamp, open page, navigate to file, etc.).

---

## 11. Security Considerations

- **No external network calls by default** except: Ollama (localhost). Podcast downloads and iTunes API lookups are user-initiated.
- **External connector API calls are user-configured** — the user explicitly enables connectors and provides credentials. No calls to Confluence, Teams, or GitHub unless the user opts in.
- **Connector credentials** — API tokens and access keys are stored in `Source.config` (JSONB), encrypted at rest. Credentials are never logged or exposed via the API.
- **No authentication** required — single-user local application
- **File path validation** — sanitize all file paths to prevent directory traversal
- **Input validation** — validate URLs before processing (allowlist of supported domains per connector)
- **Process isolation** — yt-dlp runs as subprocess with limited permissions
- **No secrets** — no API keys required for core functionality (podcasts + file uploads)

---

## 12. Error Handling

| Scenario | Handling |
|----------|---------|
| Ollama not running | Health check on startup, clear error in UI with install instructions |
| Python sidecar unreachable | Retry with backoff, degrade gracefully (disable audio features) |
| Transcription fails | Mark job as ERROR, allow retry, preserve downloaded audio |
| Audio download fails | Retry up to 3 times, mark content unit as ERROR with message |
| External API error (Confluence, Teams, GitHub) | Retry with backoff, mark source sync_status as ERROR, show details in UI |
| Connector credentials invalid | Mark source sync_status as ERROR, prompt user to update credentials |
| Unsupported URL | Return validation error with list of supported formats |
| Disk space low | Check before download/extraction, warn user |
| Model not found | Prompt user to pull model via settings UI |

---

## 13. Future Considerations

Not in scope for initial implementation, but architecturally accommodated:

- **Multi-language support**: Whisper supports 99 languages; UI and prompts need i18n
- **Speaker diarization**: Identify different speakers in podcast (whisperX or pyannote)
- **Semantic search UI**: Search across all content without asking a question
- **Scheduled sync for live sources**: Auto-sync Confluence, Teams, and GitHub on a cron schedule for near-real-time updates
- **Additional connectors**: Slack, Notion, Google Docs, JIRA — implemented via the `SourceConnector` interface
- **Mobile companion app**: API server already supports remote clients
- **Fine-tuning**: Use ingested content to fine-tune a small model on specific domain knowledge
