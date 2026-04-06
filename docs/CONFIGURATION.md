# Configuration

Every environment variable LocalLoom reads, where it's consumed, and what the default is.

Sources scanned to build this table:

- `api/src/main/resources/application.yml` (Spring config)
- `api/src/main/java/**/*.java` (`@Value(...)` injections)
- `ml-sidecar/app/config.py` (Pydantic settings, `LOCALLOOM_` prefix)
- `ml-sidecar/app/**/*.py` (`os.environ.get(...)`)
- `docker-compose.yml`, `docker-compose.dev.yml`, `docker-compose.test.yml`

If you add a new env var, update this table in the same PR.

---

## API (Spring Boot)

### Database

| Variable | Default | Description |
|---|---|---|
| `DB_USERNAME` | `localloom` | Postgres user for the API's JDBC connection |
| `DB_PASSWORD` | `localloom` | Postgres password |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/localloom` | Full JDBC URL; overridden in Docker to point at the `postgres` service |

### Ollama / Spring AI

| Variable | Default | Description |
|---|---|---|
| `SPRING_AI_OLLAMA_BASE_URL` | `http://localhost:11434` | Where to reach Ollama. In Docker the API container uses `http://host.docker.internal:11434` |
| `OLLAMA_CHAT_MODEL` | `gemma3:27b` | Chat LLM (`spring.ai.ollama.chat.options.model`) |
| `OLLAMA_EMBED_MODEL` | `mxbai-embed-large` | Embedding model (`spring.ai.ollama.embedding.options.model`) |

See [MODELS.md](MODELS.md) for alternatives and switching notes (esp. embedding dimension).

### ChromaDB

| Variable | Default | Description |
|---|---|---|
| `CHROMA_HOST` | `http://localhost` | ChromaDB host. In Docker: `http://chromadb` |
| `CHROMA_PORT` | `8000` | ChromaDB HTTP port |

### Import / ingest

| Variable | Default | Description |
|---|---|---|
| `MAX_EPISODES` | `0` (unlimited) | Per-source cap on items to import (`localloom.import.max-episodes`) |
| `LOCALLOOM_IMPORT_PARALLEL_EPISODES` | `4` | Concurrent audio imports (`localloom.import.parallel-episodes`) |

### Sidecar / audio / uploads

| Variable | Default | Description |
|---|---|---|
| `LOCALLOOM_SIDECAR_URL` | `http://localhost:8100` | Where the API reaches the ML sidecar (`localloom.sidecar.url`) |
| `LOCALLOOM_AUDIO_DIR` | `data/audio` | Where downloaded / converted audio lives (`localloom.audio.dir`) |
| `LOCALLOOM_AUDIO_SKIP_DEPENDENCY_CHECK` | `false` | Skip `ffmpeg` / `yt-dlp` check at startup (set in Docker where the check is unreliable) |
| `LOCALLOOM_AUDIO_YTDLP_PATH` | `yt-dlp` | Path/binary for yt-dlp (`localloom.audio.ytdlp-path`) |
| `LOCALLOOM_UPLOAD_DIR` / `localloom.upload.dir` | `data/uploads` | Local uploaded-file storage |

### Security

| Variable | Default | Description |
|---|---|---|
| `LOCALLOOM_API_KEY` | *(empty)* | If set, requires `X-API-Key` header on all non-health endpoints (`SecurityConfig`). Empty = open access |
| `LOCALLOOM_CORS_ORIGINS` | `http://localhost:3000` | Defined in YAML under `localloom.security.cors-origins`, but **currently not consumed by any Java code** — the `WebConfig` CORS bean was removed in commit `e257092`. Setting this env var has no effect on the API right now. Tracked in [docs/APP-116-review-followups.md](APP-116-review-followups.md) item #7. |
| `localloom.security.ssrf-allowed-hosts` | `[]` | YAML list of hostnames `SsrfValidator` allows on outbound URL fetches. Empty = block all SSRF candidates |

### RAG / chat

