# LocalLoom — Architecture & Flow Diagrams

## 1. System Architecture

> **Note:** Spring Boot and Java versions shown are target versions for this project. Adjust to the latest stable release at implementation time.

```mermaid
graph TB
    subgraph User Machine
        subgraph Frontend
            UI[Next.js 14<br/>:3000]
        end

        subgraph API["API Server (Java / Spring Boot + Spring AI)"]
            Controllers[REST Controllers]
            Services[Service Layer]
            Jobs[Job Scheduler<br/>@Async]
            ConnReg[ConnectorRegistry]
            Connectors[Source Connectors<br/>Podcast · Confluence · Teams<br/>GitHub · File Upload]
            SpringAI[Spring AI<br/>ChatClient + RAG Advisor]
            EmbedModel[OllamaEmbeddingModel]
            VectorStore[ChromaDbVectorStore]
            Chunker[TokenTextSplitter]
            SidecarClient[ML Sidecar Client]
        end

        subgraph MLSidecar["ML Sidecar (Python / FastAPI — Audio only)"]
            Transcribe["/transcribe<br/>Whisper"]
            TTS["/tts<br/>Piper TTS"]
        end

        subgraph Storage
            DB[(PostgreSQL<br/>Metadata)]
            Chroma[(ChromaDB<br/>Vectors)]
            Files[(File Store<br/>Audio / TTS)]
        end

        subgraph External
            Ollama[Ollama<br/>:11434<br/>LLM + Embeddings]
        end
    end

    UI -->|HTTP / SSE| Controllers
    Controllers --> Services
    Services --> Jobs
    Services --> ConnReg
    ConnReg --> Connectors
    Services --> SpringAI
    Services --> Chunker
    Services --> VectorStore
    Services --> DB
    Services --> Files

    Connectors -->|Audio sources| SidecarClient
    SpringAI -->|Streaming Chat| Ollama
    EmbedModel -->|Embed Text| Ollama
    VectorStore --> Chroma
    SpringAI --> VectorStore
    VectorStore --> EmbedModel

    SidecarClient -->|HTTP| Transcribe
    SidecarClient -->|HTTP| TTS

    style UI fill:#3b82f6,color:#fff
    style Controllers fill:#16a34a,color:#fff
    style Services fill:#16a34a,color:#fff
    style Jobs fill:#16a34a,color:#fff
    style ConnReg fill:#f97316,color:#fff
    style Connectors fill:#f97316,color:#fff
    style SpringAI fill:#16a34a,color:#fff
    style EmbedModel fill:#16a34a,color:#fff
    style VectorStore fill:#16a34a,color:#fff
    style Chunker fill:#16a34a,color:#fff
    style SidecarClient fill:#16a34a,color:#fff
    style Transcribe fill:#eab308,color:#000
    style TTS fill:#eab308,color:#000
    style Ollama fill:#a855f7,color:#fff
    style DB fill:#6b7280,color:#fff
    style Chroma fill:#6b7280,color:#fff
    style Files fill:#6b7280,color:#fff
```

---

## 2. Content Ingestion Pipeline

