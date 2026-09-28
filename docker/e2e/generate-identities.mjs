#!/usr/bin/env node
/**
 * Generates the e2e Keycloak realm and the matching Flyway seed from the
 * spec ids and password in aran-web/e2e/stack/spec-ids.mjs — the single
 * source of truth also imported by specUsers.ts, so the realm/seed and the
 * TypeScript helper read the same values and cannot drift apart.
 * Re-run after editing spec-ids.mjs:
 *
 *   node docker/e2e/generate-identities.mjs
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SPEC_IDS, SPEC_PASSWORD } from '../../aran-web/e2e/stack/spec-ids.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '../..');

const pad12 = (n) => String(n).padStart(12, '0');
const tenantId = (i) => `0000000e-2e00-0000-0000-${pad12(i + 1)}`;
const userId = (i) => `0000000e-2e00-0001-0000-${pad12(i + 1)}`;

// ---- realm -----------------------------------------------------------------
// Derived from the dev realm so client scopes and protocol mappers stay
// identical; only the realm name, users and redirect URIs differ.
const base = JSON.parse(
  readFileSync(path.join(REPO, 'keycloak/import/aran-realm.json'), 'utf8'),
);

base.realm = 'aran-e2e';
base.id = 'aran-e2e';
delete base._id;

for (const client of base.clients ?? []) {
  if (client.clientId === 'aran-web') {
    client.redirectUris = ['http://localhost:5273/*'];
    client.webOrigins = ['http://localhost:5273', '+'];
    // Keycloak validates the post-logout redirect against this attribute
    // separately from `redirectUris` above. Left at the dev realm's
    // localhost:5173, keycloak.logout({redirectUri: window.location.origin})
    // lands on Keycloak's own "Invalid redirect uri" page instead of coming
    // back to the app — logout then never navigates and the caller hangs.
    if (client.attributes) {
      client.attributes['post.logout.redirect.uris'] = 'http://localhost:5273';
    }
  }
}

base.users = [
  {
    username: 'service-account-aran-backend',
    enabled: true,
    serviceAccountClientId: 'aran-backend',
    clientRoles: {
      'realm-management': ['manage-users', 'view-users', 'query-users'],
    },
  },
  ...SPEC_IDS.map((specId, i) => ({
    id: userId(i),
    username: `e2e-${specId}`,
    enabled: true,
    emailVerified: true,
    email: `e2e-${specId}@example.com`,
    firstName: `E2E ${specId.toUpperCase()}`,
    lastName: 'Runner',
    attributes: { tenant_id: [tenantId(i)] },
    credentials: [{ type: 'password', value: SPEC_PASSWORD, temporary: false }],
    realmRoles: ['platform-admin'],
  })),
];

mkdirSync(path.join(REPO, 'docker/e2e'), { recursive: true });
writeFileSync(
  path.join(REPO, 'docker/e2e/aran-realm-e2e.json'),
  JSON.stringify(base, null, 2) + '\n',
);

// ---- seed ------------------------------------------------------------------
const tenants = SPEC_IDS.map((s, i) => `  ('${tenantId(i)}', 'e2e-${s}', 'E2E ${s.toUpperCase()}')`);
// max_projects_override lifts the FREE default of 3 so wide scenarios are safe.
const licenses = SPEC_IDS.map((_, i) => `  ('${tenantId(i)}', 'FREE', 'ACTIVE', 50)`);
const users = SPEC_IDS.map(
  (s, i) => `  ('${userId(i)}', '${userId(i)}', 'e2e-${s}@example.com', 'E2E ${s.toUpperCase()} Runner', NOW())`,
);
const memberships = SPEC_IDS.map((_, i) => `  ('${userId(i)}', '${tenantId(i)}', 'OWNER')`);

const sql = `-- ===========================================================================
--  V4__seed_e2e_data.sql — Playwright end-to-end seed data
--
--  GENERATED FILE — do not edit by hand.
--  Regenerate with: node docker/e2e/generate-identities.mjs
--
--  Applied only under the 'e2e' Spring profile, whose Flyway locations are
--  classpath:db/migration + classpath:db/seed-e2e. The dev seed at
--  classpath:db/seed is deliberately excluded there, so the two V4 files
--  never collide.
--
--  One tenant per Playwright spec: the schema graph is per-tenant, so this is
--  what stops specs from overwriting each other's promoted schema and is what
--  lets them run in parallel.
-- ===========================================================================

INSERT INTO tenant (id, slug, name)
VALUES
${tenants.join(',\n')}
ON CONFLICT (id) DO NOTHING;

INSERT INTO license (tenant_id, plan, status, max_projects_override)
VALUES
${licenses.join(',\n')}
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO app_user (id, subject, email, display_name, created_at)
VALUES
${users.join(',\n')}
ON CONFLICT (id) DO NOTHING;

INSERT INTO membership (user_id, tenant_id, role)
VALUES
${memberships.join(',\n')}
ON CONFLICT (user_id, tenant_id) DO NOTHING;
`;

const seedDir = path.join(
  REPO,
  'aran-adapters/aran-adapter-persistence/src/main/resources/db/seed-e2e',
);
mkdirSync(seedDir, { recursive: true });
writeFileSync(path.join(seedDir, 'V4__seed_e2e_data.sql'), sql);

console.log(`generated realm + seed for ${SPEC_IDS.length} spec identities`);
