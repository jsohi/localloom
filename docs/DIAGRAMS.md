# LocalLoom — Architecture & Flow Diagrams

## 1. System Architecture

```mermaid
graph TB
    subgraph User Machine
        subgraph Frontend
            UI[Next.js 14<br/>:3000]
        end

        subgraph API["API Server (Java 25 / Spring Boot 4.0.4)"]
            Controllers[REST Controllers]
            Services[Service Layer]
            Jobs[Job Scheduler<br/>@Async]
            OllamaClient[Ollama Client<br/>WebClient SSE]
            SidecarClient[ML Sidecar Client]
        end

        subgraph MLSidecar["ML Sidecar (Python / FastAPI)"]
            Transcribe["/transcribe<br/>Whisper"]
            Embed["/embed<br/>sentence-transformers"]
            Search["/search<br/>ChromaDB"]
            TTS["/tts<br/>Piper TTS"]
        end

        subgraph Storage
            DB[(PostgreSQL<br/>Metadata)]
            Chroma[(ChromaDB<br/>Vectors)]
            Files[(File Store<br/>Audio / TTS)]
        end

        subgraph External
            Ollama[Ollama<br/>:11434<br/>LLM Inference]
        end
    end

    UI -->|HTTP / SSE| Controllers
    Controllers --> Services
    Services --> Jobs
    Services --> OllamaClient
    Services --> SidecarClient
    Services --> DB
    Services --> Files

    OllamaClient -->|Streaming Chat| Ollama
    SidecarClient -->|HTTP| Transcribe
    SidecarClient -->|HTTP| Embed
    SidecarClient -->|HTTP| Search
    SidecarClient -->|HTTP| TTS

    Embed --> Chroma
    Search --> Chroma

    style UI fill:#3b82f6,color:#fff
    style Controllers fill:#16a34a,color:#fff
    style Services fill:#16a34a,color:#fff
    style Jobs fill:#16a34a,color:#fff
    style OllamaClient fill:#16a34a,color:#fff
    style SidecarClient fill:#16a34a,color:#fff
    style Transcribe fill:#eab308,color:#000
    style Embed fill:#eab308,color:#000
    style Search fill:#eab308,color:#000
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

    M --> N[Chunk transcript<br/>~500 tokens, 50 overlap]
    N --> O[POST /embed<br/>to Python Sidecar]
    O --> P[Generate embeddings<br/>all-MiniLM-L6-v2]
    P --> Q[Store vectors in<br/>ChromaDB]

    Q --> R[Mark Episode as INDEXED<br/>Job completed]

    style A fill:#3b82f6,color:#fff
    style B fill:#f59e0b,color:#000
    style K fill:#eab308,color:#000
    style L fill:#eab308,color:#000
    style O fill:#eab308,color:#000
    style P fill:#eab308,color:#000
    style Q fill:#a855f7,color:#fff
    style R fill:#16a34a,color:#fff

    subgraph Spring Boot
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
        R
    end

    subgraph Python Sidecar
        K
        L
        O
        P
        Q
    end
```

---

## 3. RAG Query Pipeline

```mermaid
flowchart TD
    A[User asks question] --> B[Spring Boot receives<br/>POST /api/v1/query]

    B --> C[Send query to<br/>Python Sidecar<br/>POST /search]

    C --> D[Embed question<br/>all-MiniLM-L6-v2]
    D --> E[ChromaDB similarity search<br/>top_k=5, optional filters]
    E --> F[Return ranked chunks<br/>with episode metadata]

    F --> G[Build RAG prompt]

    G --> H["System: Answer using ONLY<br/>provided context. Cite sources."]
    G --> I["Context: 5 chunks with<br/>[Episode: title, timestamp]"]
    G --> J["User: original question"]

    H --> K[Send to Ollama<br/>POST /api/chat<br/>stream: true]
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
    style C fill:#eab308,color:#000
    style D fill:#eab308,color:#000
    style E fill:#a855f7,color:#fff
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
    participant API as Spring Boot :8080
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
        API->>API: Chunk transcript (500 tokens)
        API->>Sidecar: POST /embed (chunks + metadata)
        Sidecar->>ChromaDB: Upsert embeddings
        Sidecar-->>API: {count: N}
        API->>DB: Update status → INDEXED
    end

    Frontend->>API: GET /api/v1/jobs/{id} (polling)
    API-->>Frontend: {progress: 0.75, status: "running"}

    Note over User,ChromaDB: === RAG Query Flow ===

    User->>Frontend: Ask question
    Frontend->>API: POST /api/v1/query (SSE)
    API->>Sidecar: POST /search {query, filters}
    Sidecar->>ChromaDB: Similarity search (top_k=5)
    ChromaDB-->>Sidecar: Ranked chunks + metadata
    Sidecar-->>API: Search results

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
        JPA[Spring Data JPA]
        Flyway[Flyway Migrations]
        Log4j2[Log4j2 2.25.3]
        WebClient[WebClient SSE]
    end

    subgraph ML_Layer["ML Layer (Python 3.11+)"]
        FastAPI[FastAPI]
        Whisper[faster-whisper]
        SentenceT[sentence-transformers]
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
