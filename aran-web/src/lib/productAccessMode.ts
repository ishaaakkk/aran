import type { DataProduct, ProductAccessMode } from '@/services/types';

/**
 * `accessMode` is optional on the wire only for a payload predating Phase 5 (or an older
 * fixture/demo entry) — absent means MATERIALIZED, its only meaning before this field
 * existed. Every read of the field goes through this rather than `p.accessMode` directly,
 * so "missing" and "MATERIALIZED" can never diverge (see types.ts).
 *
 * A plain function, not a component — kept out of ProductsView.tsx so that file can stay
 * component-only (react-refresh/only-export-components).
 */
export function accessModeOf(p: DataProduct): ProductAccessMode {
  return p.accessMode ?? 'MATERIALIZED';
}

/**
 * What each mode gives and gives up, in the words every screen uses (J6-F2). "Always fresh"
 * was false: a virtual read recomputes from whatever the active schema points at, which for an
 * upload is the file as of its last upload or refresh. What a virtual product gives up is named
 * too — the Iceberg catalog serves MATERIALIZED products only, an agent read ignores its row limit
 * (V37), and a query a schema change breaks leaves the product READY with failing reads.
 */
export const ACCESS_MODE_COPY: Record<
  ProductAccessMode,
  { label: string; title: string; choice: string; previewHeader: string; rowFreshness?: string }
> = {
  MATERIALIZED: {
    label: 'Snapshot',
    title: 'Snapshot — computed once, served until refreshed',
    choice: 'computed once to a snapshot; as fresh as its last refresh',
    previewHeader: 'materialized snapshot',
  },
  VIRTUAL: {
    label: 'Virtual',
    title: 'Virtual — recomputed on every read from the sources the active schema points at; no stored snapshot',
    choice:
      'recomputed on every read from the sources the active schema points at — no stored ' +
      'snapshot, heavier per query, not served over Iceberg, agent reads capped at 1,000 rows',
    previewHeader: 'recomputed now',
    rowFreshness: 'recomputed on read',
  },
};

/**
 * The detail panel's sentence about a product's version and mode (J6-F1, J6-F16).
 * `definedLabel` is the version the product was defined against ("v2"); `activeLabel` the
 * ACTIVE one, or null when none is. The recorded version is provenance, not an execution pin
 * (`DataProduct.java`): a refresh or a virtual read plans under whatever is ACTIVE then.
 */
export function productDefinitionNote(
  mode: ProductAccessMode,
  definedLabel: string,
  activeLabel: string | null,
): string {
  const active = activeLabel ? `the active schema (${activeLabel})` : 'the active schema';
  if (mode === 'VIRTUAL') {
    return (
      `Virtual — defined against schema ${definedLabel}; every read re-plans this OQL under ` +
      `${active} and recomputes it from the sources that schema points at: live sources as ` +
      'they are now, uploaded ones as of their last upload or refresh. It has no snapshot and ' +
      'is not served over Iceberg, and it stays READY if a schema change breaks its query — ' +
      'reads then fail with an error.'
    );
  }
  return (
    `Defined against schema ${definedLabel}. A refresh re-runs this OQL under ${active}, so ` +
    'rows may change, and fails if the query no longer plans. To change the query, create a ' +
    'new product. Over a federated dataset a refresh reads the source live, or its lake copy ' +
    'when that copy has an interval refresh and is fresh enough for the planner to serve it.'
  );
}
