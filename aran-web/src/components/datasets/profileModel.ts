import type { ProfileGroup } from '@/services/types';

/**
 * The Profile tab's model: what a run asks for, and how stored evidence reads back.
 *
 * Kept apart from the view so the rules are testable without a DOM (this project has none).
 */

/** The `profile` operation a Profile-tab run submits. */
export function profilingRequest(dataset: { id: string; snapshotId?: string | null }) {
  return {
    operation: 'profile',
    parameters: {
      datasetId: dataset.id,
      snapshotId: dataset.snapshotId ?? '',
      // Bootstrap profiles with 'false' (DatasetService.runBootstrapPipeline). A run supersedes the
      // stored SNAPSHOT generation, so anything narrower silently deletes evidence (J3-F2).
      varcharOnly: 'false',
      maxConcurrentColumnQueries: '4',
    },
  };
}

/**
 * The stored profile as loaded for the open dataset. Carries the dataset it is FOR (J3-F8): a
 * response — or a run's completion — for a dataset the user has left must never render against
 * the one now open, and "loading" must never read as "no profile".
 */
export type ProfileLoad =
  | { datasetId: string; status: 'loading' }
  | { datasetId: string; status: 'error'; message: string }
  | { datasetId: string; status: 'ready'; groups: ProfileGroup[] };

function localName(iri: string): string {
  const cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
  return cut >= 0 ? iri.slice(cut + 1) : iri;
}

/** What a generation measured, in the user's words. */
export function subjectLabel(group: Pick<ProfileGroup, 'subject' | 'classIri'>): string {
  const cls = group.classIri ? ` · ${localName(group.classIri)}` : '';
  switch (group.subject) {
    case 'SNAPSHOT': return 'Snapshot';
    case 'WRAPPER_OUTPUT': return `Wrapper output${cls}`;
    case 'FEDERATED_LIVE': return `Live source${cls}`;
    case 'HARVESTED': return 'Catalog statistics';
    default: return `${group.subject}${cls}`;
  }
}

/** Why a generation is not key evidence — the planner's rule, said where the numbers are read. */
export function keyEvidenceNote(group: Pick<ProfileGroup, 'subject' | 'keyEvidence'>): string | null {
  if (group.keyEvidence) return null;
  if (group.subject === 'HARVESTED') {
    return 'Estimates from the source database\'s own statistics (pg_stats). The planner uses them for row counts, never as proof that a column is unique.';
  }
  if (group.subject === 'FEDERATED_LIVE') {
    return 'Measured over the live source when the schema was compiled. Exact then, but the source keeps changing, so the planner never takes it as proof that a column is unique.';
  }
  return 'The planner never takes this generation as proof that a column is unique.';
}

/** A number with fixed decimals, or a dash when the metric is missing (J3-F9: never a crash). */
export function fmtFixed(v: number | null | undefined, digits = 2): string {
  return typeof v === 'number' && Number.isFinite(v) ? v.toFixed(digits) : '—';
}

/** A 0–1 ratio as a percentage, or a dash when missing. */
export function fmtPct(v: number | null | undefined): string {
  return typeof v === 'number' && Number.isFinite(v) ? `${(v * 100).toFixed(2)}%` : '—';
}

/** A whole-number metric, or a dash when missing. */
export function fmtInt(v: number | null | undefined): string {
  return typeof v === 'number' && Number.isFinite(v) ? v.toLocaleString('en-US') : '—';
}

/**
 * The per-column failures a run reported (`errors` in freyja's profile payload). A run can succeed
 * while some columns failed; those columns are not persisted, so without this they would silently
 * vanish from the tab (J3-F9).
 */
export function runErrorsOf(profileJson: string | undefined): Record<string, string> {
  if (!profileJson) return {};
  try {
    const parsed = JSON.parse(profileJson) as { errors?: Record<string, unknown> };
    const out: Record<string, string> = {};
    for (const [col, reason] of Object.entries(parsed.errors ?? {})) out[col] = String(reason);
    return out;
  } catch {
    return {};
  }
}
