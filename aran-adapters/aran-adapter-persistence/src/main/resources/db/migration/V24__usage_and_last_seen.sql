-- What the admin surface needs to answer "who is using this, and how much".
--
-- Two things, deliberately separate from audit_event. Audit answers "who did what" and is
-- governance-flavoured: append-only, individually meaningful, read when something went wrong.
-- Usage answers "how much" and is metering-flavoured: high-volume, meaningful in aggregate,
-- read on a dashboard. Putting query executions in audit_event would drown the events an
-- administrator actually needs to see, and putting member changes in usage would make the
-- numbers meaningless.

-- ---------------------------------------------------------------------------
--  app_user.last_seen_at — the single most-asked question of any admin UI.
--
--  "Who is actually using this" is unanswerable without it, and it is what turns a member
--  list from a directory into something an owner can act on. Nullable: a user who has been
--  created but never signed in has genuinely never been seen, and 'now' would be a lie.
-- ---------------------------------------------------------------------------
ALTER TABLE app_user ADD COLUMN last_seen_at TIMESTAMPTZ;

COMMENT ON COLUMN app_user.last_seen_at IS
    'Last authenticated request, written at most once per minute per user (see IdentityService).';

-- ---------------------------------------------------------------------------
--  usage_event — one row per billable/attributable action.
-- ---------------------------------------------------------------------------
CREATE TABLE usage_event (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    -- Null for work no human asked for: scheduled refreshes, system jobs. Deliberately
    -- nullable rather than defaulted to a service account, because "nobody" and "a robot
    -- acting for nobody" are the same thing and inventing an actor would corrupt per-user
    -- attribution, which is the whole point of this table.
    user_id     UUID,
    -- QUERY | EXPORT | JDBC | MATERIALIZE | UPLOAD. A string rather than an enum type so a
    -- new kind is an application change, not a migration.
    kind        VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- All nullable: not every kind knows every measure, and a 0 that means "unknown" is the
    -- kind of thing that quietly becomes a wrong number on a dashboard.
    duration_ms BIGINT,
    row_count   BIGINT,
    byte_count  BIGINT,
    metadata    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT pk_usage_event        PRIMARY KEY (id),
    CONSTRAINT fk_usage_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    -- ON DELETE SET NULL, not CASCADE: removing a person must not silently reduce the
    -- tenant's recorded consumption. The work happened.
    CONSTRAINT fk_usage_event_user   FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE SET NULL
);

-- The dashboard's two queries: "this tenant, recently" and "this user, recently".
CREATE INDEX idx_usage_event_tenant_time ON usage_event (tenant_id, occurred_at DESC);
CREATE INDEX idx_usage_event_user_time   ON usage_event (tenant_id, user_id, occurred_at DESC);
