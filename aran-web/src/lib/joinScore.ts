export type JoinScoreTone = 'strong' | 'good' | 'weak'

/**
 * The two discovery methods do not score on one scale. The ML model's score spans 0–1. FREYJA's
 * `exact` score is CDF_MJ(mj) · CDF_K(k), with MJ estimated from a bottom-k hash sample of each
 * column's distinct values on the paper's scale, where identical value sets give 0.5 — so at the
 * Alignments view's default strictness (0.25) no pair scores above 0.495, and the ML bands painted
 * every exact candidate, the walkthrough's textbook join included, as weak.
 *
 * The exact bands are the scores of two equal-cardinality columns sharing 80% (strong) or 60%
 * (good) of their distinct values at that default strictness. A higher strictness lowers every
 * score, so fewer pairs reach them — which is what the slider is for.
 * freyja/tests/api/test_profile_api.py derives both anchors from real profiler output and holds a
 * foreign key and an unrelated serial key against a primary key to these bands.
 */
const BANDS = {
  ml: { strong: 0.8, good: 0.6 },
  exact: { strong: 0.29, good: 0.14 },
} as const

export function joinScoreTone(score: number, method: 'ml' | 'exact'): JoinScoreTone {
  const band = BANDS[method]
  if (score >= band.strong) return 'strong'
  if (score >= band.good) return 'good'
  return 'weak'
}
