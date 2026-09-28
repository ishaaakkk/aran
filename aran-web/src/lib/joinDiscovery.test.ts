import { describe, expect, it } from 'vitest'
import {
  alignedJoinLabel,
  discoveryKey,
  discoveryState,
  emptyOutcomeMessage,
  joinMultiplicity,
  joinRelationshipOf,
  looksLikeTwoSurrogateKeys,
  overlapSummary,
  packJoinLabel,
  parseDiscoveryResult,
  queriedRelationship,
  relationshipNameProblem,
  unsampledNote,
} from './joinDiscovery'
import type { ModelMapping } from '@/services/types'

const CTX = { runId: 3, threshold: 0, method: 'exact' as const }

function run(data: Record<string, string>, ctx = CTX) {
  return parseDiscoveryResult(data, ctx)
}

describe('parseDiscoveryResult', () => {
  it('reads what the operator compared, not only what it found', () => {
    const r = run({
      candidates: '[]',
      pairsScored: '6',
      profiledColumnsA: '2',
      profiledColumnsB: '3',
      datasetNameA: 'acm',
      datasetNameB: 'researchers',
    })
    expect(r).toMatchObject({
      runId: 3,
      candidates: [],
      pairsScored: 6,
      profiledColumnsA: 2,
      profiledColumnsB: 3,
      datasetNameA: 'acm',
      datasetNameB: 'researchers',
      degraded: false,
      reason: null,
      failedColumns: [],
    })
  })

  it('carries a degraded run as degraded, with its reason and the columns that failed', () => {
    const r = run({
      candidates: '[]',
      pairsScored: '0',
      degraded: 'true',
      reason: '1 of 1 columns of acm could not be scored — the scoring service could not be reached',
      failedColumns: '["author_id"]',
    })
    expect(r.degraded).toBe(true)
    expect(r.reason).toContain('could not be reached')
    expect(r.failedColumns).toEqual(['author_id'])
  })

  it('leaves the counts unknown when the server did not send them (demo, older server) rather than inventing zeros', () => {
    const r = run({ candidates: '[{"iriA":"a","iriB":"b","label":"l","score":0.4}]' })
    expect(r.pairsScored).toBeNull()
    expect(r.profiledColumnsA).toBeNull()
    expect(r.candidates).toHaveLength(1)
  })

  it('accepts an already-parsed candidate list', () => {
    const r = parseDiscoveryResult(
      { candidates: [{ iriA: 'a', iriB: 'b', label: 'l', score: 0.4 }] } as unknown as Record<string, string>,
      CTX,
    )
    expect(r.candidates).toHaveLength(1)
  })
})

describe('discoveryState', () => {
  it('never renders a run that could not score as an empty result (J3-F5)', () => {
    expect(discoveryState(run({ candidates: '[]', degraded: 'true', reason: 'down', pairsScored: '0' }))).toBe('degraded')
  })

  it('shows candidates with a warning when only some columns failed', () => {
    expect(discoveryState(run({
      candidates: '[{"iriA":"a","iriB":"b","label":"l","score":0.4}]', degraded: 'true', reason: 'x',
    }))).toBe('partial')
  })

  it('tells a missing profile apart from a clean empty result', () => {
    expect(discoveryState(run({ candidates: '[]', profiledColumnsA: '0', profiledColumnsB: '3', pairsScored: '0' }))).toBe('no-profile')
    expect(discoveryState(run({ candidates: '[]', profiledColumnsA: '2', profiledColumnsB: '0', pairsScored: '0' }))).toBe('no-profile')
  })

  it('calls a clean run with nothing above the threshold "none", and a run with candidates "candidates"', () => {
    expect(discoveryState(run({ candidates: '[]', profiledColumnsA: '2', profiledColumnsB: '3', pairsScored: '6' }))).toBe('none')
    expect(discoveryState(run({ candidates: '[{"iriA":"a","iriB":"b","label":"l","score":0.4}]' }))).toBe('candidates')
  })
})

