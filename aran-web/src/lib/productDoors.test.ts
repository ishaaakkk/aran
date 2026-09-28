import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  JDBC_DRIVER_ARTIFACT, allRowsDownload, builtAgainstLabel, downloadFailureMessage, connectionDetails, deleteImpact, deleteNeedsTypedName, focusAfterClose,
  openButtonTestId, previewsOnOpen, productDoors,
} from './productDoors'
import type { DataProduct } from '@/services/types'

const repoFile = (path: string) => readFileSync(join(process.cwd(), '..', path), 'utf8')

function product(overrides: Partial<DataProduct> = {}): DataProduct {
  return {
    id: 'prod-1',
    projectId: 'proj-1',
    name: 'Papers per year',
    description: null,
    oql: 'SELECT Paper.year FROM Paper',
    schemaVersionId: 'sv-2',
    accessMode: 'MATERIALIZED',
    status: 'READY',
    snapshotId: 'snap-1',
    rowCount: 42,
    materializedAt: '2026-08-01T10:00:00Z',
    lastError: null,
    createdBy: null,
    createdAt: '2026-07-01T09:00:00Z',
    tableName: 'Papers_per_year',
    builtAgainstVersionId: 'sv-3',
    ...overrides,
  }
}

const VERSIONS = { 'sv-2': 2, 'sv-3': 3 }

describe('builtAgainstLabel (UI-OD12)', () => {
  it('names the version the rows were built under, and the one the product was defined against when they differ', () => {
    expect(builtAgainstLabel(product(), VERSIONS)).toBe('built against v3 · defined against v2')
  })

  it('says only "built against" when the two agree', () => {
    expect(builtAgainstLabel(product({ builtAgainstVersionId: 'sv-2' }), VERSIONS)).toBe('built against v2')
  })

  it('does not invent a version for a build that predates the record', () => {
    expect(builtAgainstLabel(product({ builtAgainstVersionId: null }), VERSIONS))
      .toBe('built before builds recorded their schema version')
  })

  it('says a never-built product is not built, and says nothing for a VIRTUAL one (it has no build)', () => {
    expect(builtAgainstLabel(product({ snapshotId: null, status: 'DRAFT', builtAgainstVersionId: null }), VERSIONS))
      .toBe('not built yet')
    expect(builtAgainstLabel(product({ accessMode: 'VIRTUAL', snapshotId: null, builtAgainstVersionId: null }), VERSIONS))
      .toBeNull()
  })

  it('falls back to a short id for a version the list does not know', () => {
    expect(builtAgainstLabel(product({ builtAgainstVersionId: 'abcdef0123456789' }), VERSIONS))
      .toBe('built against abcdef01 · defined against v2')
  })
})

describe('productDoors (J6-F4) — where a READY product can be read from', () => {
  it('serves a built MATERIALIZED product over JDBC and Iceberg under its catalog table name', () => {
    const doors = productDoors(product())
    expect(doors.jdbc).toBe('SELECT * FROM products.Papers_per_year')
    expect(doors.iceberg).toEqual({ served: true, table: 'products.Papers_per_year' })
  })

  it('serves a VIRTUAL product over JDBC, and says why Iceberg does not', () => {
    const doors = productDoors(product({ accessMode: 'VIRTUAL', snapshotId: null }))
    expect(doors.jdbc).toBe('SELECT * FROM products.Papers_per_year')
    expect(doors.iceberg).toMatchObject({ served: false, reason: expect.stringMatching(/no files/i) })
  })

  it('believes the server when it says the Iceberg catalog does not serve a built product (columns never recorded)', () => {
    const doors = productDoors(product({ servedOverIceberg: false }))
    expect(doors.jdbc).toBe('SELECT * FROM products.Papers_per_year')
    expect(doors.iceberg).toMatchObject({ served: false, reason: expect.stringMatching(/recorded their columns/) })
    expect(deleteImpact(product({ servedOverIceberg: false })).join('\n')).not.toMatch(/Iceberg/)
  })

  it('offers no door for a product that has never been built', () => {
    const doors = productDoors(product({ status: 'DRAFT', snapshotId: null }))
    expect(doors.jdbc).toBeNull()
    expect(doors.iceberg).toMatchObject({ served: false, reason: expect.stringMatching(/refresh/i) })
  })
})

describe('previewsOnOpen (J6-F19) — opening a product never queries its sources', () => {
  it('loads a built MATERIALIZED product\'s snapshot preview on open', () => {
    expect(previewsOnOpen(product())).toBe(true)
  })

  it('has nothing to load for a MATERIALIZED product never built', () => {
    expect(previewsOnOpen(product({ status: 'DRAFT', snapshotId: null }))).toBe(false)
  })

  it('never runs a VIRTUAL product live just because it was opened', () => {
    expect(previewsOnOpen(product({ accessMode: 'VIRTUAL', snapshotId: null }))).toBe(false)
  })

  it('reads a payload without accessMode as MATERIALIZED', () => {
    expect(previewsOnOpen(product({ accessMode: undefined }))).toBe(true)
  })
})

