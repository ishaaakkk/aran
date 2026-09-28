import { describe, expect, it } from 'vitest'
import { describeEnrolCheck } from './enrolCheck'

/**
 * Third review of UA2 (MINOR): an enrolment that timed out and had not landed yet was a 4 s toast,
 * after which Enroll was clickable again — and a second enrolment of a dataset the first then
 * enrolled changes nothing, and one that races the first can collide on model_source's key.
 */
describe('describeEnrolCheck', () => {
  it('is settled — nothing to say — once the dataset has sources', () => {
    expect(describeEnrolCheck(2)).toBeNull()
  })

  it('says "not enrolled yet", and to check again rather than enrol again, when the read finds none', () => {
    const check = describeEnrolCheck(0)
    expect(check?.kind).toBe('not-yet')
    expect(check?.message).toMatch(/^Not enrolled yet/)
    expect(check?.message).toContain('Check again')
    expect(check?.message).toContain('a second can collide with it')
    expect(check?.message).not.toContain('is refused')
  })

  it('claims nothing about the enrolment when the model could not be read', () => {
    const check = describeEnrolCheck(undefined)
    expect(check?.kind).toBe('unknown')
    expect(check?.message).toContain('could not be read')
    expect(check?.message).not.toContain('Not enrolled yet')
  })
})
