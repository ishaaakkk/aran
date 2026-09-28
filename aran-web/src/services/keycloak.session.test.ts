import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The session's edges in the keycloak module (UI audit J1-F17 = J8-F5 = J7-F6): sign-in comes
 * back to the page the user was on, and a session that can no longer be refreshed is reported
 * rather than turned into an immediate redirect that loses the page.
 */

const kc = vi.hoisted(() => ({
  updateToken: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  isTokenExpired: vi.fn(),
  init: vi.fn(),
  token: 'token' as string | undefined,
  authenticated: true,
}))

vi.mock('keycloak-js', () => ({
  default: class {
    constructor() {
      return kc
    }
  },
}))

/** A tab's sessionStorage, or one that refuses every access (a private window, blocked site data). */
const storage = (broken = false) => {
  const items = new Map<string, string>()
  const guard = () => { if (broken) throw new DOMException('denied', 'SecurityError') }
  return {
    getItem: (k: string) => { guard(); return items.get(k) ?? null },
    setItem: (k: string, v: string) => { guard(); items.set(k, v) },
    removeItem: (k: string) => { guard(); items.delete(k) },
  }
}

beforeEach(() => {
  vi.stubGlobal('window', { location: { origin: 'https://aran.example.org' }, sessionStorage: storage() })
  kc.logout.mockReset()
  kc.updateToken.mockReset()
  kc.login.mockReset()
  kc.isTokenExpired.mockReset()
  kc.token = 'token'
})
afterEach(() => vi.unstubAllGlobals())

describe('returnUrl — sign-in comes back to the page, never somewhere else', () => {
  it('keeps a same-origin path with its query', async () => {
    const { returnUrl } = await import('./keycloak')
    expect(returnUrl('/oql?q=SELECT%20A.x%20FROM%20A')).toBe('https://aran.example.org/oql?q=SELECT%20A.x%20FROM%20A')
  })

  it('refuses anything that could leave the origin', async () => {
    const { returnUrl } = await import('./keycloak')
    expect(returnUrl('//evil.example.com/x')).toBe('https://aran.example.org')
    expect(returnUrl('https://evil.example.com')).toBe('https://aran.example.org')
    expect(returnUrl('/\\evil.example.com')).toBe('https://aran.example.org')
    expect(returnUrl(undefined)).toBe('https://aran.example.org')
  })

  it('login passes the page as the redirect target', async () => {
    const { login } = await import('./keycloak')
    login('/datasets/abc')
    expect(kc.login).toHaveBeenCalledWith({ redirectUri: 'https://aran.example.org/datasets/abc' })
  })
})

describe('logout(returnTo) — "sign out and sign in again" comes back to the page (J8-F1)', () => {
  it('signs out to the registered origin and keeps the page for the next sign-in', async () => {
    const { logout, pendingReturnTo, login } = await import('./keycloak')
    logout('/oql?q=SELECT%20A.x%20FROM%20A')

    // The realm registers only the origin as a post-logout URI; a path there is refused.
    expect(kc.logout).toHaveBeenCalledWith({ redirectUri: 'https://aran.example.org' })
    expect(pendingReturnTo()).toBe('/oql?q=SELECT%20A.x%20FROM%20A')

    login(pendingReturnTo())
    expect(kc.login).toHaveBeenCalledWith({ redirectUri: 'https://aran.example.org/oql?q=SELECT%20A.x%20FROM%20A' })
    expect(pendingReturnTo(), 'consumed by the sign-in it was kept for').toBeUndefined()
  })

  it('a plain sign-out keeps nothing, and a foreign target is never kept', async () => {
    const { logout, pendingReturnTo } = await import('./keycloak')
    logout()
    expect(pendingReturnTo()).toBeUndefined()
    logout('//evil.example.com/x')
    expect(pendingReturnTo()).toBeUndefined()
  })

  it('storage that refuses access loses the page, never the sign-out', async () => {
    vi.stubGlobal('window', { location: { origin: 'https://aran.example.org' }, sessionStorage: storage(true) })
    const { logout, pendingReturnTo } = await import('./keycloak')
    expect(() => logout('/oql')).not.toThrow()
    expect(kc.logout).toHaveBeenCalledTimes(1)
    expect(pendingReturnTo()).toBeUndefined()
  })
})

describe('ensureFreshToken when the refresh fails', () => {
  it('reports an ended session to listeners and throws, instead of navigating away', async () => {
    const { ensureFreshToken, onSessionEnded } = await import('./keycloak')
    kc.updateToken.mockRejectedValue(new Error('refresh token expired'))
    kc.isTokenExpired.mockReturnValue(true)
    const ended = vi.fn()
    const stop = onSessionEnded(ended)

    await expect(ensureFreshToken()).rejects.toMatchObject({ name: 'SessionEndedError' })
    expect(ended).toHaveBeenCalledTimes(1)
    expect(kc.login, 'no redirect behind the user\'s back').not.toHaveBeenCalled()
    stop()
  })

  it('a session with no parsed token at all is ended, not a raw error', async () => {
    const { ensureFreshToken } = await import('./keycloak')
    kc.updateToken.mockRejectedValue(new Error('no refresh token'))
    kc.isTokenExpired.mockImplementation(() => { throw 'Not authenticated' })

    await expect(ensureFreshToken()).rejects.toMatchObject({ name: 'SessionEndedError' })
  })

  it('keeps using a token that is still valid when a refresh fails for a moment', async () => {
    const { ensureFreshToken, onSessionEnded } = await import('./keycloak')
    kc.updateToken.mockRejectedValue(new Error('keycloak restarting'))
    kc.isTokenExpired.mockReturnValue(false)
    const ended = vi.fn()
    const stop = onSessionEnded(ended)

    await expect(ensureFreshToken()).resolves.toBe('token')
    expect(ended).not.toHaveBeenCalled()
    stop()
  })
})
