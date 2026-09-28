import { describe, it, expect } from 'vitest'
import { accessModeOf, ACCESS_MODE_COPY, productDefinitionNote } from './productAccessMode'
import type { DataProduct } from '../services/types'

function product(overrides: Partial<DataProduct> = {}): DataProduct {
  return {
    id: 'prod-1',
    projectId: 'proj-1',
    name: 'papers',
    description: null,
    oql: 'FIND Paper.title',
    schemaVersionId: 'sv-1',
    accessMode: 'MATERIALIZED',
    status: 'READY',
    snapshotId: 'snap-1',
    rowCount: 42,
    materializedAt: '2026-08-01T10:00:00Z',
    lastError: null,
    createdBy: null,
    createdAt: '2026-07-01T09:00:00Z',
    ...overrides,
  }
}

/** Constructed by hand so `accessMode` is genuinely absent, not set-then-deleted — the exact
 *  shape of a payload or fixture written before this field existed. */
const legacyProduct: DataProduct = {
  id: 'prod-legacy',
  projectId: 'proj-1',
  name: 'legacy_view',
  description: null,
  oql: 'FIND Paper.title',
  schemaVersionId: 'sv-1',
  status: 'READY',
  snapshotId: 'snap-1',
  rowCount: 7,
  materializedAt: '2026-01-01T00:00:00Z',
  lastError: null,
  createdBy: null,
  createdAt: '2026-01-01T00:00:00Z',
}

describe('accessModeOf', () => {
  it('defaults a missing accessMode to MATERIALIZED', () => {
    expect(accessModeOf(legacyProduct)).toBe('MATERIALIZED')
  })

  it('reads an explicit MATERIALIZED through unchanged', () => {
    expect(accessModeOf(product({ accessMode: 'MATERIALIZED' }))).toBe('MATERIALIZED')
  })

  it('reads an explicit VIRTUAL through unchanged', () => {
    expect(accessModeOf(product({ accessMode: 'VIRTUAL' }))).toBe('VIRTUAL')
  })
})

/**
 * J6-F1 / J6-F2 / J6-F16: the sentences that describe what a product's mode and version mean.
 * `schemaVersionId` is "a record, not an execution pin" (DataProduct.java): a refresh re-plans
 * the OQL under whatever schema is ACTIVE then, and a virtual read does so every time. Each was
 * worded as a guarantee the product does not make — "pinned", "always fresh", "materialize from
 * the live source".
 */
describe('what a product mode and version mean', () => {
  it('a MATERIALIZED product is defined against a version and refreshed under the active one', () => {
    const note = productDefinitionNote('MATERIALIZED', 'v2', 'v3')
    expect(note).not.toMatch(/pinned/i)
    expect(note).toContain('Defined against schema v2')
    expect(note).toContain('under the active schema (v3)')
    expect(note).toContain('rows may change')
  })

  it('does not claim a federated refresh always reads the live source (J6-F16)', () => {
    const note = productDefinitionNote('MATERIALIZED', 'v2', 'v3')
    expect(note).not.toContain('materialize from the live source')
    expect(note).toContain('lake copy')
  })

  it('a VIRTUAL product follows the active version and names what it gives up', () => {
    const note = productDefinitionNote('VIRTUAL', 'v2', 'v3')
    expect(note).not.toMatch(/always fresh/i)
    expect(note).toContain('active schema (v3)')
    expect(note).toContain('not served over Iceberg')
    expect(note).toContain('stays READY')
  })

  it('says so when no version is active rather than printing a placeholder', () => {
    const note = productDefinitionNote('MATERIALIZED', 'v2', null)
    expect(note).toContain('the active schema')
    expect(note).not.toMatch(/\(null\)|undefined/)
  })

  it('the VIRTUAL choice copy is not "always fresh"', () => {
    expect(ACCESS_MODE_COPY.VIRTUAL.choice).not.toMatch(/always fresh/i)
    expect(ACCESS_MODE_COPY.VIRTUAL.title).not.toMatch(/always fresh/i)
    expect(ACCESS_MODE_COPY.VIRTUAL.rowFreshness).toBe('recomputed on read')
  })

  // J6-F2 allows "live" only when every leaf is federated; a virtual product over a month-old
  // upload recomputes from that upload. The detail panel's preview header said "live" anyway.
  it('the preview header says recomputed, never "live", for a VIRTUAL product', () => {
    expect(ACCESS_MODE_COPY.VIRTUAL.previewHeader).toBe('recomputed now')
    expect(ACCESS_MODE_COPY.VIRTUAL.previewHeader).not.toMatch(/live/i)
    expect(ACCESS_MODE_COPY.MATERIALIZED.previewHeader).toBe('materialized snapshot')
  })
})
