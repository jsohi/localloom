# LocalLoom

**Privacy-first, locally-running multi-source knowledge base with RAG-powered Q&A.**

LocalLoom ingests content from RSS / podcast feeds, YouTube videos, web pages, and local file uploads, indexes it into a local vector store, and lets you ask questions across everything using a local LLM — with citations back to the exact source and location. Audio answers via local text-to-speech are optional.

Everything runs on your machine. No cloud APIs, no telemetry, no per-token billing.

## Features

- **Multi-source ingest**: RSS / podcasts, YouTube, single web pages, local file uploads. Microsoft Teams and GitHub connectors are planned and opt-in.
- **Local transcription**: Whisper (`faster-whisper`) runs in a small Python sidecar.
- **Local embeddings + LLM**: Ollama powers both embeddings and chat.
- **RAG with citations**: Spring AI retrieves the most relevant chunks and the LLM answers with links back to the source title, section, or timestamp.
- **Streaming UI**: Next.js 14 chat interface with Server-Sent Events.
- **Optional TTS**: Piper synthesizes audio for any answer.
- **Local-first security**: optional API-key auth, SSRF validation, CORS lockdown, no outbound calls by default except Ollama and (first-run) model downloads.

See **[docs/DESIGN.md](docs/DESIGN.md)** for architecture details and **[docs/DIAGRAMS.md](docs/DIAGRAMS.md)** for system + data-flow diagrams.

## Architecture at a glance

```
┌─────────────┐    ┌─────────────────────────┐    ┌────────────────┐
│  Next.js    │───▶│  Spring Boot 4 / Spring │───▶│  Ollama        │
│  :3000      │SSE │  AI   :8080             │    │  :11434        │
└─────────────┘    └──────┬──────────┬───────┘    └────────────────┘
                          │          │
                          ▼          ▼
                    ┌──────────┐  ┌──────────┐    ┌────────────────┐
                    │ Postgres │  │ ChromaDB │    │ ML Sidecar     │
                    │  :5432   │  │  :8000   │◀──▶│ FastAPI :8100  │
                    └──────────┘  └──────────┘    │ Whisper+Piper  │
                                                  └────────────────┘
```

Full diagrams: [docs/DIAGRAMS.md](docs/DIAGRAMS.md).

## Tech stack

- **API**: Java 25, Spring Boot 4.0, Spring AI 2.0.0-M4
- **Frontend**: Next.js 14, TypeScript, Tailwind, shadcn/ui
- **ML Sidecar**: Python 3.11+, FastAPI, `faster-whisper`, Piper TTS
- **Storage**: PostgreSQL 16, ChromaDB 1.0.x, local filesystem for audio/models
- **LLM runtime**: Ollama (native, for GPU access)

Version matrix and rationale: [docs/DESIGN.md §6](docs/DESIGN.md#6-tech-stack-details).

## Quick start

### Prerequisites

- Java 25, Node.js 20+, Python 3.11+
- Docker (for Postgres + Chroma + E2E)
- [Ollama](https://ollama.com) running natively (`ollama serve`)
- `ffmpeg`, `yt-dlp`

Full prerequisite list: [CONTRIBUTING.md](CONTRIBUTING.md).

### Pull default models

```bash
ollama pull gemma3:27b          # chat
ollama pull mxbai-embed-large   # embeddings
```

### Run

```bash
make setup        # one-time: install deps, check system
make dev          # start API :8080, sidecar :8100, frontend :3000
```

Open **http://localhost:3000**.

Other entry points:

```bash
make start        # full prod stack via Docker Compose
make start-dev    # infra in Docker, apps native with hot reload
make stop
make logs
```

Everything goes through the Makefile — see [CLAUDE.md](CLAUDE.md#build-test-run--always-via-make) for the full command list.

## Default models

| Role | Model | Env var to override |
|---|---|---|
| Chat LLM | `gemma3:27b` | `OLLAMA_CHAT_MODEL` |
| Embeddings | `mxbai-embed-large` (1024-dim) | `OLLAMA_EMBED_MODEL` |
| Transcription | `large-v3-turbo` (Whisper) | `LOCALLOOM_WHISPER_MODEL` |
| TTS | `en_US-lessac-high` (Piper) | `LOCALLOOM_TTS_VOICE` |

Hardware tradeoffs, alternatives, and how to switch are in **[docs/MODELS.md](docs/MODELS.md)**.

> **Heads-up:** changing the embedding model also changes the embedding dimension. You must reset the Chroma collection afterward — see the gotchas in [CLAUDE.md](CLAUDE.md#gotchas).

## Configuration

Every environment variable (API, sidecar, docker-compose) is documented in **[docs/CONFIGURATION.md](docs/CONFIGURATION.md)**.

The most common ones:

```bash
# Which Ollama to talk to (API service)
SPRING_AI_OLLAMA_BASE_URL=http://localhost:11434

# Models
OLLAMA_CHAT_MODEL=gemma3:27b
OLLAMA_EMBED_MODEL=mxbai-embed-large

# Security (off by default; set to enable)
LOCALLOOM_API_KEY=
LOCALLOOM_CORS_ORIGINS=http://localhost:3000
```

## Documentation index

| Doc | What it covers |
|---|---|
| [docs/DESIGN.md](docs/DESIGN.md) | Architecture, data model, API, chunking, citations, error handling |
| [docs/DIAGRAMS.md](docs/DIAGRAMS.md) | Mermaid diagrams — system, pipelines, ERD, lifecycles |
| [docs/MODELS.md](docs/MODELS.md) | Model selection guide (LLM, embeddings, Whisper, Piper) |
| [docs/PROMPTS.md](docs/PROMPTS.md) | System and RAG prompt reference |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Environment-variable reference |
| [docs/GLOSSARY.md](docs/GLOSSARY.md) | Domain terms (Source, ContentUnit, Fragment, Chunk, …) |
| [docs/TESTING.md](docs/TESTING.md) | Test strategy, fixtures, CI considerations |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Dev setup, branch / commit format, review protocol |
| [CLAUDE.md](CLAUDE.md) | Entry point for AI coding agents (Claude Code et al.) |
| [AGENTS.md](AGENTS.md) | Vendor-neutral pointer for other AI agents |

## License

Not yet specified. See TODO in project root.

## Status

LocalLoom is in active development. APIs and data schemas may change. The current hardening branch tracks issue **APP-116** (production hardening).
