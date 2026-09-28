import { describe, it, expect } from 'vitest'
import type { DriftImpact, DriftReport, SchemaChange, SchemaChangeKind } from '../services/types'
import {
  bySeverity, confidenceLabel, countOf, decide, isBaseline, isUnchanged, kindLabel,
  repairableRenames, statusAfterDriftCheck, verdict,
} from './driftModel'

const CLS = 'http://ex.org/source/ds/papers'

function change(kind: SchemaChangeKind, overrides: Partial<SchemaChange> = {}): SchemaChange {
  return {
    kind,
    classIri: CLS,
    propertyIri: `${CLS}/venue`,
    replacementIri: null,
    before: 'venue',
    after: null,
    confidence: null,
    detail: 'something moved',
    ...overrides,
  }
}

function impact(
  severity: DriftImpact['severity'],
  c: SchemaChange,
  affected = 0,
): DriftImpact {
  return {
    change: c,
    severity,
    affected: Array.from({ length: affected }, (_, i) => ({
      mappingId: `m${i}`,
      kind: 'datatype' as const,
      status: 'ACCEPTED' as const,
      matchedIri: `${CLS}/venue`,
    })),
    reason: 'because',
  }
}

function report(impacts: DriftImpact[], baselineHash: string | null = 'old'): DriftReport {
  return { datasetId: 'd1', baselineHash, candidateHash: 'new', impacts }
}

describe('verdict', () => {
  it('is the worst severity present', () => {
    expect(verdict(report([
      impact('INFO', change('COLUMN_ADDED')),
      impact('BREAKING', change('COLUMN_REMOVED')),
      impact('WARNING', change('TYPE_CHANGED')),
    ]))).toBe('BREAKING')
  })

  it('is INFO when there is nothing to report', () => {
    expect(verdict(report([]))).toBe('INFO')
  })
})

describe('decide — what the re-bootstrap button does next', () => {
  it('applies a first fingerprint without interrupting anyone', () => {
    const decision = decide(report([], null))
    expect(decision.action).toBe('apply')
    expect(decision.reason).toContain('First time')
  })

  it('applies when the schema has not changed', () => {
    const unchanged: DriftReport = { datasetId: 'd1', baselineHash: 'same', candidateHash: 'same', impacts: [] }
    expect(decide(unchanged).action).toBe('apply')
  })

  it('stops for review only when something BREAKING is present', () => {
    expect(decide(report([impact('BREAKING', change('COLUMN_REMOVED'), 1)])).action).toBe('review')
  })

  it('does NOT stop for a warning — a dialog people click through protects nothing', () => {
    // Stopping on every type change trains the reflex that closes the breaking dialog too.
    const decision = decide(report([impact('WARNING', change('TYPE_CHANGED'), 1)]))
    expect(decision.action).toBe('apply')
    expect(decision.reason).toContain('none of which break')
  })

  it('does not stop for added columns', () => {
    expect(decide(report([impact('INFO', change('COLUMN_ADDED'))])).action).toBe('apply')
  })
})

describe('statusAfterDriftCheck — the row matches what the check did on the server', () => {
  it('a BREAKING report turns a READY row DRIFTED, as markDrifted just did server-side', () => {
    // "Check for schema changes" on a READY dataset, then "Keep current schema": the row
    // used to go on saying READY while the server held DRIFTED.
    expect(statusAfterDriftCheck('READY', report([impact('BREAKING', change('COLUMN_REMOVED'), 1)]))).toBe('DRIFTED')
  })

  it('anything less leaves the status alone — a check never clears DRIFTED', () => {
    expect(statusAfterDriftCheck('READY', report([impact('WARNING', change('TYPE_CHANGED'), 1)]))).toBe('READY')
    expect(statusAfterDriftCheck('DRIFTED', report([]))).toBe('DRIFTED')
    expect(statusAfterDriftCheck('READY', report([], null))).toBe('READY')
  })
})

describe('repairableRenames', () => {
  const rename = change('COLUMN_RENAMED', {
    replacementIri: `${CLS}/conference`,
    after: 'conference',
    confidence: 0.85,
  })

  it('offers a rename that actually has dependents', () => {
    const renames = repairableRenames(report([impact('BREAKING', rename, 3)]))
    expect(renames).toHaveLength(1)
    expect(renames[0]).toMatchObject({
      fromIri: `${CLS}/venue`,
      toIri: `${CLS}/conference`,
      fromLabel: 'venue',
      toLabel: 'conference',
      mappingCount: 3,
      confidence: 0.85,
    })
  })

  it('does not offer to repair a rename nobody mapped', () => {
    // There is no damage to undo, and offering the button would imply there was.
    expect(repairableRenames(report([impact('INFO', rename, 0)]))).toHaveLength(0)
  })

  it('does not offer a removal — there is nowhere to repoint to', () => {
    expect(repairableRenames(report([impact('BREAKING', change('COLUMN_REMOVED'), 2)]))).toHaveLength(0)
  })

  it('uses the CLASS iri when a whole dataset was renamed', () => {
    // A dataset rename re-mints the class IRI, so the repair keys on the class, not a property.
    const classRename = change('CLASS_IRI_CHANGED', {
      propertyIri: null,
      replacementIri: 'http://ex.org/source/ds/publications',
      before: CLS,
      after: 'http://ex.org/source/ds/publications',
    })
    expect(repairableRenames(report([impact('BREAKING', classRename, 5)]))[0].fromIri).toBe(CLS)
  })
})

describe('presentation helpers', () => {
  it('sorts the worst impact to the top', () => {
    const sorted = bySeverity([
      impact('INFO', change('COLUMN_ADDED')),
      impact('BREAKING', change('COLUMN_REMOVED')),
      impact('WARNING', change('TYPE_CHANGED')),
    ])
    expect(sorted.map(i => i.severity)).toEqual(['BREAKING', 'WARNING', 'INFO'])
  })

  it('does not mutate the array it was given', () => {
    const impacts = [impact('INFO', change('COLUMN_ADDED')), impact('BREAKING', change('COLUMN_REMOVED'))]
    bySeverity(impacts)
    expect(impacts[0].severity).toBe('INFO')
  })

  it('counts by severity', () => {
    const r = report([
      impact('BREAKING', change('COLUMN_REMOVED')),
      impact('BREAKING', change('CLASS_REMOVED')),
      impact('INFO', change('COLUMN_ADDED')),
    ])
    expect(countOf(r, 'BREAKING')).toBe(2)
    expect(countOf(r, 'WARNING')).toBe(0)
  })

  it('labels every change kind', () => {
    const kinds: SchemaChangeKind[] = ['CLASS_ADDED', 'CLASS_REMOVED', 'CLASS_IRI_CHANGED',
      'COLUMN_ADDED', 'COLUMN_REMOVED', 'COLUMN_RENAMED', 'TYPE_CHANGED', 'FORMAT_CHANGED']
    for (const kind of kinds) {
      expect(kindLabel(change(kind))).toBeTruthy()
    }
  })

  it('renders confidence as a percentage, and nothing when there is none', () => {
    expect(confidenceLabel(change('COLUMN_RENAMED', { confidence: 0.85 }))).toBe('85% confident')
    expect(confidenceLabel(change('COLUMN_REMOVED'))).toBeNull()
  })

  it('recognises a baseline and an unchanged report', () => {
    expect(isBaseline(report([], null))).toBe(true)
    expect(isBaseline(report([]))).toBe(false)
    expect(isUnchanged({ datasetId: 'd', baselineHash: 'x', candidateHash: 'x', impacts: [] })).toBe(true)
  })
})
