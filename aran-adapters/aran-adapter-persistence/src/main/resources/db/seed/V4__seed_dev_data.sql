-- ===========================================================================
--  V4__seed_dev_data.sql — Development seed data
--
--  Applied automatically when running with the 'dev' Spring profile
--  (see application.yml: spring.flyway.locations includes classpath:db/seed).
--  NOT applied in production, by two mechanisms: 'db/seed' is only in
--  spring.flyway.locations under the dev profile, and this directory is excluded
--  from aran-adapter-persistence's jar (see that module's build.gradle), so it is
--  absent from the classpath of any artifact built from it. Until 2026-08-27 only
--  the first was true and this comment claimed the second (audit MIGRATIONS-8) —
--  a dev profile activated by accident in a deployment would have seeded a demo
--  tenant with published passwords.
--
--  All INSERTs are ON CONFLICT DO NOTHING — safe to re-run on an existing DB.
--
--  What this seeds:
--    - The demo tenant (UUID matches the tenant_id in alice's Keycloak token)
--    - A FREE license for the demo tenant (prevents 402 on first request)
--    - Alice's app_user row (pre-seeded with a deterministic UUID and subject matching Keycloak)
--    - Alice's Membership row as OWNER of the demo tenant (no manual SQL required!)
-- ===========================================================================

-- Demo tenant. UUID matches aran-realm.json -> users[alice].attributes.tenant_id
INSERT INTO tenant (id, slug, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'demo', 'Demo Org')
ON CONFLICT (id) DO NOTHING;

-- FREE license for the demo tenant so the app doesn't 402 on first request.
-- V3__licensing.sql already has this same insert, but it also runs in
-- production where the tenant may not exist. This is the dev-specific copy
-- that's guaranteed to run after the tenant row above.
INSERT INTO license (tenant_id, plan, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'FREE', 'ACTIVE')
ON CONFLICT (tenant_id) DO NOTHING;

-- Pre-seed Alice's app_user row with a fixed subject matching Keycloak's imported UUID.
INSERT INTO app_user (id, subject, email, display_name, created_at)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002', 'alice@example.com', 'Alice Example', NOW())
ON CONFLICT (id) DO NOTHING;

-- Pre-seed Alice's membership as OWNER of the Demo tenant.
INSERT INTO membership (user_id, tenant_id, role)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'OWNER')
ON CONFLICT (user_id, tenant_id) DO NOTHING;