describe('emptyOutcomeMessage (J4-F21)', () => {
  it('at threshold 0 says no pair shares values, with the number compared, and gives no threshold advice', () => {
    const msg = emptyOutcomeMessage(run({
      candidates: '[]', pairsScored: '6', profiledColumnsA: '2', profiledColumnsB: '3',
      datasetNameA: 'acm', datasetNameB: 'semantic',
    }))
    expect(msg.headline).toBe('No column pair of acm × semantic shares values (6 pairs compared).')
    expect(msg.advice).toBeNull()
  })

  it('above 0 names the threshold and only then suggests lowering it', () => {
    const msg = emptyOutcomeMessage(run(
      { candidates: '[]', pairsScored: '6', profiledColumnsA: '2', profiledColumnsB: '3', datasetNameA: 'acm', datasetNameB: 'semantic' },
      { runId: 1, threshold: 0.3, method: 'exact' },
    ))
    expect(msg.headline).toBe('Compared 6 column pairs of acm × semantic; none scored 0.30 or more.')
    expect(msg.advice).toMatch(/lower the minimum join score/i)
  })

  it('says 1 pair, not 1 pairs', () => {
    const msg = emptyOutcomeMessage(run({ candidates: '[]', pairsScored: '1', datasetNameA: 'a', datasetNameB: 'b' }))
    expect(msg.headline).toContain('(1 pair compared)')
  })
})

describe('unsampled pairs — an unmeasured overlap is not a measured absence', () => {
  const base = { candidates: '[]', profiledColumnsA: '3', profiledColumnsB: '4', datasetNameA: 'acm', datasetNameB: 'researchers' }

  it('reads unsampledPairs, and leaves it unknown when the server did not send it (ML, older server)', () => {
    expect(run({ ...base, pairsScored: '12', unsampledPairs: '5' }).unsampledPairs).toBe(5)
    expect(run({ ...base, pairsScored: '12' }).unsampledPairs).toBeNull()
  })

  it('is its own state when EVERY pair was unsampled — nothing was compared', () => {
    expect(discoveryState(run({ ...base, pairsScored: '12', unsampledPairs: '12' }))).toBe('unmeasured')
    expect(discoveryState(run({ ...base, pairsScored: '12', unsampledPairs: '11' }))).toBe('none')
    expect(discoveryState(run({ ...base, pairsScored: '12', unsampledPairs: '0' }))).toBe('none')
  })

  it('an unmeasured run says so and names both datasets to re-profile, never "shares values"', () => {
    const msg = emptyOutcomeMessage(run({ ...base, pairsScored: '12', unsampledPairs: '12' }))
    expect(msg.headline).toMatch(/^Not measured: none of the 12 column pairs of acm × researchers could be compared/)
    expect(msg.headline).not.toMatch(/shares values/)
    expect(msg.advice).toMatch(/Re-run profiling on acm and researchers/)
  })

  it('a partly unsampled run counts only measured pairs as compared, and says how many were not', () => {
    const msg = emptyOutcomeMessage(run({ ...base, pairsScored: '12', unsampledPairs: '3' }))
    expect(msg.headline).toContain('(9 pairs compared)')
    expect(msg.headline).toContain('3 of 12 pairs could not be compared')
    expect(msg.advice).toMatch(/Re-run profiling/)
    expect(unsampledNote(run({ ...base, pairsScored: '12', unsampledPairs: '0' }))).toBeNull()
  })
})

describe('overlapSummary — the overlap as a foreign key reads', () => {
  const cand = { iriA: 'a', iriB: 'b', label: 'l', score: 0.2, columnA: 'author_ref', columnB: 'rid' }

  it('names the smaller column and the share of it found in the other', () => {
    expect(overlapSummary({ ...cand, evidence: { valueOverlap: 0.3, containment: 1, containmentOf: 'A' } }))
      .toBe('≈ 100 % of author_ref in rid')
    expect(overlapSummary({ ...cand, evidence: { valueOverlap: 0.3, containment: 0.8, containmentOf: 'B' } }))
      .toBe('≈ 80 % of rid in author_ref')
  })

  it('falls back to the Jaccard for an older payload, and to nothing when unmeasured', () => {
    expect(overlapSummary({ ...cand, evidence: { valueOverlap: 0.62 } })).toBe('≈ 62 % shared')
    expect(overlapSummary({ ...cand, evidence: { valueOverlap: null, containment: null } })).toBeNull()
    expect(overlapSummary(cand)).toBeNull()
  })
})

