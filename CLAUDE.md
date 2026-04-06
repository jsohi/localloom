# CLAUDE.md — Project Instructions for Claude Code

This file is the primary entry point for AI coding agents (Claude Code, and — via `AGENTS.md` — other agents). Read it first. Per-module scoped guidance lives in `api/CLAUDE.md`, `frontend/CLAUDE.md`, and `ml-sidecar/CLAUDE.md`.

## Review Protocol

Every commit pushed to a PR **must** go through this review cycle:

1. **Pre-push**: Run `/review` before pushing any commit
2. **For each review comment**: Validate the feedback is correct before fixing — reject invalid suggestions
3. **If valid**: Fix it, commit, and repeat the review cycle
4. After pushing, add a `@gemini review` comment on the PR to trigger Gemini Code Assist review

## Project Overview

LocalLoom is a privacy-first, locally-running multi-source knowledge base. It ingests content from RSS / podcast feeds, YouTube videos, web pages, and local file uploads (Teams and GitHub are planned, opt-in), then indexes it for RAG-powered Q&A using a local LLM via Ollama.

### Tech Stack

- **API**: Java 25 / Spring Boot 4.0 / Spring AI
- **Frontend**: Next.js 14 / TypeScript / Tailwind / shadcn/ui
- **ML Sidecar**: Python 3.11+ / FastAPI (Whisper transcription + Piper TTS only)
- **Storage**: PostgreSQL 16 (metadata), ChromaDB 1.0.x (vectors), local filesystem (audio/TTS/models)
- **LLM/Embeddings**: Ollama (local)

### Default models

See `docs/MODELS.md` for the full guide. Currently:

- Chat: `gemma3:27b` (`OLLAMA_CHAT_MODEL`)
- Embeddings: `mxbai-embed-large`, 1024-dim (`OLLAMA_EMBED_MODEL`)
- Whisper: `large-v3-turbo` (`LOCALLOOM_WHISPER_MODEL`)
- Piper TTS: `en_US-lessac-high` (`LOCALLOOM_TTS_VOICE`)

### Connectors (as implemented)

| Type | Enabled by default | Status |
|---|---|---|
| `MEDIA` (RSS / podcasts / direct audio URLs) | ✓ | implemented |
| `YOUTUBE` | ✓ | implemented |
| `WEB_PAGE` | ✓ | implemented |
| `FILE_UPLOAD` | ✓ | implemented |
| `TEAMS` | ✗ | planned, opt-in |
| `GITHUB` | ✗ | planned, opt-in |

Gating is via `localloom.connectors.*.enabled` in `api/src/main/resources/application.yml`, read through `ConnectorProperties` and surfaced by `ConnectorController`.

## Repository Layout

```
api/          Spring Boot 4 / Spring AI backend (Java 25) — see api/CLAUDE.md
frontend/     Next.js 14 app — see frontend/CLAUDE.md
ml-sidecar/   FastAPI Whisper+Piper sidecar — see ml-sidecar/CLAUDE.md
docs/         Architecture, models, prompts, config, testing, glossary
scripts/      Shell helpers invoked by the Makefile
monitoring/   Prometheus + Grafana configs (docker compose profile)
test-fixtures/ Sample audio, RSS feeds, etc.
docker-compose.yml          Full prod stack (postgres, chroma, api, sidecar, frontend)
docker-compose.dev.yml      Dev overrides
docker-compose.test.yml     E2E overrides
Makefile                    Canonical entry point for every task
```

## Build, Test, Run — always via `make`

**Never invoke scripts directly.** Use the Makefile targets below (see `make help`-style comments in the Makefile itself):

| Task | Command |
|---|---|
| One-time setup | `make setup` |
| Start everything for dev (API :8080, sidecar :8100, frontend :3000) | `make dev` |
| Restart after changes | `make restart` |
| Stop everything | `make stop` |
| Lint all three projects (Spotless, ruff, ESLint) | `make lint` |
| Auto-format all three projects | `make format` |
| Run all tests | `make test` |
| Run E2E (Playwright + Docker) | `make e2e` |
| Build all three (skip tests) | `make build` |
| Pre-flight + push (format + lint + test + push) | `make push` |
| Prod bring-up via Docker Compose | `make start` |
| Dev bring-up (infra in Docker, apps native) | `make start-dev` |
| Tail logs | `make logs` |
| Backup Postgres + Chroma | `make backup` |

Per-module commands (use only when you need to target one project):

```bash
cd api && ./gradlew test              # API unit + integration tests
cd api && ./gradlew spotlessApply     # Java format
cd ml-sidecar && uv run pytest        # Sidecar tests
cd ml-sidecar && uv run ruff check .  # Sidecar lint
cd frontend && npx vitest run         # Frontend tests
cd frontend && npm run lint           # Frontend lint
```

## Running Locally (minimum viable)

1. Start Ollama natively (`ollama serve`) and pull the default models:
   ```bash
   ollama pull gemma3:27b
   ollama pull mxbai-embed-large
   ```
2. `make setup` (first time only)
3. `make dev` — brings up Postgres + Chroma in Docker and runs API, sidecar, frontend natively with hot reload
4. Open http://localhost:3000

