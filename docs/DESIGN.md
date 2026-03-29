# LocalLoom — Design Document

## 1. Overview

**LocalLoom** is a privacy-first, locally-running application that transforms podcast audio into a searchable, queryable knowledge base. Users import podcasts from major platforms (YouTube, Apple Podcasts, Spotify), the system transcribes and indexes the audio, and a local LLM answers questions about the content — delivering responses in both text and audio format.

Everything runs on the user's machine. No cloud APIs. No data leaves the device.

### Problem Statement

Podcast listeners accumulate hundreds of hours of valuable content but have no efficient way to search, reference, or query what they've heard. Traditional search requires remembering which episode discussed a topic. Manual note-taking doesn't scale.

### Solution

LocalLoom creates a personal podcast knowledge base by:
1. Downloading podcast audio from any major platform
2. Transcribing audio to timestamped text using local speech-to-text (Whisper)
3. Indexing transcripts into a vector database for semantic search
4. Answering natural language questions using RAG (Retrieval-Augmented Generation) with a local LLM
5. Optionally reading answers aloud via local text-to-speech

### Target User

- Podcast enthusiasts who want to search across their listening history
- Researchers who use podcasts as source material
- Content creators who want to reference or repurpose podcast content
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
│  └──────────────┘    └───────┬───────────┘    └───────┬───────┘ │
│                              │                        │         │
│                    ┌─────────┼────────┐        ┌──────┴──────┐  │
│                    │         │        │        │  ChromaDB    │  │
│                    ▼         ▼        ▼        │  (embedded)  │  │
│              ┌────────┐ ┌───────┐ ┌───────┐   └─────────────┘  │
│              │   DB    │ │ Ollama│ │ Files │                    │
│              │PostgreSQL│ │:11434│ │ Store │                    │
│              └────────┘ └───────┘ └───────┘                    │
└─────────────────────────────────────────────────────────────────┘
```

> **Note:** Spring Boot and Java versions shown are target versions for this project. Adjust to the latest stable release at implementation time.

### 2.2 Component Responsibilities

#### API Server (Java / Spring Boot 4.0)
The central orchestrator. Handles all user-facing requests, business logic, and coordinates between services.

| Responsibility | Details |
|---------------|---------|
| REST API | All endpoints consumed by the frontend |
| Podcast Import | URL resolution, RSS parsing, audio download |
| Job Management | Background task orchestration with progress tracking |
| LLM Communication | Spring AI `OllamaChatModel` for streaming inference |
| Embeddings | Spring AI `OllamaEmbeddingModel` for vector generation |
| Vector Search | Spring AI `ChromaDbVectorStore` for RAG retrieval |
| RAG Pipeline | Spring AI `RetrievalAugmentationAdvisor` for prompt augmentation |
| Text Chunking | Spring AI `TokenTextSplitter` for transcript chunking |
| Data Persistence | CRUD operations via Spring Data JPA |
| ML Orchestration | Delegates transcription and TTS to Python sidecar |
| File Serving | Serves audio files and TTS output to frontend |

**Why Java + Spring AI?** Spring AI brings native Ollama chat/embedding, ChromaDB vector store, RAG advisors, and document chunking — all auto-configured in Spring Boot. This eliminates the need for Python to handle embeddings, vector search, and chunking. Spring Boot 4.0's virtual threads handle concurrent I/O efficiently.

#### ML Sidecar (Python / FastAPI)
A lightweight internal service exposing ML capabilities. Not directly accessible from the frontend.

| Responsibility | Details |
|---------------|---------|
| Transcription | Whisper inference on audio files |
| TTS | Text-to-speech synthesis via Piper |

**Why Python?** Whisper and Piper TTS have no production-quality Java equivalents. The sidecar is minimal — just 2 endpoints. Embeddings, chunking, and vector search are handled by Spring AI in the Java layer.

**Note:** With Spring AI, the Python sidecar is significantly slimmer. It only handles tasks that require Python-native ML libraries (Whisper for speech-to-text, Piper for text-to-speech).

#### Frontend (Next.js 14 + TypeScript)
The user interface. Communicates exclusively with the Spring Boot API server.

| Responsibility | Details |
|---------------|---------|
| Podcast Management | Import, browse, delete podcasts and episodes |
| Transcript Viewer | Timestamped, searchable transcript display |
| Chat Interface | Question input, streaming answer display, source citations |
| Audio Playback | Play TTS responses |
| Settings | Model configuration, storage management |

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
┌──────────────┐     ┌──────────────────┐     ┌─────────────────────┐
│   Podcast     │     │     Episode       │     │  TranscriptSegment   │
├──────────────┤     ├──────────────────┤     ├─────────────────────┤
│ id (UUID)     │──┐  │ id (UUID)         │──┐  │ id (BIGINT)          │
│ title         │  └─▶│ podcast_id (FK)   │  └─▶│ episode_id (FK)      │
│ author        │     │ title             │     │ start_time (DOUBLE)  │
│ description   │     │ description       │     │ end_time (DOUBLE)    │
│ artwork_url   │     │ published_at      │     │ text (TEXT)          │
│ feed_url      │     │ audio_url         │     └─────────────────────┘
│ source_type   │     │ audio_path        │
│ source_url    │     │ duration_seconds  │     ┌─────────────────────┐
│ created_at    │     │ status            │     │       Job            │
└──────────────┘     │ transcript_text   │     ├─────────────────────┤
                      │ created_at        │     │ id (UUID)            │
                      └──────────────────┘     │ type (ENUM)          │
                                                │ entity_id (UUID)     │
┌──────────────────┐     ┌──────────────┐      │ status (ENUM)        │
│  Conversation     │     │   Message     │     │ progress (DOUBLE)    │
├──────────────────┤     ├──────────────┤      │ error_message        │
│ id (UUID)         │──┐  │ id (UUID)     │     │ created_at           │
│ title             │  └─▶│ conv_id (FK)  │     │ completed_at         │
│ created_at        │     │ role (ENUM)   │     └─────────────────────┘
│ updated_at        │     │ content       │
└──────────────────┘     │ sources (JSON)│
                          │ audio_path    │
                          │ created_at    │
                          └──────────────┘
```