describe('joinMultiplicity (J3-F6)', () => {
  it('reads n..1 when only the right column is unique — the foreign-key shape', () => {
    expect(joinMultiplicity({ uniquenessA: 0.3, uniquenessB: 1 })).toBe('n..1')
  })
  it('reads 1..n, 1..1 and n..m for the other shapes', () => {
    expect(joinMultiplicity({ uniquenessA: 1, uniquenessB: 0.2 })).toBe('1..n')
    expect(joinMultiplicity({ uniquenessA: 1, uniquenessB: 1 })).toBe('1..1')
    expect(joinMultiplicity({ uniquenessA: 0.5, uniquenessB: 0.4 })).toBe('n..m')
  })
  it('does not guess without both uniquenesses', () => {
    expect(joinMultiplicity({ uniquenessA: 1 })).toBeNull()
    expect(joinMultiplicity(undefined)).toBeNull()
  })
})

describe('looksLikeTwoSurrogateKeys (J3-F6)', () => {
  it('flags two unique numeric columns — two 1..N sequences overlap by construction', () => {
    expect(looksLikeTwoSurrogateKeys({ uniquenessA: 1, uniquenessB: 1, typeA: 'BIGINT', typeB: 'INTEGER' })).toBe(true)
    expect(looksLikeTwoSurrogateKeys({ uniquenessA: 1, uniquenessB: 1, typeA: 'DECIMAL(10,0)', typeB: 'HUGEINT' })).toBe(true)
  })
  it('leaves a foreign key, or a text key, alone', () => {
    expect(looksLikeTwoSurrogateKeys({ uniquenessA: 0.3, uniquenessB: 1, typeA: 'BIGINT', typeB: 'BIGINT' })).toBe(false)
    expect(looksLikeTwoSurrogateKeys({ uniquenessA: 1, uniquenessB: 1, typeA: 'VARCHAR', typeB: 'VARCHAR' })).toBe(false)
  })
})

describe('packJoinLabel (J3-F14)', () => {
  it('posts the plain label when the user kept the default relationship and direction — the path c1 pins', () => {
    expect(packJoinLabel('join_author_ref_rid', 'join_author_ref_rid_join', 'forward')).toBe('join_author_ref_rid')
    expect(packJoinLabel('join_author_ref_rid', '', 'forward')).toBe('join_author_ref_rid')
    expect(packJoinLabel('join_author_ref_rid', '  ', 'forward')).toBe('join_author_ref_rid')
  })
  it('packs a named relationship or a reversed direction the way the manual form does', () => {
    expect(packJoinLabel('join_author_ref_rid', 'writtenBy', 'forward')).toBe('join_author_ref_rid|writtenBy|forward')
    expect(packJoinLabel('join_author_ref_rid', '', 'backward')).toBe('join_author_ref_rid|join_author_ref_rid_join|backward')
  })
})

describe('joinRelationshipOf — the name OQL uses, as JoinLabel.propertyName mints it', () => {
  it('is <label>_join when no relationship was given', () => {
    expect(joinRelationshipOf('join_author_ref_rid')).toEqual({ name: 'join_author_ref_rid', relationship: 'join_author_ref_rid_join', direction: 'forward' })
  })
  it('is the packed relationship when one was given', () => {
    expect(joinRelationshipOf('authorship|writtenBy|backward')).toEqual({ name: 'authorship', relationship: 'writtenBy', direction: 'backward' })
    expect(joinRelationshipOf('authorship||forward')).toEqual({ name: 'authorship', relationship: 'authorship_join', direction: 'forward' })
  })

  it('is the MINTED name — what OQL accepts — not the text as typed', () => {
    expect(joinRelationshipOf('authorship|has author|forward').relationship).toBe('has_author')
    expect(joinRelationshipOf('authorship| writtenBy |forward').relationship).toBe('writtenBy')
  })
})