```mermaid
flowchart TD
    A[User adds a source] --> B{Detect Source Type}

    B -->|Podcast URL| C[PodcastConnector<br/>Resolve feed + discover episodes]
    B -->|Confluence URL| D[ConfluenceConnector<br/>List pages in space]
    B -->|GitHub URL| E[GitHubConnector<br/>List files in repo]
    B -->|Teams Channel| F[TeamsConnector<br/>List message threads]
    B -->|File Upload| G[FileUploadConnector<br/>Accept uploaded files]

    C --> H[Fetch audio<br/>yt-dlp or HTTP]
    H --> I[POST /transcribe<br/>to Python Sidecar]
    I --> J[Whisper large-v3-turbo<br/>timestamped segments]
    J --> K[Save fragments<br/>to PostgreSQL]

    D --> L[Fetch page content<br/>via Confluence REST API]
    L --> K

    E --> M[Fetch files<br/>via GitHub API]
    M --> K

    F --> N[Fetch messages<br/>via MS Graph API]
    N --> K

    G --> O[Read uploaded files<br/>text / markdown / PDF]
    O --> K

    K --> P[Spring AI TokenTextSplitter<br/>~500 tokens, 50 overlap]
    P --> Q[Add documents to ChromaDbVectorStore<br/>uses OllamaEmbeddingModel for embeddings]
    Q --> R[Mark ContentUnit as INDEXED]

    style A fill:#3b82f6,color:#fff
    style B fill:#f59e0b,color:#000
    style C fill:#f97316,color:#fff
    style D fill:#f97316,color:#fff
    style E fill:#f97316,color:#fff
    style F fill:#f97316,color:#fff
    style G fill:#f97316,color:#fff
    style I fill:#eab308,color:#000
    style J fill:#eab308,color:#000
    style P fill:#16a34a,color:#fff
    style Q fill:#16a34a,color:#fff
    style R fill:#16a34a,color:#fff

    subgraph Spring Boot + Spring AI
        B
        C
        D
        E
        F
        G
        H
        K
        L
        M
        N
        O
        P
        Q
        R
    end

    subgraph Python Sidecar
        I
        J
    end
```

---

## 3. RAG Query Pipeline

```mermaid
flowchart TD
    A[User asks question] --> B["Spring Boot receives<br/>POST /api/v1/query<br/>(optional: source_types filter)"]

    B --> C[Retrieve documents via<br/>Spring AI RetrievalAugmentationAdvisor]
    C --> F[Return ranked chunks<br/>with source metadata]

    F --> G[Spring AI builds<br/>augmented prompt]

    G --> H["System: Answer using ONLY<br/>provided context. Cite sources."]
    G --> I["Context: 5 chunks with<br/>[Source: title, location]"]
    G --> J["User: original question"]

    H --> K[OllamaChatModel<br/>stream: true]
    I --> K
    J --> K

    K --> L[Stream tokens<br/>via SSE to frontend]
    L --> M[Display streaming<br/>response in Chat UI]

    L --> N[Save to conversation<br/>history in PostgreSQL]

    N --> O{TTS requested?}
    O -->|Yes| P[POST /tts to<br/>Python Sidecar]
    P --> Q[Piper TTS generates<br/>audio WAV]
    Q --> R[Audio player in UI]
    O -->|No| S[Done]

    style A fill:#3b82f6,color:#fff
    style C fill:#16a34a,color:#fff
    style K fill:#a855f7,color:#fff
    style L fill:#16a34a,color:#fff
    style M fill:#3b82f6,color:#fff
    style P fill:#eab308,color:#000
    style Q fill:#eab308,color:#000
    style R fill:#3b82f6,color:#fff
```

---

## 4. ContentUnit Status Lifecycle

### Audio Content (Podcasts)

```mermaid
stateDiagram-v2
    [*] --> PENDING: ContentUnit discovered

    PENDING --> FETCHING: Fetch job starts
    FETCHING --> TRANSCRIBING: Audio saved to disk
    TRANSCRIBING --> EMBEDDING: Transcript fragments saved
    EMBEDDING --> INDEXED: Vectors stored in ChromaDB

    FETCHING --> ERROR: Download failed
    TRANSCRIBING --> ERROR: Whisper failed
    EMBEDDING --> ERROR: Embedding failed

    ERROR --> PENDING: Retry requested

    INDEXED --> [*]
```

### Text Content (Confluence, GitHub, Teams)

> **Note:** File uploads skip the FETCHING state since content is already local.
> Upload lifecycle: PENDING → EXTRACTING → EMBEDDING → INDEXED

```mermaid
stateDiagram-v2
    [*] --> PENDING: ContentUnit discovered

    PENDING --> FETCHING: Fetch job starts
    FETCHING --> EXTRACTING: Content downloaded
    EXTRACTING --> EMBEDDING: Text fragments saved
    EMBEDDING --> INDEXED: Vectors stored in ChromaDB

    FETCHING --> ERROR: Fetch failed
    EXTRACTING --> ERROR: Extraction failed
    EMBEDDING --> ERROR: Embedding failed

    ERROR --> PENDING: Retry requested

    INDEXED --> [*]
```

