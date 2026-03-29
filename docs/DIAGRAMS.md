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
            SpringAI[Spring AI<br/>ChatClient + RAG Advisor]
            EmbedModel[OllamaEmbeddingModel]
            VectorStore[ChromaDbVectorStore]
            Chunker[TokenTextSplitter]
            SidecarClient[ML Sidecar Client]
        end

        subgraph MLSidecar["ML Sidecar (Python / FastAPI)"]
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
    Services --> SpringAI
    Services --> SidecarClient
    Services --> DB
    Services --> Files

    SpringAI -->|Streaming Chat| Ollama
    EmbedModel -->|Embed Text| Ollama
    VectorStore --> Chroma
    Chunker --> EmbedModel
    SpringAI --> VectorStore

    SidecarClient -->|HTTP| Transcribe
    SidecarClient -->|HTTP| TTS

    style UI fill:#3b82f6,color:#fff
    style Controllers fill:#16a34a,color:#fff
    style Services fill:#16a34a,color:#fff
    style Jobs fill:#16a34a,color:#fff
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

## 2. Podcast Import Pipeline

```mermaid
flowchart TD
    A[User submits Podcast URL] --> B{Detect URL Type}

    B -->|YouTube| C[yt-dlp: extract metadata]
    B -->|Apple Podcasts| D[iTunes Lookup API<br/>resolve RSS feed]
    B -->|Spotify| E[oEmbed API → name<br/>→ iTunes Search → RSS]
    B -->|Raw RSS| F[Parse RSS directly]

    C --> G[Extract episode list<br/>Save metadata to DB]
    D --> F
    E --> F
    F --> G

    G --> H[Download Audio<br/>yt-dlp or HTTP client]
    H --> I[Convert to 16kHz WAV<br/>via ffmpeg]
    I --> J[Save to data/audio/]

    J --> K[POST /transcribe<br/>to Python Sidecar]
    K --> L[Whisper large-v3-turbo<br/>generates timestamped segments]
    L --> M[Save transcript segments<br/>to PostgreSQL]

    M --> N[Spring AI TokenTextSplitter<br/>~500 tokens, 50 overlap]
    N --> O[Spring AI OllamaEmbeddingModel<br/>generate embeddings]
    O --> P[Spring AI ChromaDbVectorStore<br/>store vectors]

    P --> R[Mark Episode as INDEXED<br/>Job completed]

    style A fill:#3b82f6,color:#fff
    style B fill:#f59e0b,color:#000
    style K fill:#eab308,color:#000
    style L fill:#eab308,color:#000
    style N fill:#16a34a,color:#fff
    style O fill:#16a34a,color:#fff
    style P fill:#16a34a,color:#fff
    style R fill:#16a34a,color:#fff

    subgraph Spring Boot + Spring AI
        B
        C
        D
        E
        F
        G
        H
        I
        J
        M
        N
        O
        P
        R
    end

    subgraph Python Sidecar
        K
        L
    end
```

---

## 3. RAG Query Pipeline

```mermaid
flowchart TD
    A[User asks question] --> B[Spring Boot receives<br/>POST /api/v1/query]

    B --> C[Spring AI<br/>RetrievalAugmentationAdvisor]

    C --> D[OllamaEmbeddingModel<br/>embed question]
    D --> E[ChromaDbVectorStore<br/>similarity search top_k=5]
    E --> F[Return ranked chunks<br/>with episode metadata]

    F --> G[Spring AI builds<br/>augmented prompt]

    G --> H["System: Answer using ONLY<br/>provided context. Cite sources."]
    G --> I["Context: 5 chunks with<br/>[Episode: title, timestamp]"]
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
    style D fill:#16a34a,color:#fff
    style E fill:#16a34a,color:#fff
    style K fill:#a855f7,color:#fff
    style L fill:#16a34a,color:#fff
    style M fill:#3b82f6,color:#fff
    style P fill:#eab308,color:#000
    style Q fill:#eab308,color:#000
    style R fill:#3b82f6,color:#fff
```

---

## 4. Episode Status Lifecycle

