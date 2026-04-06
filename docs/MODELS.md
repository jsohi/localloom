# Models

Reference for every ML model LocalLoom uses: what the defaults are, how to switch, what it costs in RAM, and what breaks if you change it.

Config lives in two places:

- `api/src/main/resources/application.yml` — Ollama (chat + embeddings) and Chroma.
- `ml-sidecar/app/config.py` — Whisper and Piper.

Everything is overridable via environment variables (see [CONFIGURATION.md](CONFIGURATION.md)).

---

## 1. Chat LLM — Ollama

| | |
|---|---|
| **Default** | `gemma3:27b` |
| **Env var** | `OLLAMA_CHAT_MODEL` |
| **Config key** | `spring.ai.ollama.chat.options.model` |
| **Temperature** | `0.7` (hardcoded in `application.yml`) |
| **Runs where** | Native Ollama on the host (`http://localhost:11434`) — API container reaches it via `host.docker.internal` |

### Alternatives

| Model | ~RAM | Quality | When to use |
|---|---|---|---|
| `gemma3:27b` *(default)* | ~24 GB | High | 48 GB+ machines (Apple M2/M3/M4, workstations) |
| `gemma3:12b` | ~10 GB | Good | 16–32 GB machines |
| `llama3.2:3b` | ~3 GB | Workable | 8–16 GB machines, laptops |
| `qwen2.5:14b` | ~10 GB | Good — strong at reasoning | Mid-range workstations |
| `llama3.3:70b` | ~45 GB | Very high | 64 GB+ Macs, dedicated LLM boxes |

### Switching

```bash
ollama pull gemma3:12b
OLLAMA_CHAT_MODEL=gemma3:12b make dev
```

No reindex required — chat model changes do not touch the vector store.

### Wiring

`SpringAiConfig.java` builds two `ChatClient` beans against the same `ChatModel`:

- `chatClient` — uses `localloom.chat.system-prompt` for plain chat.
- `ragChatClient` (qualifier `ragChatClient`) — uses `localloom.chat.rag-system-prompt` and is consumed by `RagService`.

See [PROMPTS.md](PROMPTS.md) for the prompt bodies.

---

## 2. Embeddings — Ollama

| | |
|---|---|
| **Default** | `mxbai-embed-large` |
| **Env var** | `OLLAMA_EMBED_MODEL` |
| **Config key** | `spring.ai.ollama.embedding.options.model` |
| **Dimension** | **1024** |

### Alternatives

| Model | Dim | ~RAM | Notes |
|---|---|---|---|
| `mxbai-embed-large` *(default)* | 1024 | ~650 MB | Strong general-purpose English |
| `nomic-embed-text` | 768 | ~275 MB | Lighter, slightly lower recall |
| `bge-m3` | 1024 | ~1.2 GB | Multilingual |
| `snowflake-arctic-embed2` | 1024 | ~1.1 GB | Newer, competitive on MTEB |

### ⚠️ Switching requires a reindex

The embedding dimension is baked into the Chroma collection at first write. Changing `OLLAMA_EMBED_MODEL` to a model with a different dimension — or even the same dimension but a different vector space — will cause silent retrieval failures or hard errors.

Safe procedure:

```bash
make backup                          # snapshot Postgres + Chroma first
make stop
ollama pull <new-embed-model>
OLLAMA_EMBED_MODEL=<new-embed-model> make dev
# then, from the UI or API, re-run import for each source
```

If you need to wipe Chroma completely, the dev compose file uses a named volume `chroma-data` — remove it and restart.

---

## 3. Whisper (transcription) — ML sidecar

| | |
|---|---|
| **Default** | `large-v3-turbo` |
| **Env var** | `LOCALLOOM_WHISPER_MODEL` |
| **Compute type env** | `LOCALLOOM_WHISPER_COMPUTE_TYPE` (default `auto`) |
| **Library** | [`faster-whisper`](https://github.com/SYSTRAN/faster-whisper) |
| **Model cache** | `data/models` (host volume `models-data` in Docker) |
| **Concurrency** | `SIDECAR_WORKERS` (default `8`) — max concurrent `ProcessPoolExecutor` workers |

### Alternatives

| Model | ~Size | ~RAM | Relative speed | Quality |
|---|---|---|---|---|
| `tiny` | 75 MB | ~1 GB | ~32× | Rough |
| `base` | 140 MB | ~1 GB | ~16× | OK for clean audio |
| `small` | 460 MB | ~2 GB | ~6× | Good |
| `medium` | 1.5 GB | ~5 GB | ~2× | Very good |
| `large-v3` | 3.1 GB | ~10 GB | 1× | Best |
| `large-v3-turbo` *(default)* | ~3 GB | ~6 GB | ~8× | Near-best |

### Compute types (`LOCALLOOM_WHISPER_COMPUTE_TYPE`)

- `auto` *(default)* — let `faster-whisper` choose.
- `float16` — fastest on NVIDIA GPUs.
- `int8` — fastest on CPU, small quality loss.
- `int8_float16` — GPU low-memory mode.

### Override per request

`POST /transcribe?model=tiny` lets callers override the default per request (useful for CI / tests).

---

## 4. Piper TTS — ML sidecar

| | |
|---|---|
| **Default voice** | `en_US-lessac-high` |
| **Env var** | `LOCALLOOM_TTS_VOICE` |
| **Library** | [Piper](https://github.com/rhasspy/piper) (ONNX runtime) |
| **Voice cache** | `data/models` (same as Whisper) |

### Preset voices

These are the presets hard-coded in `ml-sidecar/app/services/tts_service.py`. Additional Piper voices can be referenced by their HuggingFace path but these five are one-word names the API accepts directly:

| Preset | Language | Character |
|---|---|---|
| `en_US-lessac-high` *(default)* | English (US) | Neutral, high quality |
| `en_US-lessac-medium` | English (US) | Neutral, faster |
| `en_US-ryan-medium` | English (US) | Male, natural |
| `en_US-amy-medium` | English (US) | Female, natural |
| `en_GB-alba-medium` | English (GB) | British English |

### Limits

- Max input: **5000 characters** per `/tts` request.
- Long text is split on sentence boundaries (`. ! ?`), synthesized per sentence, and concatenated into a single WAV.

---

## 5. Where models are downloaded

| Model type | Source | Cache path |
|---|---|---|
| Ollama chat + embeddings | `ollama pull` from registry.ollama.ai | Ollama's own cache (`~/.ollama`) |
| Whisper | HuggingFace (`faster-whisper`) | `data/models` inside the sidecar |
| Piper | HuggingFace (`rhasspy/piper-voices`) | `data/models` inside the sidecar |

Everything is downloaded once on first use and cached. **Model downloads are the only outbound network calls LocalLoom makes by default.** See [DESIGN.md §11](DESIGN.md#11-security-considerations).

## 6. Memory budget (48 GB system, default models)

| Component | Estimated RAM |
|---|---|
| Ollama + `gemma3:27b` | ~24 GB |
| Ollama + `mxbai-embed-large` | ~650 MB |
| Whisper `large-v3-turbo` (per active worker) | ~6 GB |
| PostgreSQL + ChromaDB | ~2 GB |
| Spring Boot API + Python sidecar | ~2 GB |
| OS + headroom | remainder |

On smaller machines, drop to `gemma3:12b` or `llama3.2:3b` and consider `SIDECAR_WORKERS=1`.
