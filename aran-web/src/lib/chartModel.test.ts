import { describe, it, expect } from 'vitest'
import type { QueryRunResult } from '../services/types'
import {
  classifyColumns, inferChartSpec, isChartable, buildSeries, omittedCount,
  valueScale, formatValue, labelledIndices, truncateLabel, stripCommonPrefix, MAX_CATEGORIES,
} from './chartModel'

function result(headerNames: string[], rows: (string | number | boolean | null)[][]): QueryRunResult {
  return {
    headers: headerNames,
    headerNames,
    rows: rows as QueryRunResult['rows'],
    rowCount: rows.length,
    truncated: false,
    elapsedMs: 1,
  }
}

describe('classifyColumns', () => {
  it('reads types from the values, not the header', () => {
    const r = result(['name', 'count'], [['ada', 3], ['grace', 5]])
    expect(classifyColumns(r)).toEqual(['text', 'number'])
  })

  it('a column with any non-numeric value is text', () => {
    // One stray string is enough: charting it as a magnitude would need a silent coercion.
    expect(classifyColumns(result(['x'], [[1], [2], ['n/a']]))).toEqual(['text'])
  })

  it('an all-null column is empty, not numeric', () => {
    // GAV NULL-pads properties a source does not supply, so all-null columns are routine.
    // Calling one numeric would let it be picked as an axis and drawn as a flat nothing.
    expect(classifyColumns(result(['a', 'b'], [[1, null], [2, null]]))).toEqual(['number', 'empty'])
  })

  it('ignores nulls and blanks when typing a column', () => {
    expect(classifyColumns(result(['n'], [[1], [null], [3]]))).toEqual(['number'])
  })
})

describe('inferChartSpec', () => {
  it('a text key and a measure is a bar chart', () => {
    const spec = inferChartSpec(result(['title', 'n'], [['a', 1], ['b', 2]]))
    expect(spec).toEqual({ kind: 'bar', labelColumn: 0, valueColumn: 1 })
  })

  it('a numeric key and a measure is a line chart, not two magnitudes', () => {
    // `SELECT Paper.year, COUNT(*) … GROUP BY year`. Both columns are numeric, but the first is
    // the group key: drawing a year as a bar height is the classic way a chart lies.
    const spec = inferChartSpec(result(['year', 'papers'], [[2019, 4], [2020, 9], [2021, 6]]))
    expect(spec).toEqual({ kind: 'line', labelColumn: 0, valueColumn: 1 })
  })

  it('prefers a text column as the label even when it is not first', () => {
    const spec = inferChartSpec(result(['n', 'name'], [[1, 'a'], [2, 'b']]))
    expect(isChartable(spec) && spec.labelColumn).toBe(1)
    expect(isChartable(spec) && spec.valueColumn).toBe(0)
  })

  it('refuses a single row rather than drawing a one-bar chart', () => {
    const spec = inferChartSpec(result(['name', 'n'], [['only', 7]]))
    expect(isChartable(spec)).toBe(false)
    expect(!isChartable(spec) && spec.reason).toContain('One row')
  })

  it('refuses when nothing is measurable, and says what to do', () => {
    const spec = inferChartSpec(result(['a', 'b'], [['x', 'y'], ['p', 'q']]))
    expect(isChartable(spec)).toBe(false)
    // The message has to be actionable — "not chartable" alone leaves the user stuck.
    expect(!isChartable(spec) && spec.reason).toContain('COUNT(*)')
  })

  it('refuses a bare list of numbers with nothing to group by', () => {
    const spec = inferChartSpec(result(['year'], [[2019], [2020], [2021]]))
    expect(isChartable(spec)).toBe(false)
    expect(!isChartable(spec) && spec.reason).toContain('group by')
  })

  it('refuses an empty result', () => {
    expect(isChartable(inferChartSpec(result(['a', 'b'], [])))).toBe(false)
  })

  it('does not pick an all-null column as the measure', () => {
    const spec = inferChartSpec(result(['name', 'missing', 'n'],
      [['a', null, 1], ['b', null, 2]]))
    expect(isChartable(spec) && spec.valueColumn).toBe(2)
  })
})

