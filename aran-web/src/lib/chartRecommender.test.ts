import { describe, it, expect } from 'vitest'
import type { QueryRunResult } from '../services/types'
import { analyse, recommend } from './chartRecommender'

function result(
  headerNames: string[],
  rows: (string | number | null)[][],
  columnRoles?: ('KEY' | 'MEASURE' | 'VALUE')[],
): QueryRunResult {
  return {
    headers: headerNames,
    headerNames,
    rows: rows as QueryRunResult['rows'],
    rowCount: rows.length,
    truncated: false,
    elapsedMs: 1,
    columnRoles,
  }
}

/** `SELECT Paper.venue, COUNT(*) … GROUP BY Paper.venue` — a comparison across groups. */
const grouped = result(
  ['venue', 'papers'],
  [['ACM', 12], ['VLDB', 9], ['SIGMOD', 21], ['ICDE', 4]],
  ['KEY', 'MEASURE'],
)

/** `SELECT Paper.title, Paper.year` — a list of individual papers. */
const projection = result(
  ['title', 'year'],
  [['Paper A', 2019], ['Paper B', 2020], ['Paper C', 2021], ['Paper D', 2018]],
  ['VALUE', 'VALUE'],
)

/** `SELECT Paper.year, COUNT(*) … GROUP BY Paper.year` — an ordered key. */
const overTime = result(
  ['year', 'papers'],
  [[2019, 4], [2020, 9], [2021, 6], [2022, 11]],
  ['KEY', 'MEASURE'],
)

describe('expressiveness — the hard filter', () => {
  it('a grouped result is a comparison, so a bar chart is expressive', () => {
    const best = recommend(grouped).candidates[0]
    expect(best.kind).toBe('bar')
    expect(best.labelColumn).toBe(0)
    expect(best.valueColumn).toBe(1)
  })

  it('an ungrouped projection is NOT a comparison, and the bar chart is rejected', () => {
    // The case a type-only heuristic gets wrong: text-then-number looks identical to the grouped
    // query above. Drawing it as bars asserts a comparison between four unrelated papers.
    const recommendation = recommend(projection)

    expect(recommendation.candidates.map(c => c.kind)).not.toContain('bar')
    expect(recommendation.rejected.find(r => r.kind === 'bar')?.reason)
      .toContain('individual records')
  })

  it('says what to do about a rejection rather than only that it happened', () => {
    const reason = recommend(
      result(['title', 'n'], [['a', 1], ['b', 2], ['c', 3]], ['VALUE', 'VALUE']),
    ).rejected.find(r => r.kind === 'bar')?.reason
    expect(reason).toBeDefined()
  })

  it('a line needs an ordered x, and says so when there is none', () => {
    // A line between nominal categories implies a progression from ACM to VLDB.
    expect(recommend(grouped).rejected.find(r => r.kind === 'line')?.reason)
      .toContain('ordered')
  })

  it('refuses a single row rather than drawing a one-bar chart', () => {
    const recommendation = recommend(result(['k', 'n'], [['only', 7]], ['KEY', 'MEASURE']))
    expect(recommendation.candidates).toHaveLength(0)
    expect(recommendation.advice).toContain('One row')
  })

  it('refuses when there is nothing to measure', () => {
    const recommendation = recommend(
      result(['a', 'b'], [['x', 'y'], ['p', 'q']], ['VALUE', 'VALUE']))
    expect(recommendation.candidates).toHaveLength(0)
    expect(recommendation.advice).toContain('COUNT(*)')
  })

  it('refuses an empty result', () => {
    expect(recommend(result(['a', 'b'], [], ['KEY', 'MEASURE'])).candidates).toHaveLength(0)
  })
})

describe('effectiveness — the ranking', () => {
  it('an ordered key prefers position over length, so line beats bar', () => {
    // Cleveland & McGill: position on a common scale is decoded more accurately than length.
    // Both are expressive for `year, COUNT(*)`; the ranking is what picks between them.
    const candidates = recommend(overTime).candidates
    expect(candidates[0].kind).toBe('line')
    expect(candidates.map(c => c.kind)).toContain('bar')
  })

  it('a nominal key has no position ordering, so bar wins by being the only expressive form', () => {
    const candidates = recommend(grouped).candidates
    expect(candidates[0].kind).toBe('bar')
    expect(candidates.map(c => c.kind)).not.toContain('line')
  })

  it('many categories rank lower than few — thin bars are harder to compare', () => {
    const few = recommend(grouped).candidates[0].score
    const many = recommend(result(
      ['k', 'n'],
      Array.from({ length: 40 }, (_, i) => [`cat-${i}`, i]),
      ['KEY', 'MEASURE'],
    )).candidates[0].score
    expect(many).toBeLessThan(few)
  })

  it('every candidate explains itself', () => {
    // A recommender that cannot say why is worse than a default you can override.
    for (const candidate of recommend(overTime).candidates) {
      expect(candidate.rationale.length).toBeGreaterThan(20)
    }
  })
})

describe('without query semantics (raw SQL)', () => {
  it('infers that a near-unique column is a row identifier, not a category', () => {
    // No columnRoles on the payload. Distinctness reproduces the same conclusion the query
    // semantics would have given: these are individuals, so a bar chart is not expressive.
    const raw = result(['title', 'year'],
      [['Paper A', 2019], ['Paper B', 2020], ['Paper C', 2021]])
    expect(recommend(raw).rejected.find(r => r.kind === 'bar')).toBeDefined()
  })

  it('accepts a repeating column as a category', () => {
    const raw = result(['venue', 'citations'],
      [['ACM', 4], ['ACM', 9], ['VLDB', 6], ['VLDB', 2]])
    expect(recommend(raw).candidates[0].kind).toBe('bar')
  })
})

describe('analyse', () => {
  it('measures distinctness over the returned rows', () => {
    expect(analyse(grouped).distinct).toEqual([4, 4])
    expect(analyse(result(['k'], [['a'], ['a'], ['b']])).distinct).toEqual([2])
  })

  it('detects whether a numeric column is already ascending', () => {
    // A line chart over unordered x is a scribble that looks like a trend.
    expect(analyse(overTime).ascending[0]).toBe(true)
    expect(analyse(result(['year', 'n'], [[2021, 1], [2019, 2]], ['KEY', 'MEASURE']))
      .ascending[0]).toBe(false)
  })

  it('prefers the query roles over anything inferred', () => {
    // A grouped query CAN have one row per key — that does not make it a list.
    const oneRowPerKey = result(['venue', 'n'],
      [['ACM', 1], ['VLDB', 2]], ['KEY', 'MEASURE'])
    expect(analyse(oneRowPerKey).roles).toEqual(['KEY', 'MEASURE'])
    expect(recommend(oneRowPerKey).candidates[0].kind).toBe('bar')
  })
})
