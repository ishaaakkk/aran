import { describe, expect, it } from 'vitest'
import type { TenantMember } from '@/services/types'
import {
  invitationText, isOnlyOwner, removalSummary, removeMemberCopy, resetPasswordCopy, roleChangeCopy,
} from './memberActions'

/**
 * The confirmations an owner reads before a role change, a reset or a removal (audit J7-F4,
 * J7-F16, UI-OD9) and the invitation they hand over (J7-F18). Each sentence is a promise about what
 * the server does, so each is pinned.
 */

const member = (overrides: Partial<TenantMember> = {}): TenantMember => ({
  userId: 'u-marta', email: 'marta@upc.edu', displayName: 'Marta', role: 'MEMBER',
  joinedAt: '2026-09-01T10:00:00Z', lastSeenAt: null, ...overrides,
})

describe('roleChangeCopy', () => {
  it('promotion names the authority it grants and that it is recorded', () => {
    const copy = roleChangeCopy(member(), 'OWNER', false)
    expect(copy.title).toBe('Make Marta an owner?')
    expect(copy.confirmLabel).toBe('Make owner')
    expect(copy.consequences.join(' ')).toMatch(/revoke anyone’s API tokens/)
    expect(copy.consequences.join(' ')).toMatch(/recorded in Activity/)
  })

  it('demoting someone else says they lose Administration and keep their work', () => {
    const copy = roleChangeCopy(member({ role: 'OWNER' }), 'MEMBER', false)
    expect(copy.title).toBe('Make Marta a member?')
    expect(copy.consequences.join(' ')).toMatch(/lose access to Administration immediately/)
    expect(copy.consequences.join(' ')).toMatch(/keep their projects/)
  })

  it('demoting yourself is its own warning — you leave the admin area and cannot undo it alone', () => {
    const copy = roleChangeCopy(member({ role: 'OWNER' }), 'MEMBER', true)
    expect(copy.title).toBe('Give up your owner role?')
    expect(copy.confirmLabel).toBe('Make me a member')
    expect(copy.consequences.join(' ')).toMatch(/Only another owner can make you an owner again/)
    expect(copy.tone).toBe('danger')
  })
})

describe('resetPasswordCopy', () => {
  it('says the current password stops working and when theirs is chosen — next sign-in, not first', () => {
    const copy = resetPasswordCopy(member())
    expect(copy.consequences.join(' ')).toMatch(/stops working immediately/)
    expect(copy.consequences.join(' ')).toMatch(/at their next sign-in/)
    expect(copy.consequences.join(' ')).not.toMatch(/first sign-in/)
  })
})

describe('removeMemberCopy', () => {
  it('names the tokens, the projects, and what re-adding does and does not restore', () => {
    const copy = removeMemberCopy(member(), 'UPC Data Lab')
    expect(copy.title).toBe('Remove Marta from UPC Data Lab?')
    const text = copy.consequences.join(' ')
    expect(text).toMatch(/API token they hold is revoked/)
    expect(text).toMatch(/removed from every project/)
    expect(text).toMatch(/restores their account with a new temporary password — not their tokens or project access/)
    expect(copy.confirmLabel).toBe('Remove Marta')
  })

  it('says a project only they belong to is shared with you — never that everything simply "stays"', () => {
    // Review of UI-OD9: "Their datasets, alignments and products stay." was false for the most
    // common case, a project its creator never shared, which removal made unreachable for good.
    const text = removeMemberCopy(member(), null).consequences.join(' ')
    expect(text).toMatch(/A project only they belong to is shared with you, so none becomes unreachable/)
    expect(text).not.toMatch(/products stay/)
  })
})

describe('removalSummary', () => {
  it('names each project that changed hands', () => {
    const s = removalSummary('Marta', {
      tokensRevoked: 2, projectAccessRemoved: 3,
      projectsHandedOver: [{ id: 'p1', name: 'Q3 forecast' }, { id: 'p2', name: 'Sales' }],
    })
    expect(s.headline).toBe('Marta was removed — 2 API tokens revoked.')
    expect(s.handedOver).toContain('Only Marta could see Q3 forecast and Sales')
    expect(s.handedOver).toContain('now shared with you')
  })

  it('says nothing changed hands when nothing did', () => {
    const s = removalSummary('Marta', { tokensRevoked: 0, projectAccessRemoved: 1, projectsHandedOver: [] })
    expect(s.headline).toBe('Marta was removed — no API token to revoke.')
    expect(s.handedOver).toBeNull()
  })
})

describe('isOnlyOwner', () => {
  it('locks the only owner and frees them once there is a second', () => {
    const owner = member({ userId: 'u-a', role: 'OWNER' })
    expect(isOnlyOwner(owner, [owner, member()])).toBe(true)
    expect(isOnlyOwner(owner, [owner, member({ userId: 'u-b', role: 'OWNER' })])).toBe(false)
    expect(isOnlyOwner(member(), [owner, member()])).toBe(false)
  })
})

describe('invitationText', () => {
  it('carries where, who, the temporary password and that they choose their own', () => {
    const text = invitationText({
      url: 'https://aran.upc.edu', email: 'marta@upc.edu', password: 'k7Fq2mZp', organization: 'UPC Data Lab',
    })
    expect(text).toContain('You have an Aran account in UPC Data Lab.')
    expect(text).toContain('Sign in at: https://aran.upc.edu')
    expect(text).toContain('Username: marta@upc.edu')
    expect(text).toContain('Temporary password: k7Fq2mZp')
    expect(text).toContain('You will choose your own password when you sign in.')
  })

  it('never names the identity provider underneath', () => {
    const text = invitationText({ url: 'u', email: 'e', password: 'p', organization: null })
    expect(text.toLowerCase()).not.toContain('keycloak')
  })

  it('says access was restored, with a new password, for a reinstatement (UI-OD9)', () => {
    const text = invitationText({ url: 'u', email: 'e', password: 'p', organization: 'UPC Data Lab', reinstated: true })
    expect(text.startsWith('Your Aran access to UPC Data Lab has been restored, with a new password.')).toBe(true)
    expect(text).toContain('Temporary password: p')
  })

  it('says "was reset" for a reset rather than welcoming them again', () => {
    const text = invitationText({ url: 'u', email: 'e', password: 'p', organization: null, reset: true })
    expect(text.startsWith('Your Aran password was reset.')).toBe(true)
  })
})
