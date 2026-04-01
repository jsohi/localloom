# CLAUDE.md — Project Instructions for Claude Code

## Review Protocol

Every commit pushed to a PR **must** go through this review cycle:

1. **Pre-push**: Run `/review` before pushing any commit
2. **For each review comment**: Validate the feedback is correct before fixing — reject invalid suggestions
3. **If valid**: Fix it, commit, and repeat the review cycle

## Project Overview

LocalLoom is a privacy-first, locally-running multi-source knowledge base. It ingests content from podcasts, Confluence, MS Teams, GitHub, and file uploads, then indexes it for RAG-powered Q&A using a local LLM.

### Tech Stack

- **API**: Java 25 / Spring Boot 4.0 / Spring AI
- **Frontend**: Next.js 14 / TypeScript / Tailwind / shadcn/ui
- **ML Sidecar**: Python 3.11+ / FastAPI (Whisper transcription + Piper TTS only)
- **Storage**: PostgreSQL (metadata), ChromaDB (vectors), File system (audio/TTS)
- **LLM/Embeddings**: Ollama (local)

### Key Architecture Decisions

- Source connectors are configurable via `application.yml` (`localloom.connectors.<type>.enabled`)
- Podcast and file-upload connectors enabled by default; external connectors (Confluence, Teams, GitHub) opt-in
- Python sidecar only handles audio-specific tasks (Whisper + TTS)
- Spring AI handles embeddings, vector store, RAG, and chunking
