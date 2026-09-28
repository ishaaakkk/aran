/// <reference types="node" />
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * UI copy the audit retired (§3, A1 truth pass): operator jargon shown to analysts, and verbs
 * that disagree with the button they describe. The god components that render most of it
 * (AlignmentsView, DatasetsListView) cannot be rendered without a DOM, so the guard is a scan of
 * the source text — the `tourRegistry.test` precedent. A phrase here must not come back in any
 * non-test source file, comments included (a comment is where an old string waits to be pasted
 * back).
 */
const RETIRED: Array<[phrase: string, finding: string]> = [
  // J4-F22 — one verb set (Rebuild, Promote), no model-theory labels.
  ['Run Bootstrapping Operator', 'J4-F22'],
  ['Force Bootstrap', 'J4-F22'],
  ['N-ary Model Mapping', 'J4-F22'],
  // J3-F19 — plain language on the profile and discovery screens.
  ['$ aran invoke operators/profiling', 'J3-F19'],
  ['Querying FREYJA', 'J3-F19'],
  ['truncnorm', 'J3-F19'],
  ['MJ &amp; K Formula', 'J3-F19'],
  ['Phonetic Soundex signature', 'J3-F19'],
  // J8-F22 / J1-F14 — operator guidance is not an analyst's instruction, and one port is wrong
  // on one of the two stacks.
  ['Raise ARAN_PLANNER_PRODUCTION_BUDGET', 'J8-F22'],
  ['aran-application on port 8081', 'J8-F22'],
  ['Keycloak IAM on port 8080', 'J1-F14'],
  // J6-F1 — a product's schema version is provenance, not a pin.
  ['data product pinned to the active schema version', 'J6-F1'],
  ['pinned to a schema version', 'J6-F1'],
  // J6-F2 — "live" only when every leaf is federated; a virtual product may read an old upload.
  ['live — recomputed now', 'J6-F2'],
]

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) return sources(path)
    return /\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [path] : []
  })
}

describe('retired UI copy stays retired', () => {
  const files = sources(join(process.cwd(), 'src')).map((f) => ({ f, text: readFileSync(f, 'utf8') }))

  it.each(RETIRED)('"%s" (%s) appears in no source file', (phrase) => {
    const hits = files.filter(({ text }) => text.includes(phrase)).map(({ f }) => f)
    expect(hits).toEqual([])
  })
})
