import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SPEC_IDS, SPEC_PASSWORD } from './spec-ids.mjs';

/**
 * One Keycloak user per spec. The backend treats the JWT `tenant_id` claim as
 * authoritative and in-session tenant switching is disabled, so a distinct
 * tenant — which is what isolates one spec's schema graph from another's —
 * requires a distinct user.
 *
 * `SPEC_IDS` and `SPEC_PASSWORD` live in `./spec-ids.mjs` (typed by the
 * companion `spec-ids.d.mts`) — that is the single source of truth also
 * consumed by `docker/e2e/generate-identities.mjs`, so this file and the
 * generated realm/seed cannot drift.
 */
export { SPEC_IDS, SPEC_PASSWORD };

export type SpecId = (typeof SPEC_IDS)[number];

export const REALM = 'aran-e2e';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const STATE_DIR = path.resolve(HERE, '../.auth');

function indexOf(specId: string): number {
  const i = SPEC_IDS.indexOf(specId as SpecId);
  if (i < 0) throw new Error(`unknown spec id: ${specId}`);
  return i + 1; // 1-based
}

const pad12 = (n: number) => String(n).padStart(12, '0');

export function tenantIdFor(specId: string): string {
  return `0000000e-2e00-0000-0000-${pad12(indexOf(specId))}`;
}

export function userIdFor(specId: string): string {
  return `0000000e-2e00-0001-0000-${pad12(indexOf(specId))}`;
}

export function usernameFor(specId: string): string {
  return `e2e-${specId}`;
}

export function storageStatePath(specId: string): string {
  return path.join(STATE_DIR, `${specId}.json`);
}