### 3.2 Entity Details

**Podcast**
- `source_type`: `RSS`, `YOUTUBE` — determines download strategy
- `source_url`: original URL provided by user
- `feed_url`: resolved RSS feed URL (for Apple Podcasts, resolved via iTunes API)

**Episode**
- `status` lifecycle: `PENDING` → `DOWNLOADING` → `TRANSCRIBING` → `EMBEDDING` → `INDEXED` | `ERROR`
- `audio_path`: local file path after download (relative to data directory)
- `transcript_text`: full concatenated transcript for display

**TranscriptSegment**
- `start_time` / `end_time`: seconds from episode start (e.g., 125.4 = 2:05.4)
- Used for timestamp citations in RAG responses

**Job**
- `type`: `DOWNLOAD`, `TRANSCRIBE`, `EMBED`
- `progress`: 0.0 to 1.0, updated during processing
- Frontend polls job status for progress display

**Conversation / Message**
- `role`: `USER` or `ASSISTANT`
- `sources`: JSON array of citation objects `[{episode_id, episode_title, start_time, end_time, snippet}]`
- `audio_path`: path to TTS-generated audio file

### 3.3 Vector Store Schema (ChromaDB)

Each podcast gets its own ChromaDB collection: `podcast_{podcast_id}`

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | `{episode_id}_{chunk_index}` |
| `document` | string | Chunk text (~500 tokens) |
| `embedding` | float[] | Vector (dimension depends on Ollama embedding model, e.g. nomic-embed-text) |
| `metadata.episode_id` | string | Episode UUID |
| `metadata.podcast_id` | string | Podcast UUID |
| `metadata.episode_title` | string | For citation display |
| `metadata.start_time` | float | Chunk start timestamp |
| `metadata.end_time` | float | Chunk end timestamp |
| `metadata.chunk_index` | int | Position in episode |

