import type { QueryRunResult } from '../services/types'
import { classifyColumns, type ChartKind, type ColumnKind } from './chartModel'

/**
 * Choosing a chart, after Mackinlay's APT (ACM TOG, 1986).
 *
 * <p>Two criteria, in order:
 *
 * <ol>
 *   <li><b>Expressiveness</b> — an encoding must express all the facts in the data and
 *       <em>no facts that are not in it</em>. This is a hard filter, and it is the half that
 *       matters: a bar chart over `SELECT Paper.title, Paper.year` asserts a comparison between
 *       500 unrelated papers, and drawing a year as a bar height asserts that 2020 is twice
 *       1010. Both are expressible; neither is true.</li>
 *   <li><b>Effectiveness</b> — among expressive encodings, prefer the one a reader decodes most
 *       accurately. The ranking is Cleveland &amp; McGill's (JASA, 1984): position on a common
 *       scale beats length, which beats angle, area and colour.</li>
 * </ol>
 *
 * <p>Deliberately not Draco or CompassQL: those solve the same problem with a constraint solver
 * or a full visualization grammar, and both are large dependencies for a space this small. The
 * criteria are a filter and a lookup table; the machinery is what we are skipping, not the ideas.
 *
 * <p>Every candidate carries its own reasoning, and rejected ones carry why. A recommender that
 * cannot explain itself is worse than a default you can override — the user already knows what
 * they asked for.
 */

/** Cleveland & McGill's ranking of elementary perceptual tasks, as a score. Higher decodes better. */
const CHANNEL_ACCURACY = {
  /** Position along a common scale — the most accurately decoded encoding there is. */
  positionCommonScale: 100,
  /** Length: accurate, but less so than position. A bar encodes its measure this way. */
  length: 90,
  /** Angle/slope. */
  angle: 70,
  /** Area. */
  area: 50,
} as const

/** What the query says a column is. `VALUE` means "no grouping happened". */
export type ColumnRole = 'KEY' | 'MEASURE' | 'VALUE'

export interface Candidate {
  kind: ChartKind
  labelColumn: number
  valueColumn: number
  /** Effectiveness score; only meaningful among expressive candidates. */
  score: number
  /** Why this encoding suits the data — shown to the user. */
  rationale: string
}

export interface Rejection {
  kind: ChartKind
  /** Why this encoding would assert something untrue. */
  reason: string
}

export interface Recommendation {
  /** Best first. Empty when nothing is expressive. */
  candidates: Candidate[]
  /** Encodings considered and ruled out, with the reason. */
  rejected: Rejection[]
  /** When no candidate survives, what the user should do about it. */
  advice?: string
}

/** Facts about the result that the criteria are evaluated against. */
export interface DataFacts {
  rowCount: number
  kinds: ColumnKind[]
  roles: ColumnRole[]
  /** Distinct values per column, over the returned rows. */
  distinct: number[]
  /** Whether each numeric column's values are already ascending — a line needs an ordered x. */
  ascending: boolean[]
}

/**
 * Everything the criteria need, measured from the result.
 *
 * <p>Roles come from the query when it was OQL. When they are absent (raw SQL), they are inferred:
 * a column whose values are near-unique is not a category, so treating it as `VALUE` reproduces
 * the "this is a list, not a comparison" conclusion without the query's help.
 */
export function analyse(result: QueryRunResult): DataFacts {
  const kinds = classifyColumns(result)
  const distinct = kinds.map((_, i) => new Set(result.rows.map(row => row[i])).size)

  const ascending = kinds.map((kind, i) => {
    if (kind !== 'number') return false
    let previous = -Infinity
    for (const row of result.rows) {
      const cell = row[i]
      if (typeof cell !== 'number') continue
      if (cell < previous) return false
      previous = cell
    }
    return true
  })

  const roles: ColumnRole[] = result.columnRoles
    ?? kinds.map((_, i) =>
      // No query semantics: a near-unique column is a row identifier, not a group key.
      distinct[i] >= result.rows.length && result.rows.length > 1 ? 'VALUE' : 'KEY')

  return { rowCount: result.rows.length, kinds, roles, distinct, ascending }
}

