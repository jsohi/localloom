# ml-sidecar/CLAUDE.md — ML sidecar

Scoped instructions for AI agents working inside `ml-sidecar/`. Read the root [`../CLAUDE.md`](../CLAUDE.md) first.

## What lives here

A minimal FastAPI service that wraps the two things Spring AI cannot do in Java: **Whisper transcription** and **Piper text-to-speech**. Nothing else belongs here.

```
app/
  main.py                  FastAPI app, middleware, router wiring
  config.py                Pydantic Settings (prefix LOCALLOOM_)
  logging_config.py        Log directory + rotation
  endpoints/
    health.py              GET /health
    metrics.py              GET /metrics (Prometheus)
    transcribe.py           POST /transcribe
    tts.py                   POST /tts
  services/
    whisper_service.py      faster-whisper via ProcessPoolExecutor
    tts_service.py          Piper TTS, voice preset map
tests/                     pytest tests
pyproject.toml             uv-managed project
```

Runs on port **8100** by default.

## Commands

```bash
cd ml-sidecar && uv sync                    # install deps into .venv
cd ml-sidecar && uv run pytest              # all tests
cd ml-sidecar && uv run pytest --ignore=tests/ml   # skip slow ML tests (what `make push` uses)
cd ml-sidecar && uv run ruff format .       # format
cd ml-sidecar && uv run ruff check .        # lint
cd ml-sidecar && uv run ruff check --fix .  # lint + autofix
cd ml-sidecar && uv run uvicorn app.main:app --reload --port 8100   # run standalone
```

`make dev` starts the sidecar alongside the API and frontend — prefer that.

## Conventions

- **`uv` is the package manager.** Do not use `pip` or `poetry`. Do not commit a `requirements.txt`.
- **`ruff` is formatter AND linter.** Do not add black / isort / flake8.
- **Pydantic `Settings`** — all config goes through `app.config.settings`, never read `os.environ` directly except for truly local concerns (e.g., `SIDECAR_WORKERS` in `whisper_service.py` is a controlled exception).
- **Thin endpoints, fat services.** `endpoints/*.py` files should be request/response plumbing only. All model code lives in `services/`.
- **No business logic in the sidecar.** If the task doesn't require a Python-native ML library, it belongs in the Java API instead.

## Adding things

### Add an endpoint

1. New file in `app/endpoints/`. Export a `router = APIRouter()`.
2. Wire it in `app/main.py` via `app.include_router(...)`.
3. All heavy work goes in a matching `app/services/*.py` module.
4. Add a test in `tests/`.

### Add a new Whisper compute option

`whisper_service.py` lazy-loads the model via `faster-whisper`. To expose a new compute type, route it through `settings.whisper_compute_type` — do not hardcode strings in endpoint code.

### Add a TTS voice preset

Edit the `_VOICE_PATHS` dict in `app/services/tts_service.py`. Entries map a short name (`en_US-lessac-high`) to a HuggingFace sub-path under `rhasspy/piper-voices`. The service downloads and caches on first use.

### Update the Whisper model default

Change `whisper_model` in `app/config.py`. Document the change in [`docs/MODELS.md`](../docs/MODELS.md#3-whisper-transcription--ml-sidecar).

## Gotchas

- **`ProcessPoolExecutor`** — `whisper_service.MAX_WORKERS` (env `SIDECAR_WORKERS`, default 8) bounds concurrent Whisper runs. Workers auto-shutdown after 60s idle. Do not swap this for threads — Whisper releases the GIL badly.
- **Model downloads happen lazily.** First request for a new model hangs until the download finishes. For CI, pre-seed `data/models/` or use `tiny`.
- **`data/models` is a Docker volume** (`models-data`). Wiping the volume forces re-download of every Whisper + Piper model. Don't do it casually.
- **`/transcribe` accepts an optional `model` query param** — request-level override for tests. Default comes from settings.
- **Piper TTS is strictly single-voice per request.** If you need multi-voice stitching, add a new endpoint rather than overloading `/tts`.
- **TTS max input is 5000 characters.** Enforced in `app/endpoints/tts.py` (request validation), not in the service. Raising the limit without measuring memory will OOM large synthesis jobs.
- **CORS** — the sidecar has its own CORS config in `main.py`. It already allows `localhost:8080`, `localhost:3000`, and their Docker equivalents. Do not add wildcards.
- **Do not add embedding or chunking code here.** Those live in the Java API (Spring AI).

## Testing

- Tests live flat under `tests/` (e.g., `test_transcribe.py`, `test_whisper_service.py`, `test_tts.py`).
- `make push` runs `uv run pytest --ignore=tests/ml` — any slow ML-heavy tests belong under a `tests/ml/` subdirectory so they're skipped in preflight.
- Use `short-clip-10s.wav` from `test-fixtures/` for transcription tests.
