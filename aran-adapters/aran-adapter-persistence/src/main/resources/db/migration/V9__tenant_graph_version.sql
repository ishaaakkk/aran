-- ===========================================================================
--  V9__tenant_graph_version.sql  —  Graph Versioning Index
--  Applied automatically by Flyway on application startup.
-- ===========================================================================

CREATE TABLE tenant_graph_version (
    id             UUID         NOT NULL,
    tenant_id      UUID         NOT NULL,
    version_number INT          NOT NULL,
    status         VARCHAR(16)  NOT NULL,    -- 'DRAFT' | 'ACTIVE' | 'HISTORICAL'
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     UUID,
    promoted_at    TIMESTAMPTZ,
    notes          TEXT,
    CONSTRAINT pk_tenant_graph_version PRIMARY KEY (id),
    CONSTRAINT uq_tenant_graph_version_tenant_num UNIQUE (tenant_id, version_number),
    CONSTRAINT fk_tenant_graph_version_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE,
    CONSTRAINT ck_tenant_graph_version_status CHECK (status IN ('DRAFT', 'ACTIVE', 'HISTORICAL'))
);

CREATE INDEX idx_tenant_graph_version_tenant ON tenant_graph_version (tenant_id);
