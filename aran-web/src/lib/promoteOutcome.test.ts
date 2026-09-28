import { describe, expect, it } from 'vitest'
import { describePromoteFailure, promoteNeedsRecheck } from './promoteOutcome'

const conflict = (message: string) => Object.assign(new Error(message), { kind: 'conflict', errorCode: 'NOT_A_DRAFT' })
const timeout = () => Object.assign(new Error('This is still working on the server — the page stopped waiting after 150 s.'),
  { kind: 'timeout', mayStillBeRunning: true, correlationId: 'corr-1' })

/**
 * Review of UA2: a promote that timed out, pressed again, answered 409 NOT_A_DRAFT — and the panel
 * said "Promotion failed" about a promote that had succeeded.
 */
describe('describePromoteFailure', () => {
  it('a NOT_A_DRAFT whose run is now the active one is a promote that landed, not a failure', () => {
    const err = conflict('This compile run cannot be promoted: it was already promoted — an earlier attempt may have landed.')
    expect(promoteNeedsRecheck(err)).toBe(true)
    expect(describePromoteFailure(err, 'run-1', 'run-1')).toEqual({ kind: 'landed' })
  })

  it('a NOT_A_DRAFT for a run a newer compile superseded is still a failure, in the server\'s words', () => {
    const err = conflict('This compile run cannot be promoted: a newer compile of this model replaced it.')
    const outcome = describePromoteFailure(err, 'run-1', 'run-0')
    expect(outcome.kind).toBe('failed')
    expect(outcome).toMatchObject({ message: expect.stringContaining('a newer compile of this model replaced it') })
  })

  it('a promote the page stopped waiting for is pending until the model shows it landed', () => {
    expect(promoteNeedsRecheck(timeout())).toBe(true)
    expect(describePromoteFailure(timeout(), 'run-1', null)).toMatchObject({ kind: 'pending' })
    expect(describePromoteFailure(timeout(), 'run-1', 'run-1')).toEqual({ kind: 'landed' })
  })

  it('a NOT_A_DRAFT whose re-read failed is unconfirmed, in the server\'s words, never "Promotion failed"', () => {
    // Second review of UA2: the re-read after a NOT_A_DRAFT also failed, and the panel read
    // "Promotion failed: This compile run cannot be promoted: it was already promoted…".
    const err = conflict('This compile run cannot be promoted: it was already promoted — an earlier attempt may have landed.')
    const outcome = describePromoteFailure(err, 'run-1', undefined)
    expect(outcome.kind).toBe('unconfirmed')
    expect(outcome).toMatchObject({ message: expect.stringMatching(/^This compile run cannot be promoted: it was already promoted/) })
    expect(outcome).toMatchObject({ message: expect.stringContaining('check again') })
    expect(outcome).not.toMatchObject({ message: expect.stringContaining('Promotion failed') })
  })

  it('any other failure is reported as one, with no re-read', () => {
    const err = Object.assign(new Error('Aran hit an unexpected error (HTTP 500).'), { kind: 'server' })
    expect(promoteNeedsRecheck(err)).toBe(false)
    expect(describePromoteFailure(err, 'run-1', undefined)).toEqual({
      kind: 'failed', message: 'Promotion failed: Aran hit an unexpected error (HTTP 500).',
    })
  })
})
