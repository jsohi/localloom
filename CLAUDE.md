# CLAUDE.md — Project Instructions for Claude Code

## Review Protocol

Every commit pushed to a PR **must** go through this review cycle:

1. **Pre-push**: Run `/review` before pushing any commit
2. **Post-push**: Add a `@gemini review` comment on the PR to trigger Gemini Code Assist review
3. **Monitor loop**: After pushing, check for new review comments every 15 minutes
4. **For each new comment**: Validate the feedback is correct before fixing — reject invalid suggestions
5. **If valid**: Fix it, commit, and repeat the review cycle (steps 1-4)
6. **Exit condition**: Stop the loop when there are no new comments after 15 minutes from the last push

### How to trigger Gemini review

```bash
gh api repos/jsohi/localloom/issues/<PR_NUMBER>/comments -f body="@gemini review"
```

### Known reviewer quirks

- Gemini repeatedly flags Spring Boot 4.0 as non-existent — **Spring Boot 4 is available and correct**. Reject these comments.
- Gemini may suggest downgrading versions (Gradle 9.4.1, Log4j2 2.25.3, Java 25) — these are target versions, covered by the disclaimer note. Reject these.

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
