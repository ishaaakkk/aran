/**
 * Join discovery's result, read honestly (audit A10: J3-F5, J3-F6, J3-F14, J4-F21).
 *
 * The operator used to answer a sidecar outage with the same empty list as two sources that share
 * nothing, and the modal advised lowering the threshold. It now reports what it compared
 * (`pairsScored`, `profiledColumnsA/B`) and, only when something failed, `degraded` + `reason`
 * (JoinDiscoveryOperator). This module is the one place that JSON is read, so the modal renders a
 * state rather than guessing one from an empty list.
 */

import { iriFragment } from './iri'
import type { ModelMapping } from '@/services/types'

export type DiscoveryMethod = 'ml' | 'exact'

/** What was already measured about a candidate pair — the facts that can refute its score. */
export interface JoinEvidence {
  /** Jaccard of the two columns' distinct values, estimated from hash samples; null = not sampled. */
  valueOverlap?: number | null
  /** Share of the SMALLER column's distinct values found in the other (`containmentOf` names it) —
   *  the foreign-key reading the Jaccard undersells. An estimate; null whenever the overlap is. */
  containment?: number | null
  containmentOf?: 'A' | 'B' | null
  cardinalityRatio?: number | null
  distinctA?: number
  distinctB?: number
  /** COUNT(DISTINCT)/COUNT(*), exact at profile time. */
  uniquenessA?: number
  uniquenessB?: number
  nullFractionA?: number
  nullFractionB?: number
  typeA?: string
  typeB?: string
}

/** A candidate join from `join-discovery`. */
export interface JoinCandidate {
  iriA: string
  iriB: string
  label: string
  score: number
  columnA?: string
  columnB?: string
  evidence?: JoinEvidence
}

export interface JoinDiscoveryRun {
  /** Which run produced this — the e2e page object waits for a run newer than the one it saw. */
  runId: number
  threshold: number
  method: DiscoveryMethod
  candidates: JoinCandidate[]
  degraded: boolean
  reason: string | null
  failedColumns: string[]
  /** null = the server did not say (demo, or a server older than J3-F5), never an invented zero. */
  pairsScored: number | null
  /** Of `pairsScored`, the pairs ranked WITHOUT a measured overlap (a side had no value sample), which
   *  the exact score leaves at 0. null when not reported (the ML method, an older server). */
  unsampledPairs: number | null
  profiledColumnsA: number | null
  profiledColumnsB: number | null
  datasetNameA: string | null
  datasetNameB: string | null
}

function count(raw: unknown): number | null {
  if (raw === undefined || raw === null || raw === '') return null
  const n = Number(raw)
  return Number.isFinite(n) ? n : null
}

function jsonList<T>(raw: unknown): T[] {
  if (Array.isArray(raw)) return raw as T[]
  if (typeof raw !== 'string' || raw === '') return []
  try {
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as T[]) : []
  } catch {
    return []
  }
}

export function parseDiscoveryResult(
  data: Record<string, string> | undefined,
  ctx: { runId: number; threshold: number; method: DiscoveryMethod },
): JoinDiscoveryRun {
  const d = (data ?? {}) as Record<string, unknown>
  return {
    runId: ctx.runId,
    threshold: ctx.threshold,
    method: ctx.method,
    candidates: jsonList<JoinCandidate>(d.candidates),
    degraded: d.degraded === 'true' || d.degraded === true,
    reason: typeof d.reason === 'string' && d.reason !== '' ? d.reason : null,
    failedColumns: jsonList<string>(d.failedColumns),
    pairsScored: count(d.pairsScored),
    unsampledPairs: count(d.unsampledPairs),
    profiledColumnsA: count(d.profiledColumnsA),
    profiledColumnsB: count(d.profiledColumnsB),
    datasetNameA: typeof d.datasetNameA === 'string' ? d.datasetNameA : null,
    datasetNameB: typeof d.datasetNameB === 'string' ? d.datasetNameB : null,
  }
}

/**
 * The state the modal renders. `degraded` and `partial` are failures and say so; `no-profile` is a
 * missing precondition; `unmeasured` means every pair was ranked without a value sample, so nothing
 * was actually compared; only `none` means "compared, and nothing matched".
 */
export type DiscoveryState = 'candidates' | 'partial' | 'degraded' | 'no-profile' | 'unmeasured' | 'none'