---

## 4. Data Flow

### 4.1 Podcast Import Pipeline

```
User submits URL
       │
       ▼
┌─────────────────────┐
│ 1. URL Resolution    │  Spring Boot
│    (Java)            │
│                      │
│  YouTube URL?        │──▶ yt-dlp metadata extraction
│  Apple Podcasts URL? │──▶ iTunes Lookup API → RSS feed URL
│  Spotify URL?        │──▶ oEmbed → podcast name → iTunes search → RSS
│  Raw RSS URL?        │──▶ Direct parse
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│ 2. Metadata Extract  │  Spring Boot
│                      │
│  Parse RSS feed      │
│  Extract episodes:   │
│  title, date, desc,  │
│  audio enclosure URL │
│  Save to PostgreSQL  │
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│ 3. Audio Download    │  Spring Boot (background job)
│                      │
│  YouTube: yt-dlp     │
│  RSS: HTTP download  │
│  Convert to 16kHz    │
│  mono WAV (ffmpeg)   │
│  Save to data/audio/ │
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│ 4. Transcription     │  Python Sidecar
│                      │
│  POST /transcribe    │
│  Whisper large-v3    │
│  Returns: [{start,   │
│  end, text}, ...]    │
│  ~5-15 min per hour  │
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│ 5. Chunk + Embed     │  Spring Boot (Spring AI)
│                      │
│  TokenTextSplitter   │
│  500-token chunks    │
│  (50 overlap)        │
│  OllamaEmbeddingModel│
│  → ChromaDbVectorStore│
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│ 6. Mark Indexed      │  Spring Boot
│                      │
│  Episode.status =    │
│  INDEXED             │
│  Job completed       │
└─────────────────────┘
```

### 4.2 RAG Query Pipeline

```
User asks: "What did they say about AI regulation?"
       │
       ▼
┌─────────────────────────┐
│ 1. Embed + Search        │  Spring Boot (Spring AI)
│    RetrievalAugmentation │
│    Advisor embeds query  │
│    and searches VectorStore│
└─────────┬───────────────┘
          ▼
┌─────────────────────────┐
│ 2. Vector Search         │  Spring AI (ChromaDbVectorStore)
│                          │
│  Similarity search       │
│  top_k = 5               │
│  Filter by podcast_id    │
│  Returns ranked chunks   │
│  with metadata           │
└─────────┬───────────────┘
          ▼
┌─────────────────────────┐
│ 3. Build RAG Prompt      │  Spring Boot
│                          │
│  System: "Answer using   │
│  ONLY provided context.  │
│  Cite episode + time."   │
│                          │
│  Context: [5 chunks      │
│  with source info]       │
│                          │
│  User: original question │
└─────────┬───────────────┘
          ▼
┌─────────────────────────┐
│ 4. LLM Inference        │  Spring Boot → Ollama
│                          │
│  POST /api/chat          │
│  stream: true            │
│  model: llama3.1:8b      │
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

#### Podcasts

| Method | Endpoint | Description | Request | Response |
|--------|----------|-------------|---------|----------|
| POST | `/podcasts/import` | Import podcast from URL | `{url: string}` | `{job_id, podcast_id}` |
| GET | `/podcasts` | List all podcasts | — | `[{id, title, author, artwork_url, episode_count, indexed_count}]` |
| GET | `/podcasts/{id}` | Podcast detail | — | `{id, title, author, description, episodes: [...]}` |
| DELETE | `/podcasts/{id}` | Delete podcast + all data | — | `204 No Content` |

#### Episodes

| Method | Endpoint | Description | Request | Response |
|--------|----------|-------------|---------|----------|
| GET | `/episodes/{id}` | Episode detail | — | `{id, title, status, duration, published_at}` |
| GET | `/episodes/{id}/transcript` | Full transcript | — | `{segments: [{start, end, text}]}` |
| POST | `/episodes/{id}/transcribe` | Re-transcribe | — | `{job_id}` |

#### Query (RAG)

| Method | Endpoint | Description | Request | Response |
|--------|----------|-------------|---------|----------|
| POST | `/query` | Ask a question | `{question, podcast_ids?, episode_ids?, conversation_id?}` | SSE stream |
| POST | `/query/{id}/tts` | Generate TTS for answer | — | `{job_id, audio_url}` |

**SSE Stream Events:**
```
event: token
data: {"content": "Based on"}

