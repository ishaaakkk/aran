-- API tokens: a long-lived, individually revocable credential for programmatic access.
--
-- Keycloak's browser flow is the only production-safe way into Aran today (the ROPC grant in
-- http/auth.http is marked dev-only, and is deprecated in OAuth 2.1), so nothing can be scripted:
-- no notebook, no scheduled re-materialization, no CI hook, no CLI.
--
-- token_hash, never the token. The secret is shown once at creation and is unrecoverable
-- afterwards; a leaked database gives an attacker hashes, not credentials. SHA-256 rather than
-- bcrypt/argon2 on purpose: this is a 256-bit random value, not a user-chosen password, so it has
-- no brute-forceable structure and a per-request KDF would only add latency to every API call.
CREATE TABLE api_token (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    -- Hex SHA-256 of the full token string.
    token_hash CHAR(64) NOT NULL,
    -- The displayable head of the token ("aran_pat_3f2a…"), so the UI can tell two tokens apart
    -- in a list without ever storing enough to authenticate with.
    token_prefix VARCHAR(24) NOT NULL,
    -- READ (safe methods only) or FULL. Read-only is the default in the service.
    scope VARCHAR(16) NOT NULL DEFAULT 'READ',
    expires_at TIMESTAMPTZ,
    -- Set on use, so a token nobody has touched in months is visible as such.
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, user_id, name)
);

-- Authentication looks a token up by hash on every request that presents one, so this index is
-- on the hot path, not a convenience.
CREATE UNIQUE INDEX idx_api_token_hash ON api_token (token_hash);
CREATE INDEX idx_api_token_owner ON api_token (tenant_id, user_id);
