import { describe, expect, it } from 'vitest'
import { exampleOqlFrom, pythonExportSnippet } from './tokenSnippet'
import type { QueryCatalog } from '@/services/types'

function catalog(over: Partial<QueryCatalog> = {}): QueryCatalog {
  return {
    empty: false,
    classes: [
      {
        name: 'Publication',
        iri: 'http://x/Publication',
        sources: [{ datasetId: 'd1', columns: [{ globalName: 'title' }, { globalName: 'year' }] }],
      },
    ],
    objectProperties: [],
    joinProperties: [],
    ...over,
  } as unknown as QueryCatalog
}

describe('exampleOqlFrom — the Python example names a class this tenant has (J6-F18 / J7-F21)', () => {
  it('builds the query from the ACTIVE catalog, not a demo class', () => {
    const oql = exampleOqlFrom(catalog())
    expect(oql).toContain('Publication')
    expect(oql).toContain('title')
    expect(oql).not.toContain('Paper')
  })

  it('returns null when nothing is promoted, rather than a class that does not exist', () => {
    expect(exampleOqlFrom(catalog({ empty: true, classes: [] } as Partial<QueryCatalog>))).toBeNull()
    expect(exampleOqlFrom(null)).toBeNull()
  })
})

describe('pythonExportSnippet', () => {
  it('embeds the origin, the secret and the example query', () => {
    const s = pythonExportSnippet('https://aran.example', 'aran_pat_x', 'SELECT Publication.title FROM Publication')
    expect(s).toContain('"https://aran.example/api/query/export"')
    expect(s).toContain('Bearer aran_pat_x')
    expect(s).toContain('"oql": "SELECT Publication.title FROM Publication"')
  })

  it('writes a commented placeholder when there is no schema to name a class from', () => {
    const s = pythonExportSnippet('https://aran.example', 'aran_pat_x', null)
    expect(s).toMatch(/# .*promote/i)
    expect(s).toContain('<Class>')
  })

  it('escapes a double quote in the query so the Python stays valid', () => {
    const s = pythonExportSnippet('https://aran.example', 't', 'SELECT "My Class".title FROM "My Class"')
    expect(s).toContain('"oql": "SELECT \\"My Class\\".title FROM \\"My Class\\""')
  })
})
