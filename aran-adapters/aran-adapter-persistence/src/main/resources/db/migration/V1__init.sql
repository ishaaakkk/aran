-- ===========================================================================
--  V1__init.sql  —  Aran baseline schema
--  Applied automatically by Flyway on application startup.
--
--  Multi-tenancy model: SHARED SCHEMA. A single database, a single set of
--  tables; tenant-owned rows carry a `tenant_id` discriminator. Tenant
--  isolation on those tables is enforced at runtime by a Hibernate @Filter
--  (see ProjectEntity / JpaProjectRepository) — this schema just provides the
--  column and the supporting indexes.
--
--  Worked example domain: Tenant (organization) -> Membership -> User, and
--  Tenant -> Project -> ProjectMember (explicit project visibility).
-- ===========================================================================

-- ---------------------------------------------------------------------------
--  tenant — the organization; the top-level isolation boundary.
-- ---------------------------------------------------------------------------
CREATE TABLE tenant (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    slug        VARCHAR(64)  NOT NULL,
    name        VARCHAR(256) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tenant       PRIMARY KEY (id),
    CONSTRAINT uq_tenant_slug  UNIQUE (slug)
);

-- ---------------------------------------------------------------------------
--  app_user — a LOCAL PROJECTION of the Keycloak user. Keycloak remains the
--  source of truth for authentication; `subject` is the JWT `sub` claim and
--  the join key back to the identity provider. Not tenant-owned: a user
--  exists independently of any tenant and may belong to several.
--  ("app_user" rather than "user" — "user" is a reserved word in PostgreSQL.)
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    subject       VARCHAR(255) NOT NULL,
    email         VARCHAR(320) NOT NULL,
    display_name  VARCHAR(256) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_app_user         PRIMARY KEY (id),
    CONSTRAINT uq_app_user_subject UNIQUE (subject)
);

-- ---------------------------------------------------------------------------
--  membership — the user<->tenant join, carrying the user's role WITHIN that
--  tenant. A user has at most one membership per tenant.
-- ---------------------------------------------------------------------------
CREATE TABLE membership (
    id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    tenant_id  UUID        NOT NULL,
    role       VARCHAR(32) NOT NULL,          -- TenantRole enum, stored as string
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_membership              PRIMARY KEY (id),
    CONSTRAINT uq_membership_user_tenant  UNIQUE (user_id, tenant_id),
    CONSTRAINT fk_membership_user         FOREIGN KEY (user_id)   REFERENCES app_user (id),
    CONSTRAINT fk_membership_tenant       FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT ck_membership_role         CHECK (role IN ('OWNER', 'MEMBER'))
);

-- "which tenants may this user act in?" — the post-login tenant selector query
CREATE INDEX idx_membership_user_id ON membership (user_id);

-- ---------------------------------------------------------------------------
--  project — a tenant-owned unit of work. `tenant_id` is the shared-schema
--  discriminator the Hibernate tenant filter keys on. `created_by` is
--  immutable and set server-side from the authenticated principal.
--  Project names are unique WITHIN a tenant, not globally.
-- ---------------------------------------------------------------------------
CREATE TABLE project (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    name         VARCHAR(256) NOT NULL,
    description  TEXT         NOT NULL DEFAULT '',
    created_by   UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_project             PRIMARY KEY (id),
    CONSTRAINT uq_project_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT fk_project_tenant      FOREIGN KEY (tenant_id)  REFERENCES tenant (id),
    CONSTRAINT fk_project_created_by  FOREIGN KEY (created_by) REFERENCES app_user (id)
);

-- every tenant-scoped query filters on tenant_id first
CREATE INDEX idx_project_tenant_id ON project (tenant_id);

-- ---------------------------------------------------------------------------
--  project_member — explicit project visibility ("Option A"). A user sees a
--  project only if a row here links them. The creator is always added at
--  creation time (enforced in ProjectService, same transaction as the insert).
-- ---------------------------------------------------------------------------
CREATE TABLE project_member (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    project_id  UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_project_member               PRIMARY KEY (id),
    CONSTRAINT uq_project_member_project_user  UNIQUE (project_id, user_id),
    CONSTRAINT fk_project_member_project       FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT fk_project_member_user          FOREIGN KEY (user_id)    REFERENCES app_user (id)
);

-- "which projects can this user see?" — the project list query
CREATE INDEX idx_project_member_user_id    ON project_member (user_id);
CREATE INDEX idx_project_member_project_id ON project_member (project_id);
