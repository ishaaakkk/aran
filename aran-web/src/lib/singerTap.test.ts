import { describe, it, expect } from 'vitest'
import { importKindOf, tapCatalogState, tapConfigPayload, MASKED_SECRET } from './singerTap'
import type { DataRepository, RelationalEngineInfo, SingerTapInfo } from '../services/types'

/**
 * The decisions the Singer registration surfaces make before any pixel is drawn (T6). Rendering
 * is asserted by the build and the e2e suite; what is worth a unit test is the three-state
 * catalogue and the payload filter, because getting either wrong produces a form that looks
 * perfectly fine and cannot save.
 */

const TAP: SingerTapInfo = {
  id: 'exchangeratesapi',
  label: 'Exchange Rates API',
  configKeys: ['base', 'start_date', 'api_key'],
  secretKeys: ['api_key'],
}

describe('tapCatalogState', () => {
  it('distinguishes "not answered yet" from "the feature is off"', () => {
    // Collapsing these two is how a form ends up telling a user the feature is disabled because
    // one request failed — the same mistake the repository-type list was written to avoid.
    expect(tapCatalogState(null)).toBe('loading')
    expect(tapCatalogState([])).toBe('off')
    expect(tapCatalogState([TAP])).toBe('ready')
  })

  it('says a failed request failed, instead of "loading taps…" forever (J2-F32)', () => {
    expect(tapCatalogState(null, true)).toBe('error')
  })
})

describe('importKindOf', () => {
  // What GET /api/relational-engines publishes, trimmed to the fields the decision reads.
  const ENGINES: RelationalEngineInfo[] = [
    { id: 'POSTGRESQL', displayName: 'PostgreSQL', defaultPort: 5432, supportsFederation: true, bulkExtract: 'LAKE_NATIVE', bulkExtractReady: true },
    { id: 'MYSQL', displayName: 'MySQL', defaultPort: 3306, supportsFederation: true, bulkExtract: 'JDBC_GENERIC', bulkExtractReady: true },
    { id: 'HBASE', displayName: 'HBase', defaultPort: 2181, supportsFederation: false, bulkExtract: 'NONE', bulkExtractReady: false },
  ]

  it('calls a tap repository’s relations streams and a relational one’s tables', () => {
    expect(importKindOf({ type: 'SINGER_TAP' } as DataRepository, null)).toBe('stream')
    expect(importKindOf({ type: 'RELATIONAL_DB', engine: 'postgresql' } as DataRepository, ENGINES))
      .toBe('table')
  })

  it('offers table import for every engine whose extract this build implements, not only PostgreSQL (J2-F7)', () => {
    // The server's import path accepts a JDBC_GENERIC engine (DatasetService.requireImportableTable);
    // the dialog used to hard-code POSTGRESQL, so a MySQL user read "table import" under the
    // engine, registered, and found only "Upload file".
    expect(importKindOf({ type: 'RELATIONAL_DB', engine: 'MYSQL' } as DataRepository, ENGINES)).toBe('table')
    expect(importKindOf({ type: 'RELATIONAL_DB', engine: 'HBASE' } as DataRepository, ENGINES)).toBeNull()
  })

  it('claims no table import before the engine capabilities are known', () => {
    expect(importKindOf({ type: 'RELATIONAL_DB', engine: 'POSTGRESQL' } as DataRepository, null)).toBeNull()
    expect(importKindOf({ type: 'RELATIONAL_DB', engine: 'ORACLE' } as DataRepository, ENGINES)).toBeNull()
  })

  it('calls an Iceberg catalog’s relations iceberg tables (Gap 2)', () => {
    expect(importKindOf({ type: 'ICEBERG_REST' } as DataRepository, null)).toBe('iceberg table')
  })

  it('offers no name-based import for a kind that has none', () => {
    expect(importKindOf(undefined, ENGINES)).toBeNull()
    expect(importKindOf({ type: 'FILE_SYSTEM' } as DataRepository, ENGINES)).toBeNull()
  })
})

describe('tapConfigPayload', () => {
  it('sends exactly the keys the selected tap declares', () => {
    // A value left over from a previously selected tap would be refused by the server as an
    // unknown setting — a refusal naming a key the user never typed.
    const payload = tapConfigPayload(TAP, { base: 'EUR', leftover_from_other_tap: 'x' })

    expect(Object.keys(payload)).toEqual(['base', 'start_date', 'api_key'])
    expect(payload.base).toBe('EUR')
  })

  it('sends a declared key the user left blank, so a setting can be cleared', () => {
    expect(tapConfigPayload(TAP, {}).start_date).toBe('')
  })

  it('passes the mask straight through — the server reads it as "keep the stored one"', () => {
    const payload = tapConfigPayload(TAP, { api_key: MASKED_SECRET })

    expect(payload.api_key).toBe(MASKED_SECRET)
  })

  it('falls back to whatever was typed when no tap is selected', () => {
    expect(tapConfigPayload(null, { base: 'EUR' })).toEqual({ base: 'EUR' })
  })
})
