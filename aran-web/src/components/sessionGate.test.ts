import { describe, expect, it } from 'vitest'
import { isTransient, pathOf, sessionGate, signInReturnPath, type SessionState } from './sessionGate'
import type { Me } from '@/services/types'

const me = (tenants: Me['tenants']): Me => ({
  userId: 'u', subject: 's', email: 'ana@example.org', displayName: 'Ana', createdAt: '', tenants,
})
const member = [{ tenantId: 't', slug: 'org', name: 'Org', role: 'MEMBER', joinedAt: '' }] as Me['tenants']
const state = (over: Partial<SessionState>): SessionState => ({
  keycloakInitFailed: false, authenticated: true, status: 'ready', me: me(member), ...over,
})

describe('sessionGate (UI audit J1-F1 = J8-F1, J1-F8)', () => {
  it('a failed /api/me is handled in place, never sent to /offline — the reload loop', () => {
    expect(sessionGate(state({ status: 'error', me: null }))).toBe('problem')
  })

  it('only a failed identity-provider start goes to /offline, the one failure that page re-tests', () => {
    expect(sessionGate(state({ keycloakInitFailed: true }))).toBe('offline')
  })

  it('signed out goes to login; loading waits', () => {
    expect(sessionGate(state({ authenticated: false }))).toBe('login')
    expect(sessionGate(state({ status: 'loading', me: null }))).toBe('loading')
    expect(sessionGate(state({ status: 'idle', me: null }))).toBe('loading')
  })

  it('a signed-in account with no organization gets its own page, not a workspace of 403s', () => {
    expect(sessionGate(state({ me: me([]) }))).toBe('no-organization')
    expect(sessionGate(state({}))).toBe('ready')
  })
})

describe('isTransient', () => {
  it('retries a slow, unreachable or failing server, never a refusal', () => {
    expect(isTransient({ kind: 'timeout' })).toBe(true)
    expect(isTransient({ kind: 'network' })).toBe(true)
    expect(isTransient({ kind: 'server' })).toBe(true)
    expect(isTransient({ kind: 'unauthenticated' })).toBe(false)
    expect(isTransient({ kind: 'forbidden' })).toBe(false)
    expect(isTransient(new Error('x'))).toBe(false)
  })
})

describe('pathOf', () => {
  it('keeps the query and the hash — a shared /oql?q= link is the whole point (J1-F17)', () => {
    expect(pathOf({ pathname: '/oql', search: '?q=SELECT%20A.x%20FROM%20A', hash: '#plan' }))
      .toBe('/oql?q=SELECT%20A.x%20FROM%20A#plan')
    expect(pathOf(undefined)).toBeUndefined()
  })
})

/**
 * Second review of UA2 (a note, not a finding): a page kept by a sign-out-and-in that never
 * completed used to win over a signed-out deep link opened later in the same tab.
 */
describe('signInReturnPath', () => {
  it('a kept page wins over the default landing a sign-out redirect leads here from', () => {
    expect(signInReturnPath('/oql?q=x', '/dashboard')).toBe('/oql?q=x')
    expect(signInReturnPath('/oql?q=x', '/')).toBe('/oql?q=x')
    expect(signInReturnPath('/oql?q=x', undefined)).toBe('/oql?q=x')
  })

  it('a deep link opened since wins over the kept page', () => {
    expect(signInReturnPath('/oql?q=x', '/datasets/abc')).toBe('/datasets/abc')
  })

  it('with nothing kept, the route that led here is the answer', () => {
    expect(signInReturnPath(undefined, '/dashboard')).toBe('/dashboard')
    expect(signInReturnPath(undefined, undefined)).toBeUndefined()
  })
})