describe('queriedRelationship / relationshipNameProblem — what the discovery form shows and refuses', () => {
  it('shows the minted fragment of a typed name, or the default when it is blank', () => {
    expect(queriedRelationship('join_author_ref_rid', 'has author')).toBe('has_author')
    expect(queriedRelationship('join_author_ref_rid', '   ')).toBe('join_author_ref_rid_join')
    expect(queriedRelationship('join_author_ref_rid', 'writtenBy')).toBe('writtenBy')
  })

  it('refuses "|", which would split the packed label and move the direction', () => {
    expect(relationshipNameProblem('a|b')).toMatch(/cannot contain “\|”/)
    expect(relationshipNameProblem('has author')).toBeNull()
    expect(relationshipNameProblem('')).toBeNull()
  })
})

describe('discoveryKey — results belong to the pair and settings they were computed for (J3-F3)', () => {
  const base = { projectId: 'p', left: 'acm', right: 'researchers', method: 'exact' as const, strictness: 0.25, threshold: 0 }

  it('changes with the pair, so the old pair\'s candidates are never offered for the new one', () => {
    expect(discoveryKey(base)).not.toBe(discoveryKey({ ...base, right: 'semantic' }))
    expect(discoveryKey(base)).not.toBe(discoveryKey({ ...base, projectId: 'q' }))
  })

  it('changes with the settings that change the scores', () => {
    expect(discoveryKey(base)).not.toBe(discoveryKey({ ...base, method: 'ml' }))
    expect(discoveryKey(base)).not.toBe(discoveryKey({ ...base, strictness: 0.5 }))
    expect(discoveryKey(base)).not.toBe(discoveryKey({ ...base, threshold: 0.3 }))
  })

  it('is stable for the same inputs', () => {
    expect(discoveryKey(base)).toBe(discoveryKey({ ...base }))
  })
})

describe('alignedJoinLabel — a candidate already aligned is said so (J3-F16)', () => {
  const join = (sourceIri: string, targetIri: string, status: ModelMapping['status'] = 'ACCEPTED'): ModelMapping => ({
    id: 'm', kind: 'join', sourceIri, targetIri, label: 'hasAuthor', status,
    evidence: null, author: null, comment: null, createdAt: '2026-09-25T00:00:00Z',
  })

  it('finds the join in either direction — reversed, it used to create a second, reversed join', () => {
    expect(alignedJoinLabel({ iriA: 'a:author_id', iriB: 'r:rid' }, [join('a:author_id', 'r:rid')])).toBe('hasAuthor')
    expect(alignedJoinLabel({ iriA: 'r:rid', iriB: 'a:author_id' }, [join('a:author_id', 'r:rid')])).toBe('hasAuthor')
  })

  it('ignores a rejected join and every other kind of alignment', () => {
    expect(alignedJoinLabel({ iriA: 'a:x', iriB: 'r:y' }, [join('a:x', 'r:y', 'REJECTED')])).toBeNull()
    expect(alignedJoinLabel({ iriA: 'a:x', iriB: 'r:y' }, [{ ...join('a:x', 'r:y'), kind: 'datatype' }])).toBeNull()
  })

  it('names the join by its label, not its "label|relationship|direction" packing', () => {
    expect(alignedJoinLabel({ iriA: 'a:x', iriB: 'r:y' }, [{ ...join('a:x', 'r:y'), label: 'authorKey|wrote|forward' }]))
      .toBe('authorKey')
  })

  it('names an unlabelled join by what it is', () => {
    expect(alignedJoinLabel({ iriA: 'a:x', iriB: 'r:y' }, [{ ...join('a:x', 'r:y'), label: null }])).toBe('an existing join')
  })
})