describe('buildSeries', () => {
  const spec = { kind: 'bar' as const, labelColumn: 0, valueColumn: 1 }

  it('keeps the result order rather than ranking by value', () => {
    // The user's ORDER BY is a statement about how they want it read; re-sorting would make the
    // chart disagree with the table beside it.
    const points = buildSeries(result(['k', 'n'], [['c', 3], ['a', 9], ['b', 1]]), spec)
    expect(points.map(p => p.label)).toEqual(['c', 'a', 'b'])
  })

  it('drops rows with no measure — there is no honest height for unknown', () => {
    const points = buildSeries(result(['k', 'n'], [['a', 1], ['b', null], ['c', 3]]), spec)
    expect(points).toHaveLength(2)
  })

  it('renders a null label as the same glyph the table uses', () => {
    expect(buildSeries(result(['k', 'n'], [[null, 1], ['b', 2]]), spec)[0].label).toBe('∅')
  })

  it('caps the categories and reports how many were left out', () => {
    const rows = Array.from({ length: 45 }, (_, i) => [`c${i}`, i]) as (string | number)[][]
    const r = result(['k', 'n'], rows)
    expect(buildSeries(r, spec)).toHaveLength(MAX_CATEGORIES)
    expect(omittedCount(r, spec)).toBe(15)
  })

  it('reports nothing omitted when everything fits', () => {
    expect(omittedCount(result(['k', 'n'], [['a', 1], ['b', 2]]), spec)).toBe(0)
  })
})

describe('valueScale', () => {
  it('always includes zero, so bar lengths stay proportional', () => {
    // A truncated bar axis exaggerates differences — the most effective way to mislead with a
    // chart, and it happens by accident whenever an axis is fitted to the data.
    const scale = valueScale([{ label: 'a', value: 101 }, { label: 'b', value: 105 }])
    expect(scale.min).toBe(0)
    expect(scale.max).toBeGreaterThanOrEqual(105)
  })

  it('ends on a round number', () => {
    expect(valueScale([{ label: 'a', value: 0 }, { label: 'b', value: 37 }]).max).toBe(40)
    expect(valueScale([{ label: 'a', value: 0 }, { label: 'b', value: 4 }]).max).toBe(4)
  })

  it('gives negative values a real arm rather than clamping them away', () => {
    const scale = valueScale([{ label: 'a', value: -12 }, { label: 'b', value: 30 }])
    expect(scale.min).toBeLessThanOrEqual(-12)
    expect(scale.max).toBeGreaterThanOrEqual(30)
    expect(scale.ticks).toContain(0)
  })

  it('produces ticks free of floating-point noise', () => {
    // Accumulating `t += step` drifts; a tick labelled 0.30000000000000004 is a real outcome.
    const scale = valueScale([{ label: 'a', value: 0 }, { label: 'b', value: 1 }])
    for (const tick of scale.ticks) {
      expect(String(tick)).not.toMatch(/\d{10,}/)
    }
  })

  it('survives an all-equal series without dividing by zero', () => {
    const scale = valueScale([{ label: 'a', value: 5 }, { label: 'b', value: 5 }])
    expect(scale.max).toBeGreaterThan(scale.min)
    expect(scale.ticks.length).toBeGreaterThan(1)
  })

  it('survives an empty series', () => {
    expect(valueScale([]).ticks.length).toBeGreaterThan(1)
  })
})

