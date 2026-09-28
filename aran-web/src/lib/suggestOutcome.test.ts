import { describe, expect, it } from 'vitest'
import { suggestOutcome } from './suggestOutcome'
import type { ModelMapping, MappingStatus } from '@/services/types'

const m = (id: string, status: MappingStatus): ModelMapping => ({
  id, kind: 'datatype' as ModelMapping['kind'], sourceIri: `a:${id}`, targetIri: `b:${id}`, label: null,
  status, evidence: null, author: null, comment: null, createdAt: '',
})

describe('suggestOutcome (J3-F17)', () => {
  it('an unprofiled or unrelated pair says why nothing appeared', () => {
    const o = suggestOutcome([], [])
    expect(o.sentence).toMatch(/No candidates for this pair/)
    expect(o.sentence).toMatch(/profile both datasets/)
  })

  it('counts what the run added apart from what already existed', () => {
    const before = [m('x', 'ACCEPTED'), m('y', 'SUGGESTED')]
    const o = suggestOutcome([m('x', 'ACCEPTED'), m('y', 'SUGGESTED'), m('z', 'SUGGESTED')], before)
    expect(o).toMatchObject({ created: 1, alreadyReviewed: 1, alreadyQueued: 1 })
    expect(o.sentence).toBe('1 new suggestion added to the review queue (1 already reviewed, 1 already in the queue).')
  })

  it('with no model read to compare against, claims nothing about what is new', () => {
    // Second review of UA2: the model had failed to load, and a run on an already-reviewed pair
    // read "2 new suggestions added to the review queue" — addMapping returned them unchanged.
    const o = suggestOutcome([m('x', 'ACCEPTED'), m('y', 'SUGGESTED')], null)
    expect(o.created).toBeNull()
    expect(o.sentence).toMatch(/^2 candidates returned for this pair/)
    expect(o.sentence).toMatch(/cannot say which are new/)
    expect(o.sentence).not.toMatch(/added/)
  })

  it('a fully reviewed pair says so, instead of leaving the queue silently unchanged', () => {
    const before = [m('x', 'ACCEPTED'), m('y', 'REJECTED')]
    const o = suggestOutcome([m('x', 'ACCEPTED'), m('y', 'REJECTED')], before)
    expect(o.created).toBe(0)
    expect(o.sentence).toBe('No new suggestions — every candidate for this pair already exists (2 already reviewed).')
  })
})
