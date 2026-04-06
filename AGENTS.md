# AGENTS.md

This repository's instructions for AI coding agents (Cursor, Aider, OpenAI Codex CLI, Sourcegraph Amp, Jules, Claude Code, and any other tool following the [agents.md](https://agents.md) convention) live in **[CLAUDE.md](CLAUDE.md)**.

**Start by reading `CLAUDE.md`.** It is the single source of truth for:

- Project overview, tech stack, and default models
- Build / test / run commands (always via `make`)
- Repository layout and "where things live"
- Coding conventions (Java 25, Python ruff, Next.js) and gotchas
- Git hygiene and the review protocol

For scoped guidance when working inside a specific module, also read:

- [`api/CLAUDE.md`](api/CLAUDE.md) — Spring Boot 4 / Spring AI backend
- [`frontend/CLAUDE.md`](frontend/CLAUDE.md) — Next.js 14 frontend
- [`ml-sidecar/CLAUDE.md`](ml-sidecar/CLAUDE.md) — FastAPI Whisper + Piper sidecar

Deeper references:

- [`README.md`](README.md) — human-facing project overview and quick start
- [`docs/DESIGN.md`](docs/DESIGN.md) — architecture and data model
- [`docs/MODELS.md`](docs/MODELS.md) — model selection
- [`docs/PROMPTS.md`](docs/PROMPTS.md) — system prompts
- [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) — environment variables
- [`docs/GLOSSARY.md`](docs/GLOSSARY.md) — domain terminology
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — workflow and style

This file exists so agents that do not look for `CLAUDE.md` still land in the right place. Keep it a pointer, not a duplicate — update `CLAUDE.md` instead.
