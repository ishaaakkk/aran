-- ===========================================================================
--  V6__create_data_repository_table.sql  —  Data Repositories Schema
--  Implements SINGLE_TABLE inheritance with index and foreign keys.
-- ===========================================================================

CREATE TABLE data_repository (
    id                UUID PRIMARY KEY NOT NULL,
    project_id        UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    tenant_id         UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    type              VARCHAR(64) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    description       TEXT,

    -- Relational DB & NoSQL Engine Fields
    engine            VARCHAR(64),
    host              VARCHAR(255),
    port              INTEGER,
    database_name     VARCHAR(255),
    username          VARCHAR(255),
    password          VARCHAR(255),
    schema_name       VARCHAR(255),

    -- Object Storage Fields
    provider          VARCHAR(64),
    endpoint          VARCHAR(1024),
    bucket_name       VARCHAR(255),
    access_key        VARCHAR(255),
    secret_key        VARCHAR(255),
    region            VARCHAR(64),

    -- File System Fields
    root_path         VARCHAR(1024),

    -- NoSQL specific Fields
    connection_string VARCHAR(2048),
    collection_name   VARCHAR(255),

    -- Key-Value specific Fields
    database_index    INTEGER,

    -- Web API Fields
    base_url          VARCHAR(1024),
    auth_type         VARCHAR(64),
    credentials       TEXT,

    CONSTRAINT uq_data_repository_project_name UNIQUE (project_id, name)
);

CREATE INDEX idx_data_repository_project ON data_repository(project_id);
CREATE INDEX idx_data_repository_tenant ON data_repository(tenant_id);
