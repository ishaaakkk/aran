/**
 * The single canonical list of Playwright spec ids and the shared Keycloak
 * password for their e2e users. Plain ESM (no TypeScript loader) so that
 * both `docker/e2e/generate-identities.mjs` — run with plain `node`, no
 * npm dependencies — and `specUsers.ts` import the same values instead of
 * hand-carrying separate copies.
 *
 * Types live in the companion `spec-ids.d.mts`, which pins `SPEC_IDS` to a
 * readonly tuple of these exact string literals so `SpecId` in
 * `specUsers.ts` stays a real union instead of degrading to `string`.
 */
export const SPEC_IDS = Object.freeze([
  'auth',
  'a1', 'a2', 'a3', 'a4', 'a5',
  'b1', 'b2', 'b3', 'b4', 'b5',
  'c1', 'c2', 'c3',
  // Schema drift (see specs/d1-schema-drift.spec.ts). Renames a dataset, which
  // re-mints every IRI in its source graph — so it needs its own tenant like
  // every other scenario.
  'd1',
  // Stress/performance (see specs/s1-query-stress.spec.ts). Tagged @stress and
  // excluded from the default run, but it still needs its own tenant.
  's1',
  // APPEND ONLY, even when a new id belongs alphabetically elsewhere. Tenant and
  // user UUIDs are derived from POSITION in this array, and the derived rows are
  // baked into db/seed-e2e/V4__seed_e2e_data.sql — a checked-in Flyway migration.
  // Inserting in the middle renumbers every id after it and changes that
  // migration's checksum, which Flyway refuses on any database that already
  // applied it. Grouping is cosmetic; stable ids are not.
  //
  // a6 — refresh policy (specs/a6-refresh.spec.ts). Re-imports a table.
  'a6',
  // p1 — programmatic clients, live (specs/p1-mcp-live-client.spec.ts). Materializes a
  // data product and drives aran-mcp with a real MCP client, so it needs its own tenant
  // like every other scenario.
  'p1',
  // v32 / v34: the wrapper-expansion and constraint-discovery loops
  // (specs/v32-wrapper-expansion.spec.ts, specs/v34-constraint-discovery.spec.ts). Each
  // promotes its own schema, so each needs its own tenant.
  'v32',
  'v34',
  // v37 — virtual data products, the browser half (specs/v37-virtual-products.spec.ts).
  // Promotes its own schema and enforces a denial constraint on it, so it needs its own
  // tenant like every other scenario.
  'v37',
  // ui1 — the soft-skin UI contract (specs/ui1-soft-ui.spec.ts): routed dataset
  // deep links, the command palette, breadcrumbs, the stepper, the accessible
  // dialog, light-by-default. Registers one dataset, so it needs its own tenant.
  'ui1',
  // t1 — the docs walkthrough performed as written (specs/t1-walkthrough.spec.ts). Promotes
  // its own schema, so it needs its own tenant like every other scenario.
  't1',
]);

export const SPEC_PASSWORD = 'e2e';
