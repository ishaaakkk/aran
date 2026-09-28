import type { DriftImpact, DriftReport, DriftSeverity, SchemaChange } from '../services/types'

/**
 * Reading a drift report: what it says, and what the button should therefore do.
 *
 * <p>Pure functions over the report, kept out of the component so the decisions that matter —
 * "is this safe to apply without asking?", "which renames can be repaired?" — are testable
 * without rendering anything.
 */

const SEVERITY_ORDER: Record<DriftSeverity, number> = { INFO: 0, WARNING: 1, BREAKING: 2 }

/** The worst severity present, or `INFO` when there is nothing to report. */
export function verdict(report: DriftReport): DriftSeverity {
  return report.impacts.reduce<DriftSeverity>(
    (worst, impact) => (SEVERITY_ORDER[impact.severity] > SEVERITY_ORDER[worst] ? impact.severity : worst),
    'INFO',
  )
}

/** The first time a source is seen there is no baseline, so nothing can have drifted. */
export function isBaseline(report: DriftReport): boolean {
  return report.baselineHash === null
}

export function isUnchanged(report: DriftReport): boolean {
  return report.baselineHash === report.candidateHash
}

/** Worst first, so the thing that matters is not below the fold. */
export function bySeverity(impacts: DriftImpact[]): DriftImpact[] {
  return [...impacts].sort((a, b) => SEVERITY_ORDER[b.severity] - SEVERITY_ORDER[a.severity])
}

export function countOf(report: DriftReport, severity: DriftSeverity): number {
  return report.impacts.filter(i => i.severity === severity).length
}

/** A rename the user could confirm, with the mappings it would repoint. */
export interface RepairableRename {
  fromIri: string
  toIri: string
  /** The column names, for a label a human can check without reading an IRI. */
  fromLabel: string
  toLabel: string
  mappingCount: number
  confidence: number | null
}

/**
 * The renames a one-click repair could apply.
 *
 * <p>Only impacts that both name a replacement and actually have dependents: a rename nobody
 * mapped needs no repair, and offering one would imply there was damage to undo.
 */
export function repairableRenames(report: DriftReport): RepairableRename[] {
  return report.impacts
    .filter(i => i.change.replacementIri !== null && i.affected.length > 0)
    .map(i => ({
      fromIri: (i.change.propertyIri ?? i.change.classIri),
      toIri: i.change.replacementIri as string,
      fromLabel: i.change.before ?? '—',
      toLabel: i.change.after ?? '—',
      mappingCount: i.affected.length,
      confidence: i.change.confidence,
    }))
}

/** What the re-bootstrap button should do next, given the report. */
export type DriftDecision =
  /** Nothing changed, or nothing that matters. Proceed without interrupting anyone. */
  | { action: 'apply'; reason: string }
  /** Something breaks. Show the report and let the user choose. */
  | { action: 'review'; reason: string }

/**
 * Whether applying this candidate needs a human.
 *
 * <p>Only `BREAKING` interrupts. A `WARNING` — a type change on a mapped column, say — is worth
 * reading but stopping on it would train people to click through the dialog, which is exactly
 * how the breaking case ends up being clicked through too.
 */
export function decide(report: DriftReport): DriftDecision {
  if (isBaseline(report)) {
    return { action: 'apply', reason: 'First time this source has been fingerprinted.' }
  }
  if (isUnchanged(report)) {
    return { action: 'apply', reason: 'The source schema has not changed.' }
  }
  const worst = verdict(report)
  if (worst === 'BREAKING') {
    return {
      action: 'review',
      reason: `${countOf(report, 'BREAKING')} change(s) would break mappings already compiled into the active schema.`,
    }
  }
  return {
    action: 'apply',
    reason: `${report.impacts.length} change(s), none of which break anything mapped.`,
  }
}

/** A short label for a change kind, for a chip rather than a sentence. */
export function kindLabel(change: SchemaChange): string {
  switch (change.kind) {
    case 'CLASS_ADDED': return 'class added'
    case 'CLASS_REMOVED': return 'class removed'
    case 'CLASS_IRI_CHANGED': return 'renamed dataset'
    case 'COLUMN_ADDED': return 'column added'
    case 'COLUMN_REMOVED': return 'column removed'
    case 'COLUMN_RENAMED': return 'column renamed'
    case 'TYPE_CHANGED': return 'type changed'
    case 'FORMAT_CHANGED': return 'format changed'
  }
}

/** Confidence as a percentage string, or null when the change is not a proposal. */
export function confidenceLabel(change: SchemaChange): string | null {
  return change.confidence === null ? null : `${Math.round(change.confidence * 100)}% confident`
}

/**
 * The status a dataset row should show after a drift check.
 *
 * <p>A check that finds a BREAKING change is not only reported: the drift-check operator marks
 * the dataset DRIFTED on the server (`BootstrappingOperator.markDrifted`, the same rule as the
 * server's `report.breaking()`), so a row left reading READY contradicts the server until a
 * reload. Anything less than BREAKING changes nothing — a check never clears DRIFTED.
 */
export function statusAfterDriftCheck<S extends string | undefined>(status: S, report: DriftReport): S | 'DRIFTED' {
  return countOf(report, 'BREAKING') > 0 ? 'DRIFTED' : status
}