describe('focusAfterClose — where keyboard focus lands when the detail closes', () => {
  const list = [product({ id: 'a', name: 'alpha' }), product({ id: 'b', name: 'beta' }), product({ id: 'c', name: 'gamma' })]

  it('returns to the product it was opened from', () => {
    expect(focusAfterClose('b', list, false)).toBe('beta')
    expect(openButtonTestId('beta')).toBe('product-open-beta')
  })

  it('moves to the next row after a delete, or the previous one at the end', () => {
    expect(focusAfterClose('b', list, true)).toBe('gamma')
    expect(focusAfterClose('c', list, true)).toBe('beta')
  })

  it('has nowhere to go when the last product was deleted, or the product is not listed', () => {
    expect(focusAfterClose('a', [list[0]], true)).toBeNull()
    expect(focusAfterClose('zzz', list, false)).toBeNull()
  })
})

describe('allRowsDownload / downloadFailureMessage (J6-F4) — getting every row out', () => {
  it('offers a built product that the file route serves, and any VIRTUAL one', () => {
    expect(allRowsDownload(product())).toEqual({ available: true })
    expect(allRowsDownload(product({ accessMode: 'VIRTUAL', snapshotId: null }))).toEqual({ available: true })
  })

  it('says why not for a never-built product, or one the file route does not serve', () => {
    expect(allRowsDownload(product({ status: 'DRAFT', snapshotId: null })))
      .toMatchObject({ available: false, reason: expect.stringMatching(/refresh it first/) })
    expect(allRowsDownload(product({ servedOverIceberg: false })))
      .toMatchObject({ available: false, reason: expect.stringMatching(/refresh it once/) })
  })

  it('turns the route\'s 404 into what happened and what to do, not "download failed: 404"', () => {
    const notFound = Object.assign(new Error('download failed: 404'), { status: 404 })
    expect(downloadFailureMessage(notFound)).toMatch(/no longer served — reload the list .* or refresh the product/)
    expect(downloadFailureMessage(Object.assign(new Error('egress limit reached'), { status: 429 })))
      .toBe('egress limit reached')
    expect(downloadFailureMessage('boom')).toBe('boom')
  })
})

describe('connectionDetails (J6-F4) — how a client reaches the doors', () => {
  const c = connectionDetails('https://aran.example/')

  it("builds the Avatica URL docs-site's jdbc.md documents, on this deployment's origin, with a token placeholder", () => {
    expect(c.jdbcUrl).toBe(
      'jdbc:avatica:remote:url=https://aran.example/api/jdbc;serialization=json;authentication=BASIC;'
        + 'avatica_user=aran;avatica_password=<your-token>',
    )
    expect(repoFile('docs-site/docs/programmatic-access/jdbc.md'))
      .toContain('/api/jdbc;serialization=json;authentication=BASIC;avatica_user=aran;avatica_password=<your-token>')
  })

  it('names the driver class the docs name, from the avatica-core version the server is built with', () => {
    expect(c.jdbcDriverClass).toBe('org.apache.calcite.avatica.remote.Driver')
    expect(repoFile('docs-site/docs/programmatic-access/jdbc.md')).toContain(`Driver class: ${c.jdbcDriverClass}`)
    const version = JDBC_DRIVER_ARTIFACT.replace('avatica-core ', '')
    expect(repoFile('aran-application/build.gradle')).toContain(`org.apache.calcite.avatica:avatica-core:${version}'`)
  })

  it('gives the Iceberg REST catalog URI without /v1 (clients append it) and the one namespace', () => {
    expect(c.icebergCatalogUri).toBe('https://aran.example/api/iceberg')
    expect(c.icebergNamespace).toBe('products')
  })
})

describe('deleteImpact / deleteNeedsTypedName (J6-F17)', () => {
  it('names every door that stops resolving for a READY product', () => {
    const lines = deleteImpact(product())
    expect(lines.join('\n')).toContain('products.Papers_per_year')
    expect(lines.join('\n')).toMatch(/JDBC/)
    expect(lines.join('\n')).toMatch(/Iceberg/)
    expect(lines.join('\n')).toMatch(/agents/i)
    expect(deleteNeedsTypedName(product())).toBe(true)
  })

  it('asks for no typed name for a DRAFT nobody can read, and says nothing reads it', () => {
    const draft = product({ status: 'DRAFT', snapshotId: null })
    expect(deleteNeedsTypedName(draft)).toBe(false)
    expect(deleteImpact(draft).join('\n')).toMatch(/nothing reads it yet/i)
  })

  it('treats a STALE product as readable — it is still served', () => {
    expect(deleteNeedsTypedName(product({ status: 'STALE' }))).toBe(true)
  })

  it('asks for the name of a FAILED product that still serves its previous snapshot over JDBC', () => {
    const failed = product({ status: 'FAILED', lastError: 'boom' })
    expect(deleteNeedsTypedName(failed)).toBe(true)
    expect(deleteImpact(failed).join('\n')).toMatch(/JDBC clients: SELECT \* FROM products\.Papers_per_year stops resolving/)
  })

  it('does not claim agents lose a product the sidecar already hides (FAILED, MATERIALIZING)', () => {
    for (const status of ['FAILED', 'MATERIALIZING'] as const) {
      expect(deleteImpact(product({ status })).join('\n')).not.toMatch(/Agents \(MCP\)/)
    }
    expect(deleteImpact(product({ status: 'STALE' })).join('\n')).toMatch(/Agents \(MCP\)/)
  })

  it('always asks for the name of a VIRTUAL product — it is readable the moment it exists', () => {
    expect(deleteNeedsTypedName(product({ accessMode: 'VIRTUAL', snapshotId: null }))).toBe(true)
  })
})