## Where Things Live

| Topic | File |
|---|---|
| RAG entry point | `api/src/main/java/com/localloom/service/RagService.java` |
| RAG query controller (SSE) | `api/src/main/java/com/localloom/controller/QueryController.java` |
| Embedding / chunking | `api/src/main/java/com/localloom/service/EmbeddingService.java` |
| Spring AI beans (ChatClient, TokenTextSplitter) | `api/src/main/java/com/localloom/config/SpringAiConfig.java` |
| System + RAG prompts | `api/src/main/resources/application.yml` → `localloom.chat.*` |
| Connector interface | `api/src/main/java/com/localloom/connector/SourceConnector.java` |
| Connector impls | `api/src/main/java/com/localloom/connector/{Media,YouTube,WebPage,FileUpload}Connector.java` |
| Connector config schema | `api/src/main/java/com/localloom/config/ConnectorProperties.java` |
| DB schema | `api/src/main/resources/db/migration/V1__create_schema.sql` (consolidated, do not add V2+ unless necessary) |
| Sidecar routes | `ml-sidecar/app/endpoints/{transcribe,tts,health,metrics}.py` |
| Whisper service | `ml-sidecar/app/services/whisper_service.py` |
| Piper service | `ml-sidecar/app/services/tts_service.py` |
| Sidecar config | `ml-sidecar/app/config.py` |
| Frontend chat page | `frontend/src/app/chat/page.tsx` |
| Frontend SSE client | `frontend/src/lib/api.ts` (`streamQuery`) |
| Security (API key, SSRF) | `api/src/main/java/com/localloom/config/SecurityConfig.java`, `service/SsrfValidator.java` |

## Coding Conventions

### Java (api/)

- Use `final` on all method parameters.
- Prefer `var` for local variables.
- Use Java 25 features (pattern matching, records, sealed types) where they clarify intent.
- **Log4j2 direct** — use `LogManager.getLogger(...)`, never SLF4J.
- Spring Boot 4.0 is intentional. Do not downgrade. Spring AI 2.0.0-M4 BOM is used (see `api/build.gradle*`); `ChatClient`, `RetrievalAugmentationAdvisor`, `VectorStoreDocumentRetriever`, `TokenTextSplitter` come from there.
- Prefer constructor injection with `final` fields.
- Spotless (`make format`) is the source of truth for formatting.

### Python (ml-sidecar/)

- Format and lint with `ruff` (via `uv run ruff`).
- Pydantic settings loaded from `LOCALLOOM_*` env vars (see `ml-sidecar/app/config.py`).
- Endpoints are thin — all heavy work lives in `services/`.

### Frontend (frontend/)

- ESLint + Prettier.
- shadcn/ui components live under `frontend/src/components/ui/`.
- SSE streaming is centralized in `frontend/src/lib/api.ts` — reuse, don't reimplement.

## Git Hygiene

- Branch: `feature/APP-XXX-short-description`.
- Commit: `APP-XXX: Short description` (summary <72 chars).
- **Never amend published commits.** Always create new commits.
- **Never force-push `main`/`master`.** The Makefile `push` target refuses direct pushes to main.
- Never use `--no-verify` or bypass hooks.

## Gotchas

- **Ollama must run natively**, not in Docker, for GPU access. The API container uses `host.docker.internal:11434` to reach it.
- **Embedding dimension is fixed by the model.** `mxbai-embed-large` = 1024-dim. If you change `OLLAMA_EMBED_MODEL` you must drop the Chroma collection (`make backup` first) and re-index — dimension mismatches cause silent retrieval failures.
- **`TokenTextSplitter`** is tuned in `SpringAiConfig.java`: chunk size 500 tokens, min-chunk-chars 50, max 1000 chunks.
- **`TtsService` is optional** — features degrade gracefully if the sidecar is unreachable.
- **ChromaDB schema init** is handled by Spring AI (`spring.ai.vectorstore.chroma.initialize-schema: true`). The collection is created on first write.
- **Consolidated Flyway migration** — there is only `V1__create_schema.sql`. Recent history shows we squashed pre-prod migrations; do not add V2+ without a deliberate reason.
- **CORS** default is `http://localhost:3000`. Override via `LOCALLOOM_CORS_ORIGINS`.
- **API key auth** is off by default. Set `LOCALLOOM_API_KEY` to require `X-API-Key`.

## Pointers

- Architecture, data model, API: **[docs/DESIGN.md](docs/DESIGN.md)**
- Diagrams: **[docs/DIAGRAMS.md](docs/DIAGRAMS.md)**
- Testing strategy: **[docs/TESTING.md](docs/TESTING.md)**
- Models: **[docs/MODELS.md](docs/MODELS.md)**
- Prompts: **[docs/PROMPTS.md](docs/PROMPTS.md)**
- Environment variables: **[docs/CONFIGURATION.md](docs/CONFIGURATION.md)**
- Glossary: **[docs/GLOSSARY.md](docs/GLOSSARY.md)**
- Contributing workflow: **[CONTRIBUTING.md](CONTRIBUTING.md)**
