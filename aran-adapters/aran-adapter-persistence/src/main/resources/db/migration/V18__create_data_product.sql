-- M8: data products — a named, versioned OQL view over a pinned schema version,
-- materialized to a parquet snapshot in the lake with freshness metadata.
CREATE TABLE data_product (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL, project_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL, description TEXT,
    oql TEXT NOT NULL,
    schema_version_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',   -- DRAFT|MATERIALIZING|READY|STALE|FAILED
    snapshot_id VARCHAR(160), row_count BIGINT,
    materialized_at TIMESTAMPTZ, last_error TEXT,
    created_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, project_id, name)
);