---

## 5. Entity Relationship Diagram

```mermaid
erDiagram
    SOURCE ||--o{ CONTENT_UNIT : "has many"
    CONTENT_UNIT ||--o{ CONTENT_FRAGMENT : "has many"
    CONVERSATION ||--o{ MESSAGE : "has many"
    CONTENT_UNIT ||--o{ JOB : "tracked by"
    SOURCE ||--o{ JOB : "tracked by"

    SOURCE {
        uuid id PK
        string name
        string description
        enum source_type "PODCAST | CONFLUENCE | TEAMS | GITHUB | FILE_UPLOAD"
        string origin_url
        string icon_url
        jsonb config "source-specific settings"
        enum sync_status "IDLE | SYNCING | ERROR"
        timestamp last_synced_at
        timestamp created_at
    }

    CONTENT_UNIT {
        uuid id PK
        uuid source_id FK
        string title
        enum content_type "AUDIO | PAGE | MESSAGE_THREAD | CODE_FILE | TEXT_FILE"
        string external_id
        string external_url
        enum status "PENDING | FETCHING | TRANSCRIBING | EXTRACTING | EMBEDDING | INDEXED | ERROR"
        text raw_text
        jsonb metadata "type-specific fields"
        timestamp published_at
        timestamp created_at
    }

    CONTENT_FRAGMENT {
        bigint id PK
        uuid content_unit_id FK
        enum fragment_type "TIMED_SEGMENT | SECTION | MESSAGE | CODE_BLOCK | TEXT_BLOCK"
        int sequence_index
        text text
        jsonb location "type-specific location"
    }

    CONVERSATION {
        uuid id PK
        string title
        timestamp created_at
        timestamp updated_at
    }

    MESSAGE {
        uuid id PK
        uuid conversation_id FK
        enum role "USER | ASSISTANT"
        text content
        jsonb sources "polymorphic per source_type"
        string audio_path
        timestamp created_at
    }

    JOB {
        uuid id PK
        enum type "FETCH | TRANSCRIBE | EXTRACT | EMBED | SYNC"
        uuid entity_id
        enum status "PENDING | RUNNING | COMPLETED | FAILED"
        double progress
        string error_message
        timestamp created_at
        timestamp completed_at
    }
```

---

## 6. Service Communication Overview

```mermaid
sequenceDiagram
    participant User
    participant Frontend as Next.js :3000
    participant API as Spring Boot + Spring AI :8080
    participant ConnReg as ConnectorRegistry
    participant Sidecar as Python Sidecar :8100
    participant Ollama as Ollama :11434
    participant DB as PostgreSQL
    participant ChromaDB

    Note over User,ChromaDB: === Content Import Flow (Podcast Example) ===

    User->>Frontend: Add source (paste URL)
    Frontend->>API: POST /api/v1/sources
    API->>ConnReg: Route to connector by source_type
    ConnReg->>API: PodcastConnector selected
    API->>DB: Save source + content units metadata
    API-->>Frontend: {job_id, source_id}

    loop For each content unit (background)
        API->>DB: Update status → FETCHING
        API->>API: Fetch content (connector-specific)

        alt Audio source (Podcast)
            API->>DB: Update status → TRANSCRIBING
            API->>Sidecar: POST /transcribe (audio file)
            Sidecar-->>API: [{start, end, text}, ...]
            API->>DB: Save content fragments
        else Text source (Confluence, GitHub, Teams)
            API->>DB: Update status → EXTRACTING
            API->>API: Extract text (connector-specific)
            API->>DB: Save content fragments
        end

        API->>DB: Update status → EMBEDDING
        API->>API: Chunk content (uses TokenTextSplitter)
        API->>API: Add documents to Vector Store
        Note right of API: ChromaDbVectorStore uses<br/>OllamaEmbeddingModel to generate<br/>and store embeddings in ChromaDB
        API->>DB: Update status → INDEXED
    end

    Frontend->>API: GET /api/v1/jobs/{id} (polling)
    API-->>Frontend: {progress: 0.75, status: "running"}

    Note over User,ChromaDB: === RAG Query Flow ===

    User->>Frontend: Ask question
    Frontend->>API: POST /api/v1/query (SSE)
    API->>API: Retrieve relevant documents
    Note right of API: Uses RetrievalAugmentationAdvisor which<br/>embeds the query and searches the VectorStore<br/>(optional source_types filter)
    API-->>API: Ranked chunks + metadata

    API->>API: Build RAG prompt with context
    API->>Ollama: Stream chat completion

    loop Token streaming
        Ollama-->>API: Token chunk
        API-->>Frontend: SSE event: {type: "token", content: "..."}
        Frontend-->>User: Display token
    end

    API-->>Frontend: SSE event: {type: "sources", ...}
    API-->>Frontend: SSE event: {type: "done"}
    API->>DB: Save conversation + message

    Note over User,ChromaDB: === TTS Flow (optional) ===

    User->>Frontend: Click "Listen to answer"
    Frontend->>API: POST /api/v1/query/{id}/tts
    API->>Sidecar: POST /tts {text}
    Sidecar-->>API: audio/wav
    API-->>Frontend: {audio_url}
    Frontend-->>User: Play audio
```

