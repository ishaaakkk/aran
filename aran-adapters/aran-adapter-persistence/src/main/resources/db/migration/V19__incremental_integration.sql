-- Incremental integration run + mapping-application state (Phase 4).
-- Algorithm-execution state, kept separate from the editorial model_mapping.status.

ALTER TABLE integration_model
    ADD COLUMN active_integration_run_id UUID,
    ADD COLUMN rebuild_required BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE integration_run (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    model_id UUID NOT NULL REFERENCES integration_model(id) ON DELETE CASCADE,
    mode VARCHAR(16) NOT NULL,                 -- REBUILD | INCREMENTAL
    status VARCHAR(16) NOT NULL,               -- RUNNING | DRAFT | PROMOTED | FAILED
    base_version_id UUID,                       -- G_I^old for an incremental run
    dataset_id UUID,                            -- the new source, for an incremental run
    draft_version_id UUID,
    promoted_version_id UUID,
    error_code TEXT,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE model_mapping_application (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    model_id UUID NOT NULL REFERENCES integration_model(id) ON DELETE CASCADE,
    mapping_id UUID NOT NULL REFERENCES model_mapping(id) ON DELETE CASCADE,
    state VARCHAR(16) NOT NULL,                 -- PENDING | APPLIED | DEFERRED
    run_id UUID REFERENCES integration_run(id) ON DELETE SET NULL,
    version_id UUID,
    reason TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (mapping_id)                          -- one application row per mapping
);

-- integration_model may now point at the run whose draft is active.
ALTER TABLE integration_model
    ADD CONSTRAINT fk_integration_model_active_run
    FOREIGN KEY (active_integration_run_id) REFERENCES integration_run(id) ON DELETE SET NULL;

CREATE INDEX idx_integration_run_model ON integration_run (model_id);
CREATE INDEX idx_mapping_application_model ON model_mapping_application (model_id);
CREATE INDEX idx_mapping_application_state ON model_mapping_application (model_id, state);
