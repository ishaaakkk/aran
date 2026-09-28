import type { QueryRunResult } from '../services/types'

/**
 * Deciding what — if anything — to draw for a query result, and the arithmetic to draw it.
 *
 * Kept free of React so it can be tested, which is how everything else in this folder is tested.
 * The rendering component owns only SVG.
 *
 * <p>This is possible at all because the engine now returns typed values: numbers arrive as
 * numbers rather than as `"42"`. Before that fix every column looked like text and nothing here
 * could tell a measure from a label.
 */

export type ChartKind = 'bar' | 'line'

export type ColumnKind = 'number' | 'text' | 'empty'

/** Bars past this stop being readable; the table view carries the rest. */
export const MAX_CATEGORIES = 30

export interface ChartSpec {
  kind: ChartKind
  /** Column index supplying each mark's label (the x axis). */
  labelColumn: number
  /** Column index supplying each mark's magnitude (the y axis). */
  valueColumn: number
}

/** Why a result cannot be charted, phrased for the user rather than for the developer. */
export interface NotChartable {
  reason: string
}

export interface ChartPoint {
  label: string
  value: number
}

/**
 * A column's type, from its actual values rather than from the header.
 *
 * A column of all nulls is `empty` rather than `number`: it can neither label nor measure
 * anything, and calling it numeric would let it be picked as an axis and draw a flat nothing.
 */
export function classifyColumn(result: QueryRunResult, index: number): ColumnKind {
  let sawValue = false
  for (const row of result.rows) {
    const cell = row[index]
    if (cell === null || cell === undefined || cell === '') continue
    sawValue = true
    if (typeof cell !== 'number') return 'text'
  }
  return sawValue ? 'number' : 'empty'
}

export function classifyColumns(result: QueryRunResult): ColumnKind[] {
  return result.headerNames.map((_, i) => classifyColumn(result, i))
}

/**
 * What to draw, or why not.
 *
 * The rules follow the shapes OQL actually produces:
 * - `SELECT Class.name, COUNT(*) … GROUP BY` — a text key and a measure → **bar**.
 * - `SELECT Class.year, COUNT(*) … GROUP BY` — both numeric, but the first is the group key
 *   and reads as an ordered axis → **line**. Getting this wrong would draw a year as a
 *   magnitude, which is the classic way a chart lies.
 * - Anything with no measure, or a single row, is not a chart. A one-bar bar chart is an
 *   anti-pattern: the number is already the chart.
 */
export function inferChartSpec(result: QueryRunResult): ChartSpec | NotChartable {
  if (result.rows.length === 0) {
    return { reason: 'The query returned no rows.' }
  }
  if (result.rows.length === 1) {
    // A single value is a number, not a chart — drawing one bar adds nothing to reading it.
    return { reason: 'One row — the value is easier to read in the table.' }
  }

  const kinds = classifyColumns(result)
  const numeric = kinds.flatMap((k, i) => (k === 'number' ? [i] : []))
  const text = kinds.flatMap((k, i) => (k === 'text' ? [i] : []))

  if (numeric.length === 0) {
    return { reason: 'No numeric column to measure. Try an aggregate such as COUNT(*).' }
  }

  const labelColumn = text.length > 0 ? text[0] : numeric[0]
  const valueColumn = numeric.find(i => i !== labelColumn)
  if (valueColumn === undefined) {
    // One numeric column and nothing to group it by: a list of numbers, not a comparison.
    return {
      reason: 'Only one numeric column and nothing to group by. '
        + 'Add a grouping column, or an aggregate such as COUNT(*).',
    }
  }

  return {
    kind: kinds[labelColumn] === 'number' ? 'line' : 'bar',
    labelColumn,
    valueColumn,
  }
}

export function isChartable(spec: ChartSpec | NotChartable): spec is ChartSpec {
  return 'kind' in spec
}

/**
 * The points to draw, in the result's own order.
 *
 * Deliberately not re-sorted by value: the user's ORDER BY is a statement about how they want
 * the data read, and quietly re-ranking it would make the chart disagree with the table beside it.
 * Rows whose measure is null are dropped — there is no honest height for "unknown".
 */
export function buildSeries(result: QueryRunResult, spec: ChartSpec): ChartPoint[] {
  const points: Array<ChartPoint & { sortKey: number | null }> = []
  for (const row of result.rows) {
    const raw = row[spec.valueColumn]
    if (typeof raw !== 'number' || !Number.isFinite(raw)) continue
    const label = row[spec.labelColumn]
    points.push({
      label: label === null || label === undefined ? '∅' : String(label),
      value: raw,
      sortKey: typeof label === 'number' ? label : null,
    })
  }

  // A line asserts a progression from each point to the next, so it must follow the x axis rather
  // than the result's row order. Without this, `SELECT Paper.year, Paper.citations` with no
  // ORDER BY draws a scribble that reads as a trend. Bars assert no such thing, so they keep the
  // user's own ordering — quietly re-ranking those would make the chart disagree with the table.
  if (spec.kind === 'line' && points.every(p => p.sortKey !== null)) {
    points.sort((a, b) => (a.sortKey as number) - (b.sortKey as number))
  }

  return points.slice(0, MAX_CATEGORIES).map(({ label, value }) => ({ label, value }))
}

