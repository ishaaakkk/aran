import { describe, expect, it } from 'vitest'
import { INITIAL_SYSTEMS, outageCount, systemsFromProbes } from './diagnostics'

/**
 * The service diagnostics the offline page and the top bar share (UI audit J1-F14, J8-F22). The
 * offline page used to start every row at OUTAGE — Gateway at OPERATIONAL, which nothing probed —
 * and described components by developer port ("Keycloak IAM on port 8080", which on the
 * standalone stack is Aran itself).
 */
describe('diagnostics', () => {
  it('starts every row unknown — nothing is claimed before a probe answers', () => {
    expect(INITIAL_SYSTEMS.every((s) => s.status === 'unknown')).toBe(true)
    expect(outageCount(INITIAL_SYSTEMS)).toBe(0)
  })

  it('derives the gateway from the probes that travel through it, never a constant', () => {
    const up = systemsFromProbes({ api: 'operational', iam: 'outage', db: 'outage' })
    expect(up.find((s) => s.key === 'gateway')?.status).toBe('operational')
    const down = systemsFromProbes({ api: 'outage', iam: 'outage', db: 'outage' })
    expect(down.find((s) => s.key === 'gateway')?.status).toBe('outage')
    expect(outageCount(down)).toBe(4)
  })

  it('describes each component by its role, keeping hosts and ports for operators', () => {
    for (const s of INITIAL_SYSTEMS) {
      expect(s.role, s.key).not.toMatch(/port \d+|:\d{4}/)
      expect(s.operatorDetail, s.key).toBeTruthy()
    }
    const api = INITIAL_SYSTEMS.find((s) => s.key === 'api')
    // The standalone compose serves the app on 8080; a single port would be wrong on one stack.
    expect(api?.operatorDetail).toContain('8081')
    expect(api?.operatorDetail).toContain('8080')
  })
})