| Variable / Config key | Default | Description |
|---|---|---|
| `localloom.chat.system-prompt` | *(see [PROMPTS.md](PROMPTS.md))* | Base system prompt for non-RAG chat |
| `localloom.chat.rag-system-prompt` | *(see [PROMPTS.md](PROMPTS.md))* | System prompt for the RAG pipeline |
| `localloom.chat.top-k` | `5` | Default number of chunks to retrieve per query |

Any `localloom.*` config key can be set via the equivalent uppercase-underscored env var thanks to Spring's relaxed binding (e.g., `LOCALLOOM_CHAT_TOP_K=10`).

### Logging

| Variable | Default | Description |
|---|---|---|
| `LOG_DIR` | `logs` | API: read by `log4j2-spring.xml` as `${env:LOG_DIR:-${sys:LOG_DIR:-logs}}`, controls where `api.log` and rotated files go. The Docker compose file sets it to `/app/logs` (mounted to the `localloom-logs` volume). Sidecar uses the same env var name independently — see the sidecar table below. |

---

## ML Sidecar (FastAPI)

Pydantic `Settings` in `ml-sidecar/app/config.py` uses the prefix `LOCALLOOM_`. Any field can be overridden with the prefixed env var or via a `.env` file.

| Variable | Default | Description |
|---|---|---|
| `LOCALLOOM_MODEL_DIR` | `data/models` | Where Whisper + Piper models are cached |
| `LOCALLOOM_WHISPER_MODEL` | `large-v3-turbo` | Default Whisper model (see [MODELS.md §3](MODELS.md#3-whisper-transcription--ml-sidecar)) |
| `LOCALLOOM_WHISPER_COMPUTE_TYPE` | `auto` | `faster-whisper` compute type (`auto`, `float16`, `int8`, `int8_float16`) |
| `LOCALLOOM_TTS_VOICE` | `en_US-lessac-high` | Default Piper voice |
| `LOCALLOOM_TTS_OUTPUT_DIR` | `data/tts_output` | Where synthesized WAVs are written |
| `LOCALLOOM_HOST` | `0.0.0.0` | Bind address |
| `LOCALLOOM_PORT` | `8100` | Bind port |
| `SIDECAR_WORKERS` | `8` | Max concurrent Whisper workers (`ProcessPoolExecutor`) |
| `LOG_DIR` | `logs` | Sidecar log directory |

---

## Docker Compose

These vars are read by `docker-compose.yml` itself (not by the services at runtime):

| Variable | Default | Description |
|---|---|---|
| `POSTGRES_DB` | `localloom` | Database name passed to the `postgres` container |
| `POSTGRES_USER` | `localloom` | Postgres superuser |
| `POSTGRES_PASSWORD` | `localloom` | Postgres password |
| `VERSION` | `latest` | Image tag for `localloom/api`, `localloom/ml-sidecar`, `localloom/frontend` |
| `LOCALLOOM_SIDECAR_WORKERS` | `8` | Forwarded to the sidecar's `SIDECAR_WORKERS` |
| `GRAFANA_PASSWORD` | `localloom` | Admin password for the Grafana monitoring profile |

---

## Ports

| Service | Port | Override |
|---|---|---|
| Frontend (Next.js) | 3000 | compose `ports` |
| API (Spring Boot) | 8080 | `server.port` in `application.yml` |
| ML sidecar (FastAPI) | 8100 | `LOCALLOOM_PORT` |
| PostgreSQL | 5432 | compose `ports` |
| ChromaDB | 8000 | compose `ports` |
| Ollama | 11434 | Ollama's own config |
| Prometheus (monitoring profile) | 9090 | compose |
| Grafana (monitoring profile) | 3001 | compose (maps host :3001 → container :3000) |

---

## How to set env vars

- **Local dev (`make dev`)** — export before running, or put them in a `.env` that `uv` / Node pick up.
- **Docker Compose (`make start`)** — edit `docker-compose.yml` `environment:` blocks, or supply a compose `.env` file.
- **One-off per command** — `OLLAMA_CHAT_MODEL=gemma3:12b make dev`.

Spring Boot relaxed binding means all of these work for the same key `localloom.chat.top-k`:

```
LOCALLOOM_CHAT_TOP_K=10
LOCALLOOM_CHAT_TOPK=10
localloom.chat.top-k=10
```
