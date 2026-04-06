# Prompts

Reference for every prompt LocalLoom sends to the LLM. Prompts live in configuration, not in code, so you can tune them without rebuilding.

All prompt text is defined in `api/src/main/resources/application.yml` under `localloom.chat.*`. Spring AI wires them into `ChatClient` beans in `api/src/main/java/com/localloom/config/SpringAiConfig.java`.

---

## 1. Chat system prompt

**Config key:** `localloom.chat.system-prompt`
**Bean:** `chatClient` (default `ChatClient`)
**Used by:** non-RAG chat paths (direct `ChatClient` consumers)

```
You are LocalLoom, a helpful AI assistant for a personal knowledge base.
Answer questions using the provided context. Be concise and helpful.
Cite sources with the title and section or timestamp when relevant.
```

## 2. RAG system prompt

**Config key:** `localloom.chat.rag-system-prompt`
**Bean:** `ragChatClient` (qualifier `@Qualifier("ragChatClient")`)
**Used by:** `RagService` — the main Q&A path exposed at `POST /api/v1/query`

```
You are a helpful assistant. Answer the user's question based on the
context provided below. Use the context to give a thorough, accurate
answer. Cite your sources by mentioning the title and section.
If the context is partially relevant, answer what you can from it.
Only say you cannot answer if the context is completely unrelated
to the question.
```

---

## 3. How the RAG prompt is composed

`RagService.buildAdvisor()` builds a Spring AI `RetrievalAugmentationAdvisor` with three pieces:

1. **`VectorStoreDocumentRetriever`** — pulls the top-`k` chunks (default `localloom.chat.top-k: 5`) from Chroma, optionally filtered by `sourceIds` / `sourceTypes` via `VectorStoreFilters.buildFilterExpression(...)`.
2. **`ContextualQueryAugmenter`** — injects the retrieved documents into the prompt as context. Configured with `allowEmptyContext(false)` — if retrieval returns nothing, the advisor refuses to fall through to the bare LLM.
3. **`CompressionQueryTransformer`** *(conditional)* — when the request carries a `conversationId`, the user's new question is rewritten against the prior turns so retrieval matches the resolved intent, not the raw follow-up.

The retrieved documents are merged with the system prompt (above) and the user question, and the combined prompt is sent through the `ragChatClient` `ChatClient`. Streaming (`streamAnswer`) uses the same pipeline via `.stream().content()`.

Conversation history, when present, is loaded by `loadConversationHistory(conversationId)` and attached as `UserMessage` / `AssistantMessage` turns on the `ChatClient` request spec — Spring AI places those between the system prompt and the new user message.

## 4. Citations

After the response comes back, `RagService.extractCitations()` reads the retrieved documents out of the advisor's context (`RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT`) and maps each document's metadata into a `Citation` DTO:

```
Citation(sourceType, contentUnitTitle, location, sourceId, contentUnitId)
```

The metadata keys (`source_type`, `content_unit_title`, `location`, `source_id`, `content_unit_id`) are populated at ingest time by `EmbeddingService` when it writes chunks to the vector store. See [DESIGN.md §10 Citation System](DESIGN.md#10-citation-system) for the per-source-type format.

The frontend receives citations as a separate SSE event:

```
event: sources
data: {"sources": [ ... ]}
```

and renders them inline with the streamed answer (`frontend/src/components/chat-message.tsx`).

## 5. Overriding the prompts

Because the prompts are `@Value("${localloom.chat.*}")` injected, you can override them without touching code.

**For local dev** — edit `api/src/main/resources/application.yml` and restart.

**For docker-compose** — set Spring-friendly env vars in `docker-compose.yml`:

```yaml
environment:
  LOCALLOOM_CHAT_SYSTEM_PROMPT: "You are …"
  LOCALLOOM_CHAT_RAG_SYSTEM_PROMPT: "You are …"
```

Spring's relaxed binding maps `LOCALLOOM_CHAT_RAG_SYSTEM_PROMPT` → `localloom.chat.rag-system-prompt`.

**Top-k override per request** — `RagQuery.topK` is respected per call; the config default is the fallback.

## 6. Tuning notes

- **`allowEmptyContext(false)`** — if you lower this bar and allow empty context, the LLM will start answering from its own training data, which defeats the "privacy-first knowledge base" framing. Keep it `false` unless you know why you're changing it.
- **`top-k`** default of `5` — higher values give the LLM more to chew on but inflate the prompt size and slow down retrieval. 3–10 is a reasonable band.
- **Citation phrasing** — the RAG prompt asks the model to cite "by mentioning the title and section". If you change the prompt to require a different citation format (e.g., numeric footnotes), also update the frontend renderer that parses the `sources` SSE event.
- **Compression transformer cost** — every follow-up question in a conversation incurs an extra LLM call to rewrite the question. On low-end hardware you may want to disable it by clearing `conversationId` on the client.
