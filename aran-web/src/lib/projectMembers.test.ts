import { describe, expect, it } from 'vitest'
import { addCandidates, memberLines } from './projectMembers'
import type { ProjectMember, TenantMember } from '@/services/types'

const tenant: TenantMember[] = [
  { userId: 'u-sergi', email: 'sergi@upc.edu', displayName: 'Sergi', role: 'OWNER', joinedAt: '' },
  { userId: 'u-ana', email: 'ana@upc.edu', displayName: 'ana', role: 'MEMBER', joinedAt: '' },
  { userId: 'u-marta', email: 'marta@upc.edu', displayName: 'Marta', role: 'MEMBER', joinedAt: '' },
]
const pm = (userId: string): ProjectMember => ({ userId, addedAt: '' })

describe('memberLines', () => {
  it('names members from the organization list, sorted case-insensitively, marking you', () => {
    const lines = memberLines([pm('u-sergi'), pm('u-ana')], tenant, 'u-sergi')
    expect(lines.map((l) => l.name)).toEqual(['ana', 'Sergi'])
    expect(lines.find((l) => l.userId === 'u-sergi')?.isSelf).toBe(true)
  })

  it('keeps a member who left the organization, by a short id, rather than under-counting', () => {
    const lines = memberLines([pm('0123456789abcdef')], tenant, null)
    expect(lines).toHaveLength(1)
    expect(lines[0].name).toBe('01234567…')
    expect(lines[0].known).toBe(false)
  })
})

describe('memberLines when the organization list could not be read', () => {
  it('says nothing about anyone having left — it cannot know', () => {
    // Review of J1-F4: a failed read was swallowed into [], and every member was labelled "no
    // longer in the organization" — a false fact rendered from an error.
    const lines = memberLines([pm('u-sergi'), pm('u-ana')], null, 'u-sergi')
    expect(lines.every((l) => l.known === null)).toBe(true)
  })
})

describe('addCandidates', () => {
  it('offers only organization members who cannot see the project yet', () => {
    expect(addCandidates(tenant, [pm('u-sergi')]).map((m) => m.userId)).toEqual(['u-ana', 'u-marta'])
  })
})
