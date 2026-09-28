import { describe, expect, it } from 'vitest'
import { ownerLabel } from './projectOwner'

/** J1-F10, second review of UA2: an 8-character UUID prefix is neither a name nor a findable id. */
describe('ownerLabel', () => {
  it('names the caller', () => {
    expect(ownerLabel('u-1', 'u-1')).toBe('you')
  })

  it('never shows a truncated id for anyone else', () => {
    const label = ownerLabel('3f2a9c1e-0000-4000-8000-000000000000', 'u-1')
    expect(label).toBe('someone else')
    expect(label).not.toContain('3f2a9c1e')
  })

  it('says nothing is recorded when nothing is', () => {
    expect(ownerLabel(null, 'u-1')).toBe('—')
    expect(ownerLabel(undefined, null)).toBe('—')
  })
})
