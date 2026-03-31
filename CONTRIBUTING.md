# Contributing to LocalLoom

## Prerequisites

- **Java 25** (for the Spring Boot API)
- **Python 3.11+** (for the ML sidecar)
- **Node.js 20+** (for the Next.js frontend)
- **Docker** (required for Testcontainers in integration tests)
- **Ollama** (local LLM and embeddings)
- **ffmpeg** (audio processing)
- **yt-dlp** (podcast/video downloading)

## Local Setup

```bash
make setup && make dev
```

### Port Mapping

| Service     | Port |
|-------------|------|
| API         | 8080 |
| ML Sidecar  | 8100 |
| Frontend    | 3000 |

## Branch Naming

```
feature/APP-XXX-short-description
```

## Commit Format

```
APP-XXX: Short description of the change
```

Keep the summary line under 72 characters. Use the body for additional context when needed.

## Code Style

### Java

- Use `final` on all method parameters
- Prefer `var` for local variables
- Use pattern matching and other Java 25 features where appropriate
- Use Log4j2 directly (`LogManager` / `Logger`), never SLF4J

### Python

- Format and lint with **ruff**

### Frontend

- Lint with **ESLint**
- Format with **Prettier**

## Review Protocol

1. Run `/review` before every push
2. All tests must pass before pushing
3. After pushing, add a `@gemini review` comment on the PR to trigger Gemini Code Assist review
4. Monitor for review comments and address valid feedback

## Running Tests

```bash
# API (unit + integration)
./gradlew test

# Frontend
cd frontend && npm test

# Python sidecar
cd ml-sidecar && uv sync && ruff check .
```
