import { describe, expect, it } from 'vitest'
import { CONCEPTS } from './concepts'

/**
 * The InfoTip paragraphs are the product's own teaching copy, shown on several screens each —
 * so a false one is repeated everywhere its concept appears. Each case pins a claim the UI audit
 * (§3, A1 truth pass) found false at dd47f78d, by the finding that found it.
 */
describe('CONCEPTS say only what this build does', () => {
  it('wrapper: "Optimizable" is scoped to what the planner actually expands (J4-F12)', () => {
    expect(CONCEPTS.wrapper).toContain('enforced denial constraint')
    expect(CONCEPTS.wrapper).toContain('DECIMAL or TIMESTAMP')
    expect(CONCEPTS.wrapper).toContain('Rebuild and Promote')
  })

  it('accessMode: a virtual product is as fresh as its sources, and says what it gives up (J6-F2)', () => {
    expect(CONCEPTS.accessMode).not.toMatch(/always current|always fresh/i)
    expect(CONCEPTS.accessMode).toContain('as of its last upload or refresh')
    expect(CONCEPTS.accessMode).toContain('not served over Iceberg')
  })

  it('repository: no "folder of files" on a server — file-system repositories are upload-only (J2-F30)', () => {
    expect(CONCEPTS.repository).not.toContain('folder of files')
    expect(CONCEPTS.repository).toContain('upload')
  })

  it('bootstrapping: a live dataset is not snapshotted into the lake (J2-F30)', () => {
    expect(CONCEPTS.bootstrapping).not.toMatch(/Aran snapshots its data into the lake and/)
    expect(CONCEPTS.bootstrapping).toContain('live')
  })

  it('federation: an import from a federated repository stays live without an interval refresh (J2-F4)', () => {
    expect(CONCEPTS.federation).toContain('interval refresh')
  })

  it('schemaVersion: old versions are compared and restored, only ACTIVE is queried (J4-F25)', () => {
    expect(CONCEPTS.schemaVersion).not.toMatch(/stay queryable/i)
    expect(CONCEPTS.schemaVersion).not.toMatch(/stay pinned/i)
    expect(CONCEPTS.schemaVersion).toContain('only the ACTIVE one is queried')
    expect(CONCEPTS.schemaVersion).toContain('STALE')
  })

  it('dataProduct: names the surfaces that really serve products (J6-F2)', () => {
    // The REST export (/api/query/export) runs an OQL query; it does not serve products.
    expect(CONCEPTS.dataProduct).not.toContain('REST export')
    expect(CONCEPTS.dataProduct).toContain('Iceberg')
  })
})
