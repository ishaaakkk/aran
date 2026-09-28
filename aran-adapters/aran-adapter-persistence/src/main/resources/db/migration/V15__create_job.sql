-- Durable jobs: the async backbone.
--
-- Every long-running unit of work (operator invocation, bootstrap, compile)
-- gets a row here so its status, staged progress, result and error survive a
-- request, a page reload, and an application restart.
--
-- status is a String constant, not a Postgres enum, matching the convention
-- already used by dataset.bootstrap_status:
--   PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED
CREATE TABLE job (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    project_id       UUID,
    job_type         VARCHAR(64)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    parameters       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    progress_stage   VARCHAR(160),
    progress_percent INT,
    result           JSONB,
    error_code       VARCHAR(64),
    error_message    TEXT,
    created_by       UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ
);

CREATE INDEX idx_job_tenant_created ON job (tenant_id, created_at DESC);
CREATE INDEX idx_job_status ON job (tenant_id, status);
