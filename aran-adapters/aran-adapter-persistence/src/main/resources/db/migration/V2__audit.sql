-- ===========================================================================
--  V2__audit.sql  —  Audit log
--
--  Append-only record of mutating actions, written in the same transaction
--  as the action it audits (see AuditService). Retrofitting an audit trail
--  is genuinely painful — this migration lands with the first feature, not
--  after it.
--
--  In production, REVOKE UPDATE, DELETE on audit_event from the application
--  role. The application path never updates or deletes audit rows; the only
--  reason to allow it would be human meddling, which is precisely what an
--  audit trail exists to deter.
-- ===========================================================================
CREATE TABLE audit_event (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    actor_id     UUID,                              -- null only for system actions
    action       VARCHAR(128) NOT NULL,             -- stable identifier, e.g. project.created
    target_type  VARCHAR(64)  NOT NULL,             -- e.g. project
    target_id    UUID,                              -- null for actions not bound to one aggregate
    metadata     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_event        PRIMARY KEY (id),
    CONSTRAINT fk_audit_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_audit_event_actor  FOREIGN KEY (actor_id)  REFERENCES app_user (id)
);

-- Reads are tenant-scoped and chronological. A composite index on
-- (tenant_id, occurred_at DESC) supports the "recent events for this tenant"
-- query without a separate index per column.
CREATE INDEX idx_audit_event_tenant_occurred
    ON audit_event (tenant_id, occurred_at DESC);

-- Action-targeted lookups ("everything that happened to project X") benefit
-- from a partial index where target_id is set.
CREATE INDEX idx_audit_event_target
    ON audit_event (target_type, target_id)
    WHERE target_id IS NOT NULL;
