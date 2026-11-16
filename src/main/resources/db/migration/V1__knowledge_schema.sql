-- Index-as-code: every table and index the service relies on is created here by Flyway at
-- startup, so a fresh environment can never be missing a search index.
-- ${embedding_dimensions} is a Flyway placeholder bound to rag.embedding.dimensions.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
    id              UUID PRIMARY KEY,
    space_id        VARCHAR(63)  NOT NULL,
    source_name     TEXT         NOT NULL,
    content_hash    CHAR(64)     NOT NULL,
    version         INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    content_type    TEXT,
    chunk_count     INT          NOT NULL DEFAULT 0,
    embedding_model TEXT,
    error           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (space_id, source_name, version)
);

-- The database itself guarantees at most one serving version per source.
CREATE UNIQUE INDEX documents_one_active ON documents (space_id, source_name) WHERE status = 'ACTIVE';
CREATE INDEX documents_active_hash ON documents (space_id, content_hash) WHERE status = 'ACTIVE';

CREATE TABLE parent_chunks (
    id          UUID PRIMARY KEY,
    document_id UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    space_id    VARCHAR(63) NOT NULL,
    ordinal     INT         NOT NULL,
    content     TEXT        NOT NULL
);
CREATE INDEX parent_chunks_document ON parent_chunks (document_id);

CREATE TABLE chunks (
    id          UUID PRIMARY KEY,
    document_id UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    parent_id   UUID        REFERENCES parent_chunks (id) ON DELETE CASCADE,
    space_id    VARCHAR(63) NOT NULL,
    ordinal     INT         NOT NULL,
    content     TEXT        NOT NULL,
    source_name TEXT        NOT NULL,
    page        INT,
    section     TEXT,
    -- Denormalised from documents.status so the ANN scan filters without a join.
    active      BOOLEAN     NOT NULL DEFAULT FALSE,
    embedding   VECTOR(${embedding_dimensions}) NOT NULL,
    tsv         TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', content)) STORED
);
CREATE INDEX chunks_document ON chunks (document_id);
CREATE INDEX chunks_space_active ON chunks (space_id) WHERE active;
CREATE INDEX chunks_embedding_hnsw ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunks_tsv ON chunks USING gin (tsv);

CREATE TABLE corpus_versions (
    space_id VARCHAR(63) PRIMARY KEY,
    version  BIGINT NOT NULL
);

CREATE TABLE answer_traces (
    id           UUID PRIMARY KEY,
    principal    TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    confidence   VARCHAR(8)  NOT NULL,
    grounded     BOOLEAN     NOT NULL,
    cached       BOOLEAN     NOT NULL,
    rating       SMALLINT,
    feedback     TEXT,
    payload      JSONB       NOT NULL
);
CREATE INDEX answer_traces_created ON answer_traces (created_at);