event: token
data: {"content": " the discussion"}

event: sources
data: {"sources": [{"episode_title": "...", "start_time": 125.4, "snippet": "..."}]}

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

Not exposed to frontend. Called only by Spring Boot.

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

| Model | Size | RAM | Use Case |
|-------|------|-----|----------|
| `llama3.1:8b-instruct-q5_K_M` | ~6 GB | 8 GB | Default LLM — good quality, fast on M1+ |
| `llama3.1:70b-instruct-q4_K_M` | ~40 GB | 48 GB | Premium LLM — for M2/M3/M4 Max/Ultra |
| `large-v3-turbo` (Whisper) | ~3 GB | 4 GB | Default transcription — near-best quality, 2-3x faster |
| `nomic-embed-text` | ~275 MB | 500 MB | Embeddings via Ollama — 768-dim, good quality |
| `en_US-amy-medium` (Piper) | 60 MB | 100 MB | TTS — natural voice, fast |

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

Podcast transcripts are split into overlapping chunks for embedding and retrieval:

- **Chunk size**: ~500 tokens (~375 words, ~2-3 minutes of speech)
- **Overlap**: 50 tokens — prevents losing context at boundaries
- **Boundary**: Split at sentence ends (`. `) after reaching 400 tokens
- **Metadata**: Each chunk carries `episode_id`, `podcast_id`, `start_time`, `end_time`, `chunk_index`

### RAG Prompt Template

```
System: You are a podcast research assistant for LocalLoom. Answer the
user's question using ONLY the provided podcast transcript excerpts.

Rules:
- Cite sources as [Episode: "title", timestamp]
- If the excerpts don't contain enough information, say so clearly
- Be concise and direct
- Do not make up information not present in the excerpts

Context:
---
[Source: "{episode_title}" at {start_time}-{end_time}]
{chunk_text}
---

User: {question}
```

---

## 8. Security Considerations

- **No external network calls** except: podcast download (user-initiated), Ollama (localhost), iTunes API (metadata lookup)
- **No authentication** required — single-user local application
- **File path validation** — sanitize all file paths to prevent directory traversal
- **Input validation** — validate URLs before processing (allowlist of supported domains)
- **Process isolation** — yt-dlp runs as subprocess with limited permissions
- **No secrets** — no API keys required for core functionality

---

## 9. Error Handling

| Scenario | Handling |
|----------|---------|
| Ollama not running | Health check on startup, clear error in UI with install instructions |
| Python sidecar unreachable | Retry with backoff, degrade gracefully (disable ML features) |
| Transcription fails | Mark job as ERROR, allow retry, preserve downloaded audio |
| Audio download fails | Retry up to 3 times, mark episode as ERROR with message |
| Unsupported URL | Return validation error with list of supported formats |
| Disk space low | Check before download/transcription, warn user |
| Model not found | Prompt user to pull model via settings UI |

---

## 10. Future Considerations

Not in scope for initial implementation, but architecturally accommodated:

- **Multi-language support**: Whisper supports 99 languages; UI and prompts need i18n
- **Speaker diarization**: Identify different speakers in podcast (whisperX or pyannote)
- **Semantic search UI**: Search across all transcripts without asking a question
- **Podcast RSS subscription**: Auto-download new episodes on a schedule
- **Mobile companion app**: API server already supports remote clients
- **Fine-tuning**: Use transcripts to fine-tune a small model on specific podcast content
- **Plugin system**: Custom post-processing pipelines (summarization, topic extraction)