/** How many points were dropped by the category cap, so the chart can say so. */
export function omittedCount(result: QueryRunResult, spec: ChartSpec): number {
  const drawable = result.rows.filter(r => typeof r[spec.valueColumn] === 'number').length
  return Math.max(0, drawable - MAX_CATEGORIES)
}

/**
 * A value axis that includes zero and ends on a round number.
 *
 * Zero is included for bars because a truncated bar axis exaggerates differences — the single
 * most effective way to mislead with a chart, and one that happens by accident whenever an axis
 * is fitted to the data.
 */
export function valueScale(points: ChartPoint[]): { min: number; max: number; ticks: number[] } {
  if (points.length === 0) return { min: 0, max: 1, ticks: [0, 1] }

  const values = points.map(p => p.value)
  const rawMax = Math.max(...values)
  const rawMin = Math.min(...values)

  // Negative values get a real negative arm rather than being clamped away.
  const lo = Math.min(0, rawMin)
  const hi = Math.max(0, rawMax)
  if (lo === hi) return { min: 0, max: 1, ticks: [0, 1] }

  const step = niceStep((hi - lo) / 4)
  const min = Math.floor(lo / step) * step
  const max = Math.ceil(hi / step) * step

  const ticks: number[] = []
  // Accumulating with a counter rather than `t += step` — repeated float addition drifts, and a
  // tick labelled 0.30000000000000004 is a real thing that happens.
  for (let i = 0; min + i * step <= max + step / 1000; i++) {
    ticks.push(roundToStep(min + i * step, step))
  }
  return { min, max, ticks }
}

/** 1, 2, 2.5 or 5 times a power of ten — the steps people read without thinking. */
function niceStep(rough: number): number {
  if (rough <= 0) return 1
  const magnitude = Math.pow(10, Math.floor(Math.log10(rough)))
  const normalised = rough / magnitude
  const nice = normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 2.5 ? 2.5 : normalised <= 5 ? 5 : 10
  return nice * magnitude
}

function roundToStep(value: number, step: number): number {
  const decimals = Math.max(0, -Math.floor(Math.log10(step)) + 1)
  return Number(value.toFixed(decimals))
}

/** Compact axis and label text: 12.3k rather than 12300. */
export function formatValue(n: number): string {
  const abs = Math.abs(n)
  if (abs >= 1_000_000) return `${+(n / 1_000_000).toFixed(1)}M`
  if (abs >= 1_000) return `${+(n / 1_000).toFixed(abs < 10_000 ? 1 : 0)}k`
  if (Number.isInteger(n)) return String(n)
  return String(+n.toFixed(2))
}

/**
 * Which points get a value drawn next to them.
 *
 * Never all of them: a number on every mark is chaos and goes unread, and the axis plus the
 * tooltip already carry the rest. Up to eight points are few enough to label individually;
 * beyond that only the extremes are worth naming.
 */
export function labelledIndices(points: ChartPoint[]): Set<number> {
  if (points.length === 0) return new Set()
  if (points.length <= 8) return new Set(points.map((_, i) => i))

  let maxAt = 0
  let minAt = 0
  points.forEach((p, i) => {
    if (p.value > points[maxAt].value) maxAt = i
    if (p.value < points[minAt].value) minAt = i
  })
  return new Set([maxAt, minAt])
}

/** Truncate a category label to fit its tick, with an ellipsis rather than a hard cut. */
export function truncateLabel(label: string, max: number): string {
  return label.length <= max ? label : `${label.slice(0, Math.max(1, max - 1))}…`
}

/**
 * Drop the prefix every label shares, so truncation keeps the part that distinguishes them.
 *
 * Without this, a column of institution names truncates to eighteen identical `Universita…`
 * ticks — visible in the rendered preview, and worse than no labels at all, because it looks
 * like the chart is broken rather than like the labels are long. Only applied when the shared
 * prefix is long enough to be the problem and every label keeps something after it.
 */
export function stripCommonPrefix(labels: string[]): string[] {
  if (labels.length < 2) return labels

  let prefix = labels[0]
  for (const label of labels.slice(1)) {
    let i = 0
    while (i < prefix.length && i < label.length && prefix[i] === label[i]) i++
    prefix = prefix.slice(0, i)
    if (prefix.length === 0) return labels
  }

  // Short prefixes are not what makes a label unreadable, and stripping one would just make the
  // labels cryptic. Only strip when every label still has something left to read.
  if (prefix.length < 6) return labels
  const stripped = labels.map(l => l.slice(prefix.length).trim())
  return stripped.every(l => l.length > 0) ? stripped : labels
}
