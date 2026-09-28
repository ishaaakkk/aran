import { describe, expect, it } from 'vitest'
import { describeVersionActionTimeout } from './versionActionOutcome'
import type { SchemaVersion } from '@/services/types'

const version = (versionNumber: number, status: string): SchemaVersion => ({
  id: `id-${versionNumber}`, versionNumber, status, createdAt: '', createdBy: null, promotedAt: null, notes: null,
})

/**
 * Third review of UA2 (MINOR): on /versions a promote or rollback that outlived its 150 s deadline
 * said only "still working" in the modal, next to an enabled Rollback, with the list unrefreshed —
 * and a retried rollback that lands creates another identical ACTIVE version.
 */
describe('describeVersionActionTimeout', () => {
  it('a promote whose target is ACTIVE now landed', () => {
    const check = describeVersionActionTimeout('promote', version(5, 'DRAFT'), 5, [version(5, 'ACTIVE'), version(4, 'HISTORICAL')])
    expect(check).toEqual({ kind: 'landed', message: 'v5 is active — the promote finished after the page stopped waiting.' })
  })

  it('a promote whose target is still a draft is not-yet, and says to check rather than promote again', () => {
    const check = describeVersionActionTimeout('promote', version(5, 'DRAFT'), 5, [version(5, 'DRAFT'), version(4, 'ACTIVE')])
    expect(check.kind).toBe('not-yet')
    expect(check.message).toContain('rather than promoting again')
  })

  it('a rollback that produced a new ACTIVE version is named by that version, as most likely this rollback', () => {
    const check = describeVersionActionTimeout('rollback', version(3, 'HISTORICAL'), 7,
      [version(8, 'ACTIVE'), version(7, 'HISTORICAL'), version(3, 'HISTORICAL')])
    expect(check.kind).toBe('landed')
    expect(check.message).toMatch(/^v8 is active, created after this rollback to v3 was sent — most likely by it/)
  })

  it('a rollback with no newer ACTIVE version is not-yet, and says why rolling back again is not harmless', () => {
    const check = describeVersionActionTimeout('rollback', version(3, 'HISTORICAL'), 7,
      [version(7, 'ACTIVE'), version(3, 'HISTORICAL')])
    expect(check.kind).toBe('not-yet')
    expect(check.message).toContain('creates another identical version')
  })

  it('claims nothing when the list could not be read', () => {
    const check = describeVersionActionTimeout('rollback', version(3, 'HISTORICAL'), 7, null)
    expect(check.kind).toBe('unknown')
    expect(check.message).toContain('could not be read')
  })
})
