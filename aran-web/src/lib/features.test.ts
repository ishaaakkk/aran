import { describe, it, expect } from 'vitest'
import { cubesEnabledFrom } from './features'
import type { Me } from '../services/types'

function me(overrides: Partial<Me> = {}): Me {
  return {
    userId: 'u-1',
    subject: 'sub-1',
    email: 'who@example.com',
    displayName: 'Who Ami',
    createdAt: '2026-09-01T09:00:00Z',
    tenants: [],
    ...overrides,
  }
}

/**
 * The deployment capability the UI renders the cube surface off. Every case that is not an
 * explicit `true` has to be off, because with `aran.cubes.enabled` off the backend does not
 * merely refuse the cube routes — `CubeController` is not a bean, so they answer 404, and a
 * button that can only produce 404s is worse than no button.
 */
describe('cubesEnabledFrom', () => {
  it('is true only for an explicit published true', () => {
    expect(cubesEnabledFrom(me({ features: { cubes: true } }))).toBe(true)
  })

  it('is false when the deployment published the flag off', () => {
    expect(cubesEnabledFrom(me({ features: { cubes: false } }))).toBe(false)
  })

  it('is false when the payload carries no features at all — a server predating the flag', () => {
    // Built without the key rather than set-then-deleted, so this is the real legacy shape.
    expect(cubesEnabledFrom(me())).toBe(false)
  })

  it('is false before /api/me has answered', () => {
    // RequireAuth holds every view back until it has, but "unknown" must still be off, and
    // must not throw on the way there.
    expect(cubesEnabledFrom(null)).toBe(false)
  })
})
