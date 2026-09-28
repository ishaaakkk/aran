import { describe, expect, it } from 'vitest'
import { dismissOutcome } from './discardChanges'

/**
 * UI audit J2-F26, and the review of its fix: dismissing an edited form asks first — and the
 * question must be answerable from the keyboard. A second Escape (or backdrop click, or Cancel)
 * while it is asking means "keep editing": only the button named Discard throws work away.
 */
describe('dismissOutcome (J2-F26)', () => {
  it('closes a form with nothing to lose', () => {
    expect(dismissOutcome({ dirty: false, asking: false })).toBe('close')
  })

  it('asks before closing an edited form', () => {
    expect(dismissOutcome({ dirty: true, asking: false })).toBe('ask')
  })

  it('takes a second dismissal while asking as "keep editing", never as discard', () => {
    expect(dismissOutcome({ dirty: true, asking: true })).toBe('keep')
    expect(dismissOutcome({ dirty: false, asking: true })).toBe('keep')
  })
})
