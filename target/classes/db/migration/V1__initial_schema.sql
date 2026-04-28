-- V1__initial_schema.sql
-- Initial schema for Document Search Service

-- Core documents table
CREATE TABLE IF NOT EXISTS documents (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(64)  NOT NULL,
    title       VARCHAR(512) NOT NULL,
    content     TEXT         NOT NULL,
    author      VARCHAR(256),
    category    VARCHAR(128),
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Tags (one-to-many)
CREATE TABLE IF NOT EXISTS document_tags (
    document_id UUID        NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    tag         VARCHAR(128) NOT NULL
);

-- Metadata key-value pairs
CREATE TABLE IF NOT EXISTS document_metadata (
    document_id UUID         NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    meta_key    VARCHAR(256) NOT NULL,
    meta_value  TEXT
);

-- Tenant rate limit configuration
CREATE TABLE IF NOT EXISTS tenant_rate_limits (
    tenant_id                  VARCHAR(64) PRIMARY KEY,
    search_requests_per_minute BIGINT NOT NULL DEFAULT 100,
    index_requests_per_minute  BIGINT NOT NULL DEFAULT 50,
    max_documents              BIGINT NOT NULL DEFAULT 1000000,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for tenant-scoped queries (critical for multi-tenancy performance)
CREATE INDEX IF NOT EXISTS idx_documents_tenant_id        ON documents(tenant_id);
CREATE INDEX IF NOT EXISTS idx_documents_tenant_status    ON documents(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_documents_tenant_created   ON documents(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_documents_tenant_category  ON documents(tenant_id, category) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_document_tags_document_id  ON document_tags(document_id);
CREATE INDEX IF NOT EXISTS idx_document_meta_document_id  ON document_metadata(document_id);

-- Partial index for active documents only (most queries only touch ACTIVE docs)
CREATE INDEX IF NOT EXISTS idx_documents_active_tenant
    ON documents(tenant_id, created_at DESC)
    WHERE status = 'ACTIVE';

-- Auto-update updated_at trigger
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_documents_updated_at
    BEFORE UPDATE ON documents
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