---

## 7. Tech Stack Layer Diagram

```mermaid
graph LR
    subgraph Presentation
        NextJS[Next.js 14]
        Tailwind[Tailwind CSS]
        ShadcnUI[shadcn/ui]
        TanStack[TanStack Query v5]
    end

    subgraph API_Layer["API Layer (Java 25)"]
        SpringBoot[Spring Boot 4.0]
        SpringAI[Spring AI]
        ConnRegistry[ConnectorRegistry]
        JPA[Spring Data JPA]
        Flyway[Flyway Migrations]
        Log4j2[Log4j2 2.25.3]
    end

    subgraph Connectors["Source Connectors"]
        PodcastConn[Podcast]
        ConfluenceConn[Confluence]
        TeamsConn[MS Teams]
        GitHubConn[GitHub]
        FileConn[File Upload]
    end

    subgraph ML_Layer["ML Layer (Python 3.11+)"]
        FastAPI[FastAPI]
        Whisper[faster-whisper]
        PiperTTS[Piper TTS]
    end

    subgraph Data_Layer["Data Layer"]
        PostgreSQL[(PostgreSQL)]
        ChromaDB[(ChromaDB)]
        FileStore[(File System)]
    end

    subgraph Inference
        Ollama[Ollama + Llama 3.1]
    end

    Presentation --> API_Layer
    API_Layer --> Connectors
    API_Layer --> ML_Layer
    API_Layer --> Data_Layer
    API_Layer --> Inference
    Connectors -->|Audio sources| ML_Layer
    ML_Layer --> Data_Layer

    style NextJS fill:#3b82f6,color:#fff
    style SpringBoot fill:#16a34a,color:#fff
    style ConnRegistry fill:#f97316,color:#fff
    style PodcastConn fill:#f97316,color:#fff
    style ConfluenceConn fill:#f97316,color:#fff
    style TeamsConn fill:#f97316,color:#fff
    style GitHubConn fill:#f97316,color:#fff
    style FileConn fill:#f97316,color:#fff
    style FastAPI fill:#eab308,color:#000
    style Ollama fill:#a855f7,color:#fff
    style PostgreSQL fill:#336791,color:#fff
    style ChromaDB fill:#ff6b6b,color:#fff
```

---

## Color Legend

| Color | Meaning |
|-------|---------|
| Blue | Frontend / User-facing |
| Green | Java / Spring Boot API |
| Orange | Source Connectors |
| Yellow | Python ML Sidecar (audio only) |
| Purple | LLM / Vector inference |
| Gray | Storage / Persistence |
