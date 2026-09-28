import type { DataRepository, RelationalEngineInfo, SingerTapInfo } from '@/services/types';

/** What a masked secret comes back as, and what sending it back means: keep the stored value. */
export const MASKED_SECRET = '********';

/**
 * How the Singer tap catalogue should be presented.
 *
 * Four states, and collapsing any two of them is a lie the user pays for: `loading` (the request
 * is in flight, so the UI claims nothing), `error` (it failed — said, with a retry, instead of
 * "loading taps…" forever, UI audit J2-F32), `off` (the deployment configured no tap —
 * `SingerTapController`'s own rule is to say so rather than render an empty dropdown), and
 * `ready`.
 */
export type TapCatalogState = 'loading' | 'error' | 'off' | 'ready';

export function tapCatalogState(taps: SingerTapInfo[] | null, failed = false): TapCatalogState {
  if (taps === null) return failed ? 'error' : 'loading';
  return taps.length === 0 ? 'off' : 'ready';
}

/**
 * Whether a repository can be imported from by naming one relation, and what that relation is
 * called there.
 *
 * Both kinds take the same shape — scan the source, pick one, submit the name — which is why the
 * dataset dialog shares one picker; only the word differs, and it is the word a user of that
 * source already uses. `null` means neither, and the dialog offers upload only.
 *
 * A relational repository imports tables when its engine's extract is built in this release —
 * `bulkExtractReady` from `GET /api/relational-engines`, the same capability the repository form
 * reads and the server enforces. This used to be `engine === 'POSTGRESQL'`, a private copy of that
 * table that went wrong the day MySQL gained a JDBC extract (J2-F7). Until the capabilities load,
 * `engines` is null and nothing is claimed.
 */
export function importKindOf(
  repository: DataRepository | undefined,
  engines: RelationalEngineInfo[] | null,
): 'table' | 'stream' | 'iceberg table' | null {
  if (repository?.type === 'SINGER_TAP') return 'stream';
  if (repository?.type === 'ICEBERG_REST') return 'iceberg table';
  if (repository?.type === 'RELATIONAL_DB') {
    const engine = engines?.find((e) => e.id.toUpperCase() === repository.engine?.toUpperCase());
    return engine?.bulkExtractReady ? 'table' : null;
  }
  return null;
}

/**
 * The settings to submit for a tap: exactly the keys it declares, and nothing else.
 *
 * Switching taps in the form leaves stale values behind, and the server refuses a setting its tap
 * does not accept rather than dropping it (deliberately — a typo'd key must be reported, not
 * silently ignored). Filtering here is what keeps that refusal from being triggered by a key the
 * user never typed. A declared key the user left blank is still sent, so clearing a setting works.
 */
export function tapConfigPayload(
  tap: SingerTapInfo | null,
  values: Record<string, string>,
): Record<string, string> {
  const keys = tap?.configKeys ?? Object.keys(values);
  return Object.fromEntries(keys.map((key) => [key, values[key] ?? '']));
}
