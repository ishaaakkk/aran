import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SPEC_IDS, tenantIdFor, userIdFor, usernameFor, REALM } from '../stack/specUsers';

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');

test('there are exactly 23 spec identities and they are unique', () => {
  expect(SPEC_IDS).toHaveLength(23);
  expect(new Set(SPEC_IDS.map(tenantIdFor)).size).toBe(23);
  expect(new Set(SPEC_IDS.map(userIdFor)).size).toBe(23);
});

test('ids are well-formed UUIDs', () => {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  for (const id of SPEC_IDS) {
    expect(tenantIdFor(id)).toMatch(uuid);
    expect(userIdFor(id)).toMatch(uuid);
  }
});

test('the generated realm carries every user, with the e2e redirect URI', () => {
  const realm = JSON.parse(
    readFileSync(path.join(REPO, 'docker/e2e/aran-realm-e2e.json'), 'utf8'),
  );
  expect(realm.realm).toBe(REALM);

  const usernames = realm.users.map((u: { username: string }) => u.username);
  for (const id of SPEC_IDS) expect(usernames).toContain(usernameFor(id));

  // Each user's Keycloak id must equal the subject the seed SQL inserts, or
  // JIT provisioning creates a second app_user row with no membership.
  const specUsers = realm.users.filter((u: { serviceAccountClientId?: string }) => !u.serviceAccountClientId);
  for (const u of specUsers) {
    const specId = u.username.replace(/^e2e-/, '');
    expect(u.id).toBe(userIdFor(specId));
    expect(u.attributes.tenant_id).toEqual([tenantIdFor(specId)]);
  }

  const web = realm.clients.find((c: { clientId: string }) => c.clientId === 'aran-web');
  expect(web.redirectUris).toContain('http://localhost:5273/*');
  expect(web.publicClient).toBe(true);
});

test('the generated seed inserts a tenant, license, user and membership per spec', () => {
  const sql = readFileSync(
    path.join(
      REPO,
      'aran-adapters/aran-adapter-persistence/src/main/resources/db/seed-e2e/V4__seed_e2e_data.sql',
    ),
    'utf8',
  );
  for (const id of SPEC_IDS) {
    expect(sql).toContain(tenantIdFor(id));
    expect(sql).toContain(userIdFor(id));
  }
  expect(sql.match(/INSERT INTO tenant/g)).toHaveLength(1);
  expect(sql.match(/INSERT INTO membership/g)).toHaveLength(1);
});

// Regression coverage for the drift the two generated files used to be able
// to fall into: SPEC_IDS lived twice (specUsers.ts and
// generate-identities.mjs), and an id added to only one list left the realm
// or the seed silently out of step with the other. Both files now import
// the same aran-web/e2e/stack/spec-ids.mjs; these tests assert *exact*
// correspondence — not mere containment — so any future drift, from
// whatever cause, fails here rather than as a spec authenticating into the
// wrong tenant.
test('the realm has exactly one user per spec id, no more and no fewer', () => {
  const realm = JSON.parse(
    readFileSync(path.join(REPO, 'docker/e2e/aran-realm-e2e.json'), 'utf8'),
  );
  const specUsers = realm.users.filter((u: { serviceAccountClientId?: string }) => !u.serviceAccountClientId);
  const usernames = specUsers.map((u: { username: string }) => u.username);
  expect(usernames.sort()).toEqual([...SPEC_IDS.map(usernameFor)].sort());
});

test('the generated seed has exactly one row per spec id in every INSERT block', () => {
  const sql = readFileSync(
    path.join(
      REPO,
      'aran-adapters/aran-adapter-persistence/src/main/resources/db/seed-e2e/V4__seed_e2e_data.sql',
    ),
    'utf8',
  );
  for (const block of ['tenant', 'license', 'app_user', 'membership']) {
    const statement = sql.match(
      new RegExp(`INSERT INTO ${block}[\\s\\S]*?;`),
    )?.[0];
    expect(statement, `no INSERT INTO ${block} statement found`).toBeDefined();
    // Each row is one parenthesised tuple; the statement has exactly one row
    // per spec id — never fewer (a missing id) nor more (a stray one).
    const rowCount = statement!.match(/^\s*\(/gm)?.length ?? 0;
    expect(rowCount).toBe(SPEC_IDS.length);
  }
});

// Regression coverage for a second, subtler place the same duplication could
// re-form: spec-ids.d.mts hand-declares SPEC_IDS as a readonly tuple of
// string literals so SpecId stays a real union across the plain-JS/TS
// boundary, instead of degrading to `string`. TypeScript trusts that
// declaration file as ground truth and never checks it against the runtime
// array in spec-ids.mjs, so nothing else catches the two falling out of
// step — add an id to the .mjs without the .d.mts (or vice versa) and every
// other test above still passes. This test reads the .d.mts source directly
// (there is no runtime binding for a type declaration) and compares its
// declared literals, in order, against the actual runtime SPEC_IDS array.
test('spec-ids.d.mts declares exactly the same ids, in the same order, as spec-ids.mjs exports at runtime', () => {
  const dtsSource = readFileSync(
    path.join(REPO, 'aran-web/e2e/stack/spec-ids.d.mts'),
    'utf8',
  );
  const tupleMatch = dtsSource.match(/SPEC_IDS:\s*readonly\s*\[([\s\S]*?)\]/);
  expect(tupleMatch, 'could not find "SPEC_IDS: readonly [...]" in spec-ids.d.mts').not.toBeNull();

  const declaredIds = [...tupleMatch![1].matchAll(/'([^']*)'/g)].map((m) => m[1]);
  expect(declaredIds).toEqual([...SPEC_IDS]);
});

test('the e2e Spring profile pins the offset ports and the e2e seed', () => {
  const yml = readFileSync(
    path.join(REPO, 'aran-application/src/main/resources/application-e2e.yml'),
    'utf8',
  );
  // Server port
  expect(yml).toContain('port: 8181');
  // Management port (actuator, part of seven mandated e2e ports)
  expect(yml).toContain('port: 8182');

  // Seed configuration
  expect(yml).toContain('classpath:db/seed-e2e');
  expect(yml).not.toContain('classpath:db/seed\n');

  // Keycloak: all six OAuth2 URIs (issuer-uri, two jwk-set-uri, authorization-uri,
  // token-uri, user-info-uri) point to e2e realm, not dev.
  // Ensure no dev Keycloak port
  expect(yml).not.toContain('localhost:8080');
  // Ensure no /realms/aran that is not /realms/aran-e2e (via negative lookahead)
  expect(yml).not.toMatch(/\/realms\/aran(?!-e2e)/);
  // Assert exactly six occurrences of the e2e Keycloak realm URI
  expect((yml.match(/localhost:8180\/realms\/aran-e2e/g) || []).length).toBe(6);

  // Offset ports (anchored with negative lookahead to block substring matches like 31300 for 3130)
  expect(yml).toMatch(/localhost:3130(?!\d)/);  // Fuseki
  expect(yml).toMatch(/localhost:8185(?!\d)/);  // Lake
  expect(yml).toMatch(/localhost:8186(?!\d)/);  // Freyja
  expect(yml).toMatch(/localhost:55432(?!\d)/); // Database
});
