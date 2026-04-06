# Glossary

Disambiguates overloaded terms used across LocalLoom code, docs, and the data model. If you are about to invent a new term for any of these — don't. Use the one below.

---

### Source

A user-connected origin of knowledge. One RSS feed, one YouTube channel, one uploaded file, one web-page URL. Persisted as the `sources` table, represented by `com.localloom.model.Source`.

- Has a `SourceType` (see below).
- Has `sync_status` (`IDLE` / `SYNCING` / `ERROR`).
- Owns many `ContentUnit`s.

### SourceType

Enum in `com.localloom.model.SourceType`. Current values:

| Value | Meaning |
|---|---|
| `MEDIA` | RSS / podcast feed / direct audio URL |
| `YOUTUBE` | YouTube video or playlist |
| `WEB_PAGE` | Single web page |
| `FILE_UPLOAD` | Locally uploaded file |
| `TEAMS` | Microsoft Teams channel *(planned, opt-in)* |
| `GITHUB` | GitHub repository *(planned, opt-in)* |

Do not use legacy names like `PODCAST` or `CONFLUENCE` — those were superseded.

### ContentUnit

One addressable item inside a source: a single podcast episode, one YouTube video, one file, one web page. Persisted as the `content_units` table, represented by `com.localloom.model.ContentUnit`.

- Has a lifecycle status: `PENDING` → `FETCHING` → `TRANSCRIBING` | `EXTRACTING` → `EMBEDDING` → `INDEXED`, with an `ERROR` terminal state.
- Owns many `ContentFragment`s.

### ContentFragment

The structured output of extraction — a transcript segment, a web-page section, a paragraph, a code block. The natural unit of *the source's own structure*. Persisted as `content_fragments`, represented by `com.localloom.model.ContentFragment`.

- Has a `fragment_type`: `TIMED_SEGMENT`, `SECTION`, `MESSAGE`, `CODE_BLOCK`, `TEXT_BLOCK`.
- Has a `location` JSONB with type-specific fields (start/end time, heading, line numbers, page number).
- **Not the same as a chunk.** Fragments preserve the source's structure; chunks are what goes into the vector store.

### Chunk

The fixed-size (token-bounded) text unit actually embedded and stored in ChromaDB. Produced by Spring AI's `TokenTextSplitter` (configured in `SpringAiConfig.java`: 500 tokens, min-chunk-chars 50).

- One `ContentFragment` may become 1..N chunks depending on length.
- Each chunk becomes one Spring AI `Document` with metadata.
- **Not persisted in Postgres.** The source of truth for chunks is Chroma.

### Document (Spring AI)

`org.springframework.ai.document.Document`. Spring AI's transport type: a text body + metadata map. LocalLoom uses it purely as the wire format for writing chunks into Chroma and reading them back out of the `RetrievalAugmentationAdvisor`.

- Metadata carries: `source_id`, `source_type`, `content_unit_id`, `content_unit_title`, `content_type`, `location`, `chunk_index`.
- `RagService.extractCitations()` reads these fields to build `Citation` DTOs.

### Citation

The user-visible pointer from an answer back to a source. DTO: `com.localloom.service.dto.Citation(sourceType, contentUnitTitle, location, sourceId, contentUnitId)`.

- Built post-response from the Spring AI `Document`s that the advisor retrieved.
- Deduped (`.distinct()`) before being returned to the client.
- Rendered in the frontend chat UI via a separate `event: sources` SSE frame.

### Conversation

A threaded chat session. Persisted as `conversations` + `messages` tables, represented by `com.localloom.model.Conversation` and `Message`.

- A `Conversation` has many `Message`s with `role` = `USER` or `ASSISTANT`.
- Passing `conversationId` on a `POST /api/v1/query` call activates the `CompressionQueryTransformer` — the new user question is rewritten against prior turns before retrieval.

### Message

One turn in a `Conversation`. Has `role`, `content`, and (for assistant messages) a JSON citations column.

> Not to be confused with Spring AI's `org.springframework.ai.chat.messages.Message` — that's an in-memory type used to pass history to the `ChatClient`. Our persisted `Message` is the durable record; the Spring AI `Message` is the per-request representation.

### Job

An asynchronous background unit of work — typically an import or reprocess. Persisted as `jobs`, represented by `com.localloom.model.Job`. Tracks state for long-running operations so the UI can poll status.

### Connector

A Spring bean implementing `com.localloom.connector.SourceConnector`. One per `SourceType` (where implemented). Responsible for the full import pipeline (`importSource(source, jobId, maxItems)`) — discovery, fetch, extract, and handing off to `EmbeddingService` for chunking + embedding.

### Sidecar

The Python FastAPI process (`ml-sidecar/`) that handles the two things Spring AI cannot: Whisper transcription and Piper TTS. Everything else — embeddings, vector store, RAG, chunking, text extraction — lives in the Java API.