```mermaid
stateDiagram-v2
    [*] --> PENDING: Episode discovered

    PENDING --> DOWNLOADING: Download job starts
    DOWNLOADING --> TRANSCRIBING: Audio saved to disk
    TRANSCRIBING --> EMBEDDING: Transcript segments saved
    EMBEDDING --> INDEXED: Vectors stored in ChromaDB

    DOWNLOADING --> ERROR: Download failed
    TRANSCRIBING --> ERROR: Whisper failed
    EMBEDDING --> ERROR: Embedding failed

    ERROR --> PENDING: Retry requested

    INDEXED --> [*]
```

---

## 5. Entity Relationship Diagram

```mermaid
erDiagram
    PODCAST ||--o{ EPISODE : "has many"
    EPISODE ||--o{ TRANSCRIPT_SEGMENT : "has many"
    CONVERSATION ||--o{ MESSAGE : "has many"
    EPISODE ||--o{ JOB : "tracked by"
    PODCAST ||--o{ JOB : "tracked by"

    PODCAST {
        uuid id PK
        string title
        string author
        string description
        string artwork_url
        string feed_url
        enum source_type "RSS | YOUTUBE"
        string source_url
        timestamp created_at
    }

    EPISODE {
        uuid id PK
        uuid podcast_id FK
        string title
        string description
        timestamp published_at
        string audio_url
        string audio_path
        int duration_seconds
        enum status "PENDING | DOWNLOADING | TRANSCRIBING | EMBEDDING | INDEXED | ERROR"
        text transcript_text
        timestamp created_at
    }

    TRANSCRIPT_SEGMENT {
        bigint id PK
        uuid episode_id FK
        double start_time
        double end_time
        text text
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
        json sources
        string audio_path
        timestamp created_at
    }

    JOB {
        uuid id PK
        enum type "DOWNLOAD | TRANSCRIBE | EMBED"
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
    participant Sidecar as Python Sidecar :8100
    participant Ollama as Ollama :11434
    participant DB as PostgreSQL
    participant ChromaDB

    Note over User,ChromaDB: === Podcast Import Flow ===

    User->>Frontend: Paste podcast URL
    Frontend->>API: POST /api/v1/podcasts/import
    API->>DB: Save podcast + episodes metadata
    API-->>Frontend: {job_id, podcast_id}

    loop For each episode (background)
        API->>API: Download audio (yt-dlp / HTTP)
        API->>DB: Update status → DOWNLOADING
        API->>Sidecar: POST /transcribe (audio file)
        Sidecar-->>API: [{start, end, text}, ...]
        API->>DB: Save transcript segments
        API->>DB: Update status → TRANSCRIBING
        API->>API: Chunk transcript (~500 tokens)
        API->>Ollama: POST /api/embed (generate embeddings)
        Ollama-->>API: Embedding vectors
        API->>ChromaDB: Store document embeddings
        API->>DB: Update status → INDEXED
    end

    Frontend->>API: GET /api/v1/jobs/{id} (polling)
    API-->>Frontend: {progress: 0.75, status: "running"}

    Note over User,ChromaDB: === RAG Query Flow ===

    User->>Frontend: Ask question
    Frontend->>API: POST /api/v1/query (SSE)
    API->>Ollama: POST /api/embed (embed question)
    Ollama-->>API: Query vector
    API->>ChromaDB: Similarity search (top_k=5)
    ChromaDB-->>API: Ranked chunks + metadata

    API->>API: Build RAG prompt with context
    API->>Ollama: POST /api/chat (stream: true)

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
        SpringBoot[Spring Boot 4.0.4]
        SpringAI2[Spring AI]
        JPA[Spring Data JPA]
        Flyway[Flyway Migrations]
        Log4j2[Log4j2 2.25.3]
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
    API_Layer --> ML_Layer
    API_Layer --> Data_Layer
    API_Layer --> Inference
    ML_Layer --> Data_Layer

    style NextJS fill:#3b82f6,color:#fff
    style SpringBoot fill:#16a34a,color:#fff
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
| Yellow | Python ML Sidecar |
| Purple | LLM / Vector inference |
| Gray | Storage / Persistence |
