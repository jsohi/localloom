-- V1__create_schema.sql
-- Complete schema for LocalLoom

CREATE SEQUENCE content_fragment_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE sources (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    source_type     VARCHAR(50)  NOT NULL,
    origin_url      TEXT,
    icon_url        TEXT,
    config          JSONB,
    sync_status     VARCHAR(50)  NOT NULL DEFAULT 'IDLE',
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_sources PRIMARY KEY (id)
);

CREATE INDEX idx_sources_source_type ON sources (source_type);

CREATE TABLE content_units (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    source_id    UUID         NOT NULL,
    title        VARCHAR(1000),
    content_type VARCHAR(50),
    external_id  VARCHAR(500),
    external_url TEXT,
    status       VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    raw_text     TEXT,
    metadata     JSONB,
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_content_units PRIMARY KEY (id),
    CONSTRAINT fk_content_units_source FOREIGN KEY (source_id) REFERENCES sources (id) ON DELETE CASCADE
);

CREATE INDEX idx_content_units_source_id ON content_units (source_id);
CREATE INDEX idx_content_units_status    ON content_units (status);

-- Partial unique index: prevents duplicate INDEXED episodes within a source
CREATE UNIQUE INDEX idx_content_units_source_external_indexed
  ON content_units (source_id, external_id)
  WHERE external_id IS NOT NULL AND status = 'INDEXED';

CREATE TABLE content_fragments (
    id              BIGINT      NOT NULL DEFAULT nextval('content_fragment_seq'),
    content_unit_id UUID        NOT NULL,
    fragment_type   VARCHAR(50),
    sequence_index  INT         NOT NULL,
    text            TEXT,
    location        JSONB,
    CONSTRAINT pk_content_fragments PRIMARY KEY (id),
    CONSTRAINT fk_content_fragments_unit FOREIGN KEY (content_unit_id) REFERENCES content_units (id) ON DELETE CASCADE
);

CREATE INDEX idx_content_fragments_content_unit_id ON content_fragments (content_unit_id);

CREATE TABLE jobs (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    type          VARCHAR(50)  NOT NULL,
    entity_id     UUID         NOT NULL,
    entity_type   VARCHAR(50)  NOT NULL,
    status        VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    progress      DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    completed_at  TIMESTAMPTZ,
    CONSTRAINT pk_jobs PRIMARY KEY (id)
);

CREATE INDEX idx_jobs_entity_id_type ON jobs (entity_id, entity_type);
CREATE INDEX idx_jobs_status         ON jobs (status);

CREATE TABLE conversations (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    title      VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_conversations PRIMARY KEY (id)
);

CREATE TABLE messages (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    content         TEXT         NOT NULL,
    sources         JSONB,
    audio_path      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_messages PRIMARY KEY (id),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_conversation_id ON messages (conversation_id);
