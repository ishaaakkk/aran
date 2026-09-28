import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { conditionCleared, diagnose, offlineHeadline } from '@/lib/offlineDiagnosis'

/**
 * The offline page's half of the reload loop (UI audit J1-F1 = J8-F1): it redirected to
 * /dashboard whenever its probes were green, which they are whenever the backend is merely slow
 * or refusing one user — so the dashboard failed again and sent the user back, several times a
 * second. Probing must never navigate; the way back is a button offered only once the condition
 * that sent the user here re-tests green.
 */

const green = {
  backend: async () => ({ api: 'operational' as const, db: 'operational' as const }),
  identity: async () => 'operational' as const,
}

let assigned: string[]
beforeEach(() => {
  assigned = []
  vi.stubGlobal('window', {
    location: {
      assign: (url: string) => { assigned.push(url) },
      set href(url: string) { assigned.push(url) },
      get href() { return 'http://localhost/offline' },
    },
  })
})
afterEach(() => vi.unstubAllGlobals())

describe('diagnose', () => {
  it('reports every probe green without navigating anywhere', async () => {
    const result = await diagnose(green)

    expect(result).toEqual({ api: 'operational', db: 'operational', identity: 'operational' })
    expect(assigned, 'a green probe alone must not redirect').toEqual([])
  })
})

describe('conditionCleared re-tests what sent the user here', () => {
  it('a failed identity-provider start is cleared by the sign-in service answering', () => {
    expect(conditionCleared('identity', { api: 'outage', db: 'outage', identity: 'operational' })).toBe(true)
    expect(conditionCleared('identity', { api: 'operational', db: 'operational', identity: 'outage' })).toBe(false)
  })

  it('a session that would not load is cleared only by the backend and its database answering', () => {
    expect(conditionCleared('session', { api: 'operational', db: 'outage', identity: 'operational' })).toBe(false)
    expect(conditionCleared('session', { api: 'operational', db: 'operational', identity: 'outage' })).toBe(true)
  })

  it('a direct visit needs everything green', () => {
    expect(conditionCleared(undefined, { api: 'operational', db: 'operational', identity: 'outage' })).toBe(false)
    expect(conditionCleared(undefined, { api: 'operational', db: 'operational', identity: 'operational' })).toBe(true)
  })
})

/**
 * Second review of UA2: the session panel's "Check which services are reachable" sends people
 * here because /api/me was slow, and the page headlined "Platform offline — Service Outage
 * Detected." over four green rows.
 */
describe('offlineHeadline states an outage only when a probe reports one', () => {
  const allGreen = { api: 'operational', db: 'operational', identity: 'operational' } as const

  it('a session problem is not an outage before or after an all-green check', () => {
    for (const d of [null, allGreen]) {
      const h = offlineHeadline('session', d)
      expect(h.outage).toBe(false)
      expect(h.kicker).not.toMatch(/offline/i)
      expect(h.title).not.toMatch(/Outage/)
    }
    expect(offlineHeadline('session', allGreen).body).toMatch(/loading your session/)
  })

  it('a probe that reports an outage is headlined as one', () => {
    const h = offlineHeadline('session', { ...allGreen, db: 'outage' })
    expect(h.outage).toBe(true)
    expect(h.title).toBe('Service Outage Detected.')
  })

  it('a direct visit waits for the probes before claiming anything', () => {
    expect(offlineHeadline(undefined, null).outage).toBe(false)
    expect(offlineHeadline(undefined, allGreen).outage).toBe(false)
  })

  it('a sign-in that failed to start keeps its own sentence, which is what sent the user here', () => {
    expect(offlineHeadline('identity', null).title).toBe("Can't reach the sign-in service.")
    expect(offlineHeadline('identity', allGreen).title).toBe("Can't reach the sign-in service.")
  })
})
