CREATE TABLE integration_model (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL, project_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, project_id)
);
CREATE TABLE model_source (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL,
    model_id UUID NOT NULL REFERENCES integration_model(id) ON DELETE CASCADE,
    dataset_id UUID NOT NULL,
    class_iri TEXT NOT NULL,
    wrapper_sql TEXT,
    UNIQUE (model_id, dataset_id, class_iri)
);
CREATE TABLE model_mapping (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL,
    model_id UUID NOT NULL REFERENCES integration_model(id) ON DELETE CASCADE,
    kind VARCHAR(24) NOT NULL,           -- mirror the operator's alignment type strings EXACTLY
    source_iri TEXT NOT NULL,            -- iriA in the old payload
    target_iri TEXT NOT NULL,            -- iriB in the old payload
    label VARCHAR(200),                  -- global name; for kind=join keep the packed form for v1
    status VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',   -- SUGGESTED | ACCEPTED | REJECTED
    evidence JSONB,
    author UUID, comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (model_id, kind, source_iri, target_iri)
);
CREATE TABLE model_constraint (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL,
    model_id UUID NOT NULL REFERENCES integration_model(id) ON DELETE CASCADE,
    class_iri TEXT NOT NULL,
    expression TEXT NOT NULL,            -- SQL boolean predicate over the class's global columns
    enforced BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_model_mapping_model ON model_mapping (model_id);
CREATE INDEX idx_model_source_model ON model_source (model_id);
