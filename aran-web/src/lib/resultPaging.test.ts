import { describe, it, expect } from 'vitest'
import type { QueryRunResult } from '../services/types'
import { pageStateOf, showPager, prevOffset, nextOffset, pageRangeLabel } from './resultPaging'

function result(over: Partial<QueryRunResult>): QueryRunResult {
  return {
    headers: ['h'],
    headerNames: ['h'],
    rows: [],
    rowCount: 0,
    truncated: false,
    elapsedMs: 1,
    ...over,
  }
}

describe('pageStateOf', () => {
  it('reads the server’s page fields', () => {
    const state = pageStateOf(result({ offset: 2000, pageSize: 1000, rowCount: 1000, hasMore: true }))
    expect(state).toEqual({ offset: 2000, pageSize: 1000, rowCount: 1000, hasMore: true })
  })

  it('treats a payload with no paging fields as a single page', () => {
    // An older backend sends only rowCount and truncated. Rendering a pager against that would
    // produce controls that cannot work, so it must collapse to exactly the pre-paging behaviour.
    const state = pageStateOf(result({ rowCount: 9, truncated: false }))
    expect(state).toEqual({ offset: 0, pageSize: 9, rowCount: 9, hasMore: false })
    expect(showPager(state)).toBe(false)
  })

  it('falls back to `truncated` when `hasMore` is absent', () => {
    expect(pageStateOf(result({ rowCount: 1000, truncated: true })).hasMore).toBe(true)
  })

  it('does not assume the server’s cap is 1000', () => {
    // pageSize falls back to rowCount rather than a constant. A hardcoded 1000 here would send
    // `next` to the wrong offset the moment MAX_ROWS changed, silently skipping or repeating rows.
    const state = pageStateOf(result({ rowCount: 250, truncated: true }))
    expect(state.pageSize).toBe(250)
    expect(nextOffset(state)).toBe(250)
  })
})

describe('showPager', () => {
  it('is hidden for a result that fits in one page', () => {
    expect(showPager({ offset: 0, pageSize: 1000, rowCount: 12, hasMore: false })).toBe(false)
  })

  it('is shown on the first page of a larger result', () => {
    expect(showPager({ offset: 0, pageSize: 1000, rowCount: 1000, hasMore: true })).toBe(true)
  })

  it('is still shown on the LAST page, so there is a way back', () => {
    expect(showPager({ offset: 3000, pageSize: 1000, rowCount: 40, hasMore: false })).toBe(true)
  })
})

describe('navigation', () => {
  const state = { offset: 2000, pageSize: 1000, rowCount: 1000, hasMore: true }

  it('steps forward and back by one page', () => {
    expect(nextOffset(state)).toBe(3000)
    expect(prevOffset(state)).toBe(1000)
  })

  it('clamps prev at the start rather than going negative', () => {
    expect(prevOffset({ ...state, offset: 0 })).toBe(0)
    expect(prevOffset({ ...state, offset: 400 })).toBe(0)
  })

  it('round-trips: next then prev returns to the same offset', () => {
    const forward = { ...state, offset: nextOffset(state) }
    expect(prevOffset(forward)).toBe(state.offset)
  })
})

describe('pageRangeLabel', () => {
  it('is 1-based and inclusive', () => {
    expect(pageRangeLabel({ offset: 0, pageSize: 1000, rowCount: 1000, hasMore: true }))
      .toBe('1–1,000')
    expect(pageRangeLabel({ offset: 1000, pageSize: 1000, rowCount: 1000, hasMore: true }))
      .toBe('1,001–2,000')
  })

  it('reports a short final page by its real length, not the page size', () => {
    expect(pageRangeLabel({ offset: 2000, pageSize: 1000, rowCount: 37, hasMore: false }))
      .toBe('2,001–2,037')
  })

  it('says so when the page is empty', () => {
    // An offset past the end of the result — the engine returns nothing rather than failing.
    expect(pageRangeLabel({ offset: 9000, pageSize: 1000, rowCount: 0, hasMore: false }))
      .toBe('no rows')
  })
})
