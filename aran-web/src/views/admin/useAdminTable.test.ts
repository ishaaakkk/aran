import { describe, expect, it } from 'vitest'
import {
  byNullableNumber, byNumberDesc, byRecency, byText, nextSort, sortRows,
  type SortDirection, type Sorter,
} from './useAdminTable'

/**
 * The comparators every admin table sorts by, and the direction a header click gives.
 *
 * The hook itself needs a renderer to exercise; these are the parts with decisions in them, and
 * each of the decisions below is one a naive implementation gets wrong. The direction cases are
 * audit J7-F17's: the tables used to sort one way only, and announced "ascending" for a column
 * sorted newest-first.
 */

interface Row { name: string; size: number; lastUsed: string | null; p95: number | null }

const row = (name: string, size: number, lastUsed: string | null, p95: number | null = null): Row =>
  ({ name, size, lastUsed, p95 })

const names = (rows: Row[]) => rows.map((r) => r.name)
const sorted = (rows: Row[], sorter: Sorter<Row>, direction: SortDirection = sorter.natural) =>
  sortRows(rows, sorter, direction)

describe('byText', () => {
  it('sorts alphabetically', () => {
    const rows = [row('Marta', 0, null), row('Anna', 0, null), row('Sergi', 0, null)]
    expect(names(sorted(rows, byText<Row>((r) => r.name)))).toEqual(['Anna', 'Marta', 'Sergi'])
  })

  it('ignores case, so a lowercase name is not exiled to the end', () => {
    // The default string comparison puts every uppercase letter before every lowercase one, which
    // sorts "anna" after "Sergi" — technically ordered, and wrong to a reader.
    const rows = [row('Sergi', 0, null), row('anna', 0, null)]
    expect(names(sorted(rows, byText<Row>((r) => r.name)))).toEqual(['anna', 'Sergi'])
  })

  it('starts ascending and reverses to Z-first', () => {
    const sorter = byText<Row>((r) => r.name)
    const rows = [row('Marta', 0, null), row('Anna', 0, null), row('Sergi', 0, null)]
    expect(sorter.natural).toBe('ascending')
    expect(names(sorted(rows, sorter, 'descending'))).toEqual(['Sergi', 'Marta', 'Anna'])
  })
})

describe('byNumberDesc', () => {
  it('puts the largest first — the useful direction for a size or a count', () => {
    const rows = [row('a', 10, null), row('b', 9000, null), row('c', 500, null)]
    expect(sorted(rows, byNumberDesc<Row>((r) => r.size)).map((r) => r.size)).toEqual([9000, 500, 10])
  })

  it('does not choke on zero', () => {
    const rows = [row('a', 0, null), row('b', 5, null)]
    expect(sorted(rows, byNumberDesc<Row>((r) => r.size)).map((r) => r.size)).toEqual([5, 0])
  })

  it('starts descending and reverses to smallest first', () => {
    const sorter = byNumberDesc<Row>((r) => r.size)
    const rows = [row('a', 10, null), row('b', 9000, null), row('c', 500, null)]
    expect(sorter.natural).toBe('descending')
    expect(sorted(rows, sorter, 'ascending').map((r) => r.size)).toEqual([10, 500, 9000])
  })
})

describe('byRecency', () => {
  it('sorts newest first', () => {
    const rows = [
      row('old', 0, '2026-01-01T00:00:00Z'),
      row('new', 0, '2026-08-01T00:00:00Z'),
      row('mid', 0, '2026-04-01T00:00:00Z'),
    ]
    expect(names(sorted(rows, byRecency<Row>((r) => r.lastUsed)))).toEqual(['new', 'mid', 'old'])
  })

  it('puts never-happened last, whichever end the encoding would have put it', () => {
    // Null is "this has not happened" — a token never presented, a member never seen. Comparing it
    // as an empty string sorts it to one end by accident; someone scanning for recent activity
    // means it to be at the bottom.
    const rows = [
      row('never', 0, null),
      row('recent', 0, '2026-08-01T00:00:00Z'),
      row('also-never', 0, null),
      row('older', 0, '2026-01-01T00:00:00Z'),
    ]
    expect(names(sorted(rows, byRecency<Row>((r) => r.lastUsed))))
      .toEqual(['recent', 'older', 'never', 'also-never'])
  })

  it('treats two nulls as equal rather than reordering them', () => {
    const rows = [row('a', 0, null), row('b', 0, null)]
    expect(names(sorted(rows, byRecency<Row>((r) => r.lastUsed)))).toEqual(['a', 'b'])
  })

  it('is natively descending, so the header can say so', () => {
    expect(byRecency<Row>((r) => r.lastUsed).natural).toBe('descending')
  })

  it('reversed is oldest first — and never-happened still last, not first', () => {
    // Negating a null-last comparator would float every never-used token to the top of an
    // "oldest first" list, which answers a question nobody asked.
    const rows = [
      row('never', 0, null),
      row('recent', 0, '2026-08-01T00:00:00Z'),
      row('older', 0, '2026-01-01T00:00:00Z'),
    ]
    expect(names(sorted(rows, byRecency<Row>((r) => r.lastUsed), 'ascending')))
      .toEqual(['older', 'recent', 'never'])
  })
})

describe('byNullableNumber', () => {
  it('sorts largest first with null last, and keeps null last reversed', () => {
    const rows = [row('none', 0, null, null), row('slow', 0, null, 900), row('fast', 0, null, 12)]
    const sorter = byNullableNumber<Row>((r) => r.p95)
    expect(names(sorted(rows, sorter))).toEqual(['slow', 'fast', 'none'])
    expect(names(sorted(rows, sorter, 'ascending'))).toEqual(['fast', 'slow', 'none'])
  })
})

describe('nextSort — what a header click does', () => {
  const sorters = {
    name: byText<Row>((r) => r.name),
    lastUsed: byRecency<Row>((r) => r.lastUsed),
  }

  it('clicking the sorted column reverses it', () => {
    expect(nextSort({ key: 'name', direction: 'ascending' }, 'name', sorters))
      .toEqual({ key: 'name', direction: 'descending' })
    expect(nextSort({ key: 'name', direction: 'descending' }, 'name', sorters))
      .toEqual({ key: 'name', direction: 'ascending' })
  })

  it('clicking another column starts it at its own natural order', () => {
    expect(nextSort({ key: 'name', direction: 'descending' }, 'lastUsed', sorters))
      .toEqual({ key: 'lastUsed', direction: 'descending' })
    expect(nextSort({ key: 'lastUsed', direction: 'ascending' }, 'name', sorters))
      .toEqual({ key: 'name', direction: 'ascending' })
  })
})

describe('sortRows — pinned rows', () => {
  it('keeps a pinned row last in both directions', () => {
    // The unattributed usage row is data, not a member: it must never sort in among people.
    const rows = [row('system', 99999, null), row('Anna', 3, null), row('Marta', 7, null)]
    const pin = (r: Row) => r.name === 'system'
    const sorter = byNumberDesc<Row>((r) => r.size)
    expect(names(sortRows(rows, sorter, 'descending', pin))).toEqual(['Marta', 'Anna', 'system'])
    expect(names(sortRows(rows, sorter, 'ascending', pin))).toEqual(['Anna', 'Marta', 'system'])
  })
})
