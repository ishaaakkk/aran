import { describe, expect, it } from 'vitest'
import { resultToCsv } from './resultCsv'
import type { QueryRunResult } from '@/services/types'

function result(rows: unknown[][], headerNames = ['title', 'year']): QueryRunResult {
  return {
    headers: headerNames.map((h) => `http://x/${h}`),
    headerNames,
    rows: rows as (string | null)[][],
    rowCount: rows.length,
    truncated: false,
    elapsedMs: 1,
  }
}

describe('resultToCsv — one CSV writer for a page of rows (workbench and product preview)', () => {
  it('writes the display headers and one line per row', () => {
    expect(resultToCsv(result([['RDF Graphs', '2022'], ['Linked Data', '2019']])))
      .toBe('title,year\nRDF Graphs,2022\nLinked Data,2019')
  })

  it('writes numbers and booleans as the engine sent them — cells are not all strings on the wire', () => {
    // run/run-oql/preview-parquet serialise through DuckDbExecutor.jsonSafe, so a numeric column
    // arrives as a JSON number; the old writer called .includes on it and threw.
    expect(resultToCsv(result([['RDF Graphs', 2022], ['x', true]]))).toBe('title,year\nRDF Graphs,2022\nx,true')
  })

  it('leaves NULL empty and quotes separators, quotes and newlines (RFC 4180)', () => {
    expect(resultToCsv(result([[null, 'a,b'], ['say "hi"', 'line\nbreak']])))
      .toBe('title,year\n,"a,b"\n"say ""hi""","line\nbreak"')
  })
})