export function discoveryState(run: JoinDiscoveryRun): DiscoveryState {
  if (run.degraded) return run.candidates.length > 0 ? 'partial' : 'degraded'
  if (run.candidates.length > 0) return 'candidates'
  if (run.profiledColumnsA === 0 || run.profiledColumnsB === 0) return 'no-profile'
  if (run.pairsScored !== null && run.pairsScored > 0 && run.unsampledPairs === run.pairsScored) return 'unmeasured'
  return 'none'
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/** The pairs whose overlap was actually measured, when the server said. */
function measuredPairs(run: JoinDiscoveryRun): number | null {
  if (run.pairsScored === null) return null
  return run.pairsScored - (run.unsampledPairs ?? 0)
}

/** The fix for unsampled pairs: both datasets, since the operator cannot tell which side lacked a sample. */
function reprofileAdvice(run: JoinDiscoveryRun): string {
  return `Re-run profiling on ${run.datasetNameA ?? 'A'} and ${run.datasetNameB ?? 'B'} (each dataset's Profile tab) to sample their values, then run discovery again.`
}

/**
 * The note for pairs that were ranked but not compared (no value sample on a side) — said beside
 * a result rather than folded into "compared". Null when every pair was measured.
 */
export function unsampledNote(run: JoinDiscoveryRun): string | null {
  const unsampled = run.unsampledPairs ?? 0
  if (unsampled === 0 || run.pairsScored === null) return null
  return `${unsampled} of ${plural(run.pairsScored, 'pair', 'pairs')} could not be compared — a column had no value sample (profiled before samples existed).`
}

/** The words for a run that found nothing (J4-F21): threshold advice only above 0, an unmeasured run never as "shares no values". */
export function emptyOutcomeMessage(run: JoinDiscoveryRun): { headline: string; advice: string | null } {
  const pair = `${run.datasetNameA ?? 'A'} × ${run.datasetNameB ?? 'B'}`
  const measured = measuredPairs(run)
  if (discoveryState(run) === 'unmeasured') {
    return {
      headline: `Not measured: none of the ${plural(run.pairsScored ?? 0, 'column pair', 'column pairs')} of ${pair} could be compared — their columns have no value sample.`,
      advice: reprofileAdvice(run),
    }
  }
  const note = unsampledNote(run)
  if (run.threshold > 0) {
    const compared = measured !== null ? `Compared ${plural(measured, 'column pair', 'column pairs')} of ${pair}` : `No candidate for ${pair}`
    return {
      headline: `${compared}; none scored ${run.threshold.toFixed(2)} or more.${note ? ` ${note}` : ''}`,
      advice: note ? `Lower the minimum join score to see weaker matches. ${reprofileAdvice(run)}` : 'Lower the minimum join score to see weaker matches.',
    }
  }
  const compared = measured !== null ? ` (${plural(measured, 'pair', 'pairs')} compared)` : ''
  return {
    headline: `No column pair of ${pair} shares values${compared}.${note ? ` ${note}` : ''}`,
    advice: note ? reprofileAdvice(run) : null,
  }
}

/**
 * The overlap as a person reads a foreign key: the share of the smaller column's values found in
 * the other ("≈ 100 % of author_ref in rid"), falling back to the Jaccard when the containment was
 * not sent. Null when nothing was measured.
 */
export function overlapSummary(cand: JoinCandidate): string | null {
  const e = cand.evidence
  if (!e) return null
  const pctOf = (x: number) => `${Math.round(x * 100)} %`
  if (typeof e.containment === 'number' && (e.containmentOf === 'A' || e.containmentOf === 'B')) {
    const [inner, outer] = e.containmentOf === 'A' ? [cand.columnA, cand.columnB] : [cand.columnB, cand.columnA]
    return `≈ ${pctOf(e.containment)} of ${inner ?? e.containmentOf} in ${outer ?? (e.containmentOf === 'A' ? 'B' : 'A')}`
  }
  if (typeof e.valueOverlap === 'number') return `≈ ${pctOf(e.valueOverlap)} shared`
  return null
}

/**
 * The cardinality the evidence supports, left to right: `n..1` is the foreign-key shape (each left
 * row meets at most one right row). Measured at profile time over the snapshot — an accepted join
 * still enters as a HYPOTHESIS, which nothing downstream treats as a uniqueness guarantee.
 */
export function joinMultiplicity(e: JoinEvidence | undefined): '1..1' | 'n..1' | '1..n' | 'n..m' | null {
  if (!e || typeof e.uniquenessA !== 'number' || typeof e.uniquenessB !== 'number') return null
  const a = e.uniquenessA >= 1
  const b = e.uniquenessB >= 1
  if (a && b) return '1..1'
  if (b) return 'n..1'
  if (a) return '1..n'
  return 'n..m'
}

const NUMERIC = /^(U?(TINY|SMALL|BIG|HUGE)?INT(EGER)?|INT[1248]|DECIMAL|NUMERIC|DOUBLE|FLOAT|REAL)\b/i

/**
 * Two unique numeric columns — two surrogate keys numbered 1..N — share values by construction and
 * rank first under any overlap score, whether or not they identify the same thing (J3-F6).
 */
export function looksLikeTwoSurrogateKeys(e: JoinEvidence | undefined): boolean {
  if (!e) return false
  return (e.uniquenessA ?? 0) >= 1 && (e.uniquenessB ?? 0) >= 1
    && NUMERIC.test(e.typeA ?? '') && NUMERIC.test(e.typeB ?? '')
}

export type JoinDirection = 'forward' | 'backward'

/** What `JoinLabel.propertyName()` mints when no relationship is given. */
export const defaultRelationship = (label: string) => `${label}_join`

/**
 * The packed `label|relationship|direction` the manual form posts (JoinLabel.parse is the one
 * parser). An untouched candidate posts its bare label, byte-identical to before J3-F14.
 */
export function packJoinLabel(label: string, relationship: string, direction: JoinDirection): string {
  const rel = relationship.trim()
  const custom = rel !== '' && rel !== defaultRelationship(label)
  if (!custom && direction === 'forward') return label
  return `${label}|${custom ? rel : defaultRelationship(label)}|${direction}`
}

/**
 * The relationship name OQL uses, as the server mints it: `SchemaIntegrator.mintIri` is
 * `INTEGRATION_NS + Names.iriFragment(propertyName().trim())`, so a relationship typed `has author`
 * is queried as `has_author` — showing the typed text instead named a relationship nothing resolves.
 */
export function queriedRelationship(label: string, relationship: string): string {
  const rel = relationship.trim()
  return iriFragment((rel !== '' ? rel : defaultRelationship(label)).trim())
}

/**
 * Why a typed relationship name cannot be posted, or null. `|` separates the packed label's parts
 * (JoinLabel.parse), so `a|b` would post `label|a|b|forward` and be read back with direction `b`.
 */
export function relationshipNameProblem(relationship: string): string | null {
  return relationship.includes('|')
    ? 'A relationship name cannot contain “|” — it separates the parts of a join’s label.'
    : null
}

/** A join mapping's label unpacked the way JoinLabel does — `relationship` is the name OQL uses (minted). */
export function joinRelationshipOf(packed: string): { name: string; relationship: string; direction: JoinDirection } {
  if (!packed.includes('|')) return { name: packed, relationship: queriedRelationship(packed, ''), direction: 'forward' }
  const [name, rel, dir] = packed.split('|')
  return {
    name,
    relationship: queriedRelationship(name, rel ?? ''),
    direction: dir === 'backward' ? 'backward' : 'forward',
  }
}

/**
 * What a discovery run's candidates were computed for. Results are shown only under the key they
 * carry, so switching the right-hand source never offers the previous pair's columns as if they
 * belonged to the new one — accepting such a row joined the old pair (J3-F3).
 */
export function discoveryKey(k: {
  projectId: string | null;
  left: string | null;
  right: string | null;
  method: 'ml' | 'exact';
  strictness: number;
  threshold: number;
}): string {
  return JSON.stringify([k.projectId, k.left, k.right, k.method, k.strictness, k.threshold]);
}

/**
 * The label of the join already aligned between a candidate's two columns, in either direction,
 * or null. A candidate for an existing join used to be offered as new: accepting it did nothing
 * and said nothing, and accepting it reversed created a second, reversed join (J3-F16).
 */
export function alignedJoinLabel(
  cand: { iriA: string; iriB: string },
  mappings: ModelMapping[],
): string | null {
  const hit = mappings.find((m) => m.kind === 'join' && m.status !== 'REJECTED'
    && ((m.sourceIri === cand.iriA && m.targetIri === cand.iriB)
      || (m.sourceIri === cand.iriB && m.targetIri === cand.iriA)));
  if (!hit) return null;
  // The name the user gave the join, not its stored "label|relationship|direction" packing.
  return hit.label ? joinRelationshipOf(hit.label).name : 'an existing join';
}