/**
 * Rank the encodings that express this result, and name the ones that do not.
 *
 * <p>`aggregated` is the pivotal fact. A grouped query's rows are summaries — each one stands for
 * many — so comparing them is exactly what the query asked for. An ungrouped projection's rows are
 * individuals, and a bar chart over them invents a comparison.
 */
export function recommend(result: QueryRunResult): Recommendation {
  const facts = analyse(result)
  const candidates: Candidate[] = []
  const rejected: Rejection[] = []

  if (facts.rowCount === 0) {
    return { candidates: [], rejected: [], advice: 'The query returned no rows.' }
  }
  if (facts.rowCount === 1) {
    // A single value is a number. A one-bar bar chart adds nothing to reading it.
    return { candidates: [], rejected: [], advice: 'One row — the value is easier to read in the table.' }
  }

  const measures = facts.roles.flatMap((r, i) =>
    (r === 'MEASURE' || (r === 'VALUE' && facts.kinds[i] === 'number')) ? [i] : [])
  const keys = facts.roles.flatMap((r, i) => (r === 'KEY' ? [i] : []))
  const aggregated = facts.roles.includes('MEASURE')

  if (measures.length === 0) {
    return {
      candidates: [], rejected: [],
      advice: 'No numeric column to measure. Try an aggregate such as COUNT(*).',
    }
  }

  const measure = measures[0]
  const measureName = result.headerNames[measure]

  // ── bar: a nominal key on position, the measure on length ──────────────────
  const nominalKey = keys.find(i => facts.kinds[i] !== 'number') ?? keys[0]
  if (nominalKey === undefined) {
    rejected.push({
      kind: 'bar',
      reason: aggregated
        ? 'no group key to compare across'
        : 'the rows are individual records, not groups — a bar chart would assert a comparison '
          + 'between them that the query never made',
    })
  } else if (!aggregated && facts.distinct[nominalKey] >= facts.rowCount) {
    // The expressiveness failure that matters most, and the one a type-only heuristic misses.
    rejected.push({
      kind: 'bar',
      reason: `every ${result.headerNames[nominalKey]} is distinct — these are individual rows, `
        + 'not categories. GROUP BY to compare them.',
    })
  } else {
    candidates.push({
      kind: 'bar', labelColumn: nominalKey, valueColumn: measure,
      score: CHANNEL_ACCURACY.length - lengthPenalty(facts, nominalKey),
      rationale: `${measureName} compared across ${result.headerNames[nominalKey]}`
        + ` — ${facts.distinct[nominalKey]} categories, measured by bar length.`,
    })
  }

  // ── line: an ordered key on position, the measure on position ──────────────
  const orderedKey = keys.find(i => facts.kinds[i] === 'number')
    ?? (aggregated ? undefined : facts.kinds.findIndex(k => k === 'number'))
  if (orderedKey !== undefined && orderedKey >= 0 && orderedKey !== measure) {
    candidates.push({
      kind: 'line', labelColumn: orderedKey, valueColumn: measure,
      // Both axes on a common scale — the most accurately decoded pairing — but only legitimate
      // because the x values are ordered. buildSeries sorts by x so the line cannot be a scribble.
      score: CHANNEL_ACCURACY.positionCommonScale
        - (facts.ascending[orderedKey] ? 0 : 4),
      rationale: `${measureName} over ${result.headerNames[orderedKey]}`
        + ` — an ordered axis, so position carries both values.`,
    })
  } else {
    rejected.push({
      kind: 'line',
      reason: 'no ordered column for the x axis — a line between unordered categories implies a '
        + 'progression that does not exist',
    })
  }

  candidates.sort((a, b) => b.score - a.score)
  return { candidates, rejected }
}

/**
 * Bar charts degrade as categories multiply: past a few dozen the marks are too thin to compare,
 * which is a real loss of the accuracy the encoding was chosen for.
 */
function lengthPenalty(facts: DataFacts, keyColumn: number): number {
  const categories = facts.distinct[keyColumn]
  if (categories <= 12) return 0
  if (categories <= 30) return 3
  return 8
}