describe('formatValue', () => {
  it('compacts large numbers and leaves small ones alone', () => {
    expect(formatValue(7)).toBe('7')
    expect(formatValue(1500)).toBe('1.5k')
    expect(formatValue(12_300)).toBe('12k')
    expect(formatValue(2_400_000)).toBe('2.4M')
    expect(formatValue(-1500)).toBe('-1.5k')
  })

  it('does not print a long float tail', () => {
    expect(formatValue(1 / 3)).toBe('0.33')
  })
})

describe('labelledIndices', () => {
  it('labels every point when there are few enough to read', () => {
    const points = [1, 2, 3].map(v => ({ label: String(v), value: v }))
    expect(labelledIndices(points).size).toBe(3)
  })

  it('labels only the extremes once the marks get dense', () => {
    // A number on every mark is chaos and goes unread; the axis and tooltip carry the rest.
    const points = Array.from({ length: 20 }, (_, i) => ({ label: `p${i}`, value: i === 5 ? 99 : i }))
    const labelled = labelledIndices(points)
    expect(labelled.size).toBe(2)
    expect(labelled.has(5)).toBe(true)   // the max
    expect(labelled.has(0)).toBe(true)   // the min
  })

  it('handles an empty series', () => {
    expect(labelledIndices([]).size).toBe(0)
  })
})

describe('truncateLabel', () => {
  it('ellipsises rather than hard-cutting', () => {
    expect(truncateLabel('short', 10)).toBe('short')
    expect(truncateLabel('a-very-long-category', 8)).toBe('a-very-…')
  })
})

describe('stripCommonPrefix', () => {
  it('removes a shared prefix so truncation keeps what distinguishes labels', () => {
    // Without this, eighteen institution names all truncate to `Universita…` — worse than no
    // labels, because it looks like the chart is broken. Caught by rendering it and looking.
    const labels = ['Universitat Politecnica de Catalunya 1', 'Universitat Politecnica de Catalunya 2']
    expect(stripCommonPrefix(labels)).toEqual(['1', '2'])
  })

  it('leaves labels alone when the shared prefix is short', () => {
    // A two-character overlap is not what makes a label unreadable, and stripping it would only
    // make the labels cryptic.
    expect(stripCommonPrefix(['abx', 'aby'])).toEqual(['abx', 'aby'])
  })

  it('leaves labels alone when stripping would empty one', () => {
    expect(stripCommonPrefix(['Universitat', 'Universitat Politecnica']))
      .toEqual(['Universitat', 'Universitat Politecnica'])
  })

  it('leaves labels alone when they share nothing', () => {
    expect(stripCommonPrefix(['alpha', 'beta'])).toEqual(['alpha', 'beta'])
  })

  it('is a no-op for a single label', () => {
    expect(stripCommonPrefix(['only one'])).toEqual(['only one'])
  })
})

describe('buildSeries ordering', () => {
  it('sorts a LINE by its x values, because a line asserts a progression', () => {
    // Unsorted, this draws a scribble that reads as a trend.
    const r = result(['year', 'n'], [[2021, 5], [2019, 1], [2020, 3]])
    const points = buildSeries(r, { kind: 'line', labelColumn: 0, valueColumn: 1 })
    expect(points.map(p => p.label)).toEqual(['2019', '2020', '2021'])
  })

  it('leaves a BAR in the result order, because bars assert no progression', () => {
    // The user's ORDER BY is a statement about how they want it read; re-ranking would make the
    // chart disagree with the table beside it.
    const r = result(['k', 'n'], [['c', 3], ['a', 9], ['b', 1]])
    const points = buildSeries(r, { kind: 'bar', labelColumn: 0, valueColumn: 1 })
    expect(points.map(p => p.label)).toEqual(['c', 'a', 'b'])
  })

  it('leaves a line alone when its x is not numeric — there is nothing to sort by', () => {
    const r = result(['k', 'n'], [['c', 3], ['a', 9]])
    const points = buildSeries(r, { kind: 'line', labelColumn: 0, valueColumn: 1 })
    expect(points.map(p => p.label)).toEqual(['c', 'a'])
  })
})
