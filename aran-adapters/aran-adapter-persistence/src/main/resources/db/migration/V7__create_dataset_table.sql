-- ===========================================================================
--  V7__create_dataset_table.sql  —  Datasets Schema
--  Implements strict tenant isolation and relational constraints.
-- ===========================================================================

CREATE TABLE dataset (
    id             UUID PRIMARY KEY NOT NULL,
    repository_id  UUID NOT NULL REFERENCES data_repository(id) ON DELETE CASCADE,
    tenant_id      UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    CONSTRAINT uq_dataset_repository_name UNIQUE (repository_id, name)
);

CREATE INDEX idx_dataset_repository ON dataset(repository_id);
CREATE INDEX idx_dataset_tenant ON dataset(tenant_id);
