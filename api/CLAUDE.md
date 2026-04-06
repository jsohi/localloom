# api/CLAUDE.md — Spring Boot API

Scoped instructions for AI agents working inside `api/`. Read the root [`../CLAUDE.md`](../CLAUDE.md) first for project-wide context.

## What lives here

Spring Boot 4.0 / Spring AI 2.0.0-M4 / Java 25 backend. Single Gradle project; all code under `src/main/java/com/localloom/`:

```
config/       Spring @Configuration classes, @ConfigurationProperties records
connector/    SourceConnector implementations (Media, YouTube, WebPage, FileUpload)
controller/   REST controllers (Query, Connector, Source, Conversation, Tts, ...)
model/        JPA entities + enums (Source, ContentUnit, ContentFragment, Conversation, Message, Job)
repository/   Spring Data JPA repositories
service/      Business logic (RagService, EmbeddingService, AudioService, MlSidecarClient, TtsService, ...)
LocalLoomApplication.java   @SpringBootApplication entry point
```

Resources:

```
src/main/resources/
  application.yml                          canonical config
  db/migration/V1__create_schema.sql       single consolidated Flyway migration
  log4j2-spring.xml                        Log4j2 config (NOT logback)
```

## Commands

Always from the repo root via `make` when possible. Direct Gradle when targeting only the API:

```bash
cd api && ./gradlew test             # unit + integration (Testcontainers)
cd api && ./gradlew build -x test    # compile + package, skip tests
cd api && ./gradlew spotlessApply    # format
cd api && ./gradlew spotlessCheck    # verify format
```

Integration tests use Testcontainers and require Docker to be running.

## Conventions

- **`final` on every method parameter.** Spotless enforces format, but parameter finality is a habit you maintain.
- **`var` for local variables** unless the type aids readability.
- **Log4j2 directly** — `private static final Logger log = LogManager.getLogger(MyClass.class);`. **Never** `org.slf4j.*`.
- **Constructor injection with `final` fields** — no `@Autowired` on fields.
- **`@Value("${key:default}")`** — always include a default when reasonable, so tests don't need to set every key.
- **Java 25 idioms welcome**: records, sealed types, pattern matching (`switch` and `instanceof`), `var`. Use them when they clarify intent.
- **No SLF4J**, no Logback, no Lombok.

## Adding things

### Add a REST endpoint

1. New controller class in `controller/`, annotated `@RestController` + `@RequestMapping("/api/v1/...")`.
2. Inject services via constructor with `final` fields.
3. Return DTOs (records under `service/dto/` or inline records in the controller for controller-scoped shapes).
4. Add a unit test under `src/test/java/com/localloom/controller/` using `@WebMvcTest`.

### Add a Spring AI bean

Add it to `config/SpringAiConfig.java`. The class already builds `ChatClient` (default), `ragChatClient` (qualifier), `TokenTextSplitter`, and a Jackson 2.x `ObjectMapper`. Keep it the one place Spring AI wiring lives.

### Add a source connector

1. Add a new `SourceType` enum value.
2. Implement `SourceConnector` (`sourceType()` + `importSource(source, jobId, maxItems)`).
3. Do discovery + fetch + extract internally; hand extracted `ContentFragment`s to `EmbeddingService`.
4. Add `localloom.connectors.<new-type>.enabled: false` to `application.yml` and a field on `ConnectorProperties`.
5. Register the connector in `ConnectorController.listConnectors()`.
6. Gate usage on `connectors.<new-type>().enabled()` at runtime — there is no `@ConditionalOnProperty` on existing connectors; follow the pattern.
7. Tests under `src/test/java/com/localloom/connector/`.

### Add a Flyway migration

There is currently **one** migration: `V1__create_schema.sql`. Recent history consolidated earlier migrations into V1. Add a new `V2__...sql` only when you need to migrate real prod data; otherwise extend V1 while the schema is still fluid.

## Gotchas

- **Spring Boot 4.0 ships Jackson 3.x.** Services that need the classic Jackson 2.x `com.fasterxml.jackson.databind.ObjectMapper` (e.g., `WebPageService`, `UrlResolver`, `AudioImportSupport`, `QueryService`, `FileUploadService`) get it from the explicit bean in `SpringAiConfig.java`. Don't delete that bean.
- **Embedding dimension is pinned by the model** — 1024 for the default `mxbai-embed-large`. Changing `OLLAMA_EMBED_MODEL` to a different-dimension model silently breaks Chroma. See [docs/MODELS.md](../docs/MODELS.md#2-embeddings--ollama).
- **`TokenTextSplitter` is already tuned** — 500 tokens, min-chunk-chars 50, max 1000 chunks. Change it only if you have a good reason, and re-index afterward.
- **Spring AI `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT`** is the key `RagService.extractCitations()` reads retrieved documents from. If you rewrite the advisor, don't lose that post-hoc read.
- **`CompressionQueryTransformer`** is attached only when the request carries a `conversationId`. Every follow-up question then costs an extra LLM call for question rewriting.
- **`SsrfValidator`** — default allowlist is empty, which blocks SSRF candidates. Don't relax it without a reason.
- **API key auth** — `LOCALLOOM_API_KEY` empty = open. The filter in `SecurityConfig` skips auth entirely when unset.

## Testing notes

- Embeddings in CI use Spring AI's `spring-ai-starter-model-transformers` with `all-MiniLM-L6-v2` so tests don't need Ollama. See [docs/TESTING.md §7.2](../docs/TESTING.md).
- ChromaDB in integration tests runs via Testcontainers.
- Do not add tests that require a real Ollama to be running — use the transformer-based embeddings instead.
