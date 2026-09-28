-- ===========================================================================
--  V3__licensing.sql  —  Cloud-mode licensing
--
--  One license row per tenant (PK = tenant_id). The DatabaseLicenseProvider
--  reads from here; DatabaseLicenseAdminPort writes to here. Self-hosted
--  deployments don't apply this migration's data (their LicenseProvider
--  reads from a signed token), but the table still exists — Flyway runs the
--  same migrations everywhere. The self-hosted code path simply ignores it.
--
--  plan → default-limits mapping is configuration (application.yml
--  aran.licensing.plans.*), not data. Override columns let a platform admin
--  negotiate a non-standard arrangement without inventing a new plan name.
-- ===========================================================================
CREATE TABLE license (
    tenant_id              UUID         NOT NULL,
    plan                   VARCHAR(32)  NOT NULL,
    status                 VARCHAR(32)  NOT NULL,
    max_users_override     INTEGER,                              -- null → use plan default
    max_projects_override  INTEGER,                              -- null → use plan default
    expires_at             TIMESTAMPTZ,                          -- null → no expiry
    issued_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_license          PRIMARY KEY (tenant_id),
    CONSTRAINT fk_license_tenant   FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE,
    CONSTRAINT ck_license_plan     CHECK (plan   IN ('FREE', 'PRO', 'ENTERPRISE')),
    CONSTRAINT ck_license_status   CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_license_overrides_positive
        CHECK ((max_users_override    IS NULL OR max_users_override    >= 0)
           AND (max_projects_override IS NULL OR max_projects_override >= 0))
);

-- The seed tenant from V1 / docker-compose / README needs a license, or every
-- request through it would 402. FREE plan, no expiry — typical demo profile.
-- Idempotent so re-running on an existing DB does not break.
INSERT INTO license (tenant_id, plan, status)
SELECT id, 'FREE', 'ACTIVE' FROM tenant
WHERE id = '00000000-0000-0000-0000-000000000001'
ON CONFLICT (tenant_id) DO NOTHING;
