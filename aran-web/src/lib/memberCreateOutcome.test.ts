import { describe, expect, it } from 'vitest'
import {
  CLOCK_ALLOWANCE_MS, describeMemberCreateFailure, memberCreateNeedsRelist, resetAfterLostPasswordCopy,
} from './memberCreateOutcome'
import type { TenantMember } from '@/services/types'

const NOW = Date.parse('2026-09-25T10:00:00Z')
const member = (joinedAt: string): TenantMember => ({
  userId: 'u-ana', email: 'ana@example.org', displayName: 'Ana', role: 'MEMBER', joinedAt,
})
const longStanding = member('2025-03-01T00:00:00Z')
const justJoined = member('2026-09-25T10:00:20Z')
const none = new Map<string, number>()

const timeout = () => Object.assign(
  new Error('This is still working on the server — the page stopped waiting after 30 s. Check the result before trying again.'),
  { kind: 'timeout', mayStillBeRunning: true, correlationId: 'corr-7' })
const alreadyMember = () => Object.assign(
  new Error('ana@example.org is already a member of this organization. If an earlier attempt to add them timed out, it did add them — use Reset password to give them a new one-time password.'),
  { kind: 'conflict', errorCode: 'ALREADY_A_MEMBER' })
const exists = () => Object.assign(
  new Error('That email already has an account. If an earlier attempt to add them timed out, it may still be finishing — reload the list of members.'),
  { kind: 'conflict', errorCode: 'ACCOUNT_EXISTS' })

describe('describeMemberCreateFailure', () => {
  it('re-lists after a timeout or a conflict, and only then', () => {
    expect(memberCreateNeedsRelist(timeout())).toBe(true)
    expect(memberCreateNeedsRelist(exists())).toBe(true)
    expect(memberCreateNeedsRelist(Object.assign(new Error('invalid'), { kind: 'invalid' }))).toBe(false)
  })

  /**
   * Third review of UA2 (MAJOR): an owner re-adding Ana, a member since March 2025, was told she
   * "is a member now — an earlier attempt, or this one, finished after the page stopped waiting",
   * next to a one-click Reset password that would have replaced her working password.
   */
  it('a conflict on a first submit, for someone who joined before it, is "already a member" — nothing about waiting', () => {
    const outcome = describeMemberCreateFailure(alreadyMember(), 'ana@example.org', [longStanding], none, NOW)
    expect(outcome.kind).toBe('already-member')
    expect(outcome.message).toMatch(/^Ana \(ana@example\.org\) is already a member — since .*2025/)
    expect(outcome.message).not.toContain('member now')
    expect(outcome.message).not.toContain('stopped waiting')
    expect(outcome.message).not.toContain('one-time password')
  })

  it('a conflict on a first submit is "already a member" even for someone who joined minutes ago', () => {
    // Nothing on this page timed out, so nothing it knows makes the recent join its own doing.
    const outcome = describeMemberCreateFailure(alreadyMember(), 'ana@example.org', [justJoined], none, NOW + 60_000)
    expect(outcome.kind).toBe('already-member')
  })

  it('this attempt timing out, with the person joined since it began, is landed-late with Reset password', () => {
    const outcome = describeMemberCreateFailure(timeout(), ' Ana@Example.org ', [justJoined], none, NOW)
    expect(outcome.kind).toBe('landed-late')
    expect(outcome).toMatchObject({ member: justJoined })
    expect(outcome.message).toContain('is a member now')
    expect(outcome.message).toContain('finished after the page stopped waiting')
    expect(outcome.message).toContain('Reset password')
  })

  it('a conflict after this page timed out on the same email earlier is landed-late', () => {
    const remembered = new Map([['ana@example.org', NOW]])
    const outcome = describeMemberCreateFailure(alreadyMember(), 'ana@example.org', [justJoined], remembered, NOW + 90_000)
    expect(outcome.kind).toBe('landed-late')
  })

  it('a timeout on this page is not credited with a membership that predates it', () => {
    const remembered = new Map([['ana@example.org', NOW]])
    expect(describeMemberCreateFailure(alreadyMember(), 'ana@example.org', [longStanding], remembered, NOW + 90_000).kind)
      .toBe('already-member')
    expect(describeMemberCreateFailure(timeout(), 'ana@example.org', [longStanding], none, NOW).kind)
      .toBe('already-member')
  })

  it('allows the server clock to run a little behind the browser\'s, and no more', () => {
    const behind = (ms: number) => member(new Date(NOW - ms).toISOString())
    expect(describeMemberCreateFailure(timeout(), 'ana@example.org', [behind(CLOCK_ALLOWANCE_MS - 1000)], none, NOW).kind)
      .toBe('landed-late')
    expect(describeMemberCreateFailure(timeout(), 'ana@example.org', [behind(CLOCK_ALLOWANCE_MS + 1000)], none, NOW).kind)
      .toBe('already-member')
  })

  it('a timeout remembered for another email changes nothing', () => {
    const remembered = new Map([['bo@example.org', NOW - 10_000]])
    expect(describeMemberCreateFailure(alreadyMember(), 'ana@example.org', [justJoined], remembered, NOW).kind)
      .toBe('already-member')
  })

  it('a person who is not in the list keeps the failure in the server\'s words', () => {
    const outcome = describeMemberCreateFailure(exists(), 'bo@example.org', [longStanding], none, NOW)
    expect(outcome).toEqual({ kind: 'failed', message: expect.stringContaining('already has an account') })
  })

  it('a re-list that failed claims nothing about membership', () => {
    const outcome = describeMemberCreateFailure(timeout(), 'ana@example.org', null, none, NOW)
    expect(outcome.kind).toBe('failed')
    expect(outcome.message).toContain('still working on the server')
  })
})

describe('resetAfterLostPasswordCopy', () => {
  it('names, first, that the person\'s current password stops working', () => {
    const copy = resetAfterLostPasswordCopy(justJoined)
    expect(copy.title).toBe('Reset Ana’s password?')
    expect(copy.consequences[0]).toMatch(/^Their current password stops working immediately\./)
    expect(copy.confirmLabel).toBe('Reset password')
  })
})
