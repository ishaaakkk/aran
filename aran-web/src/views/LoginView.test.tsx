import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'

/**
 * The front door is the first claim an evaluator checks, and a false one there makes every true
 * one after it suspect (UI audit J1-F13). Rendered in node with `renderToStaticMarkup`, as the
 * rest of the suite does; the keycloak module is stubbed because constructing keycloak-js needs a
 * browser, and the page's theme read needs a `localStorage`.
 */

vi.mock('@/services/keycloak', () => ({
  pendingReturnTo: () => undefined,
  isAuthenticated: () => false,
  login: () => {},
}))

const { LoginView } = await import('./LoginView')

function render(): string {
  return renderToStaticMarkup(
    <MemoryRouter>
      <LoginView />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})

describe('LoginView says only what this build does (J1-F13)', () => {
  it('does not promise warehouses, which no engine ships', () => {
    expect(render()).not.toMatch(/warehouse/i)
  })

  it('names APIs only as Singer taps an operator enables', () => {
    const html = render()
    expect(html).not.toContain('files and APIs')
    expect(html).toContain('Singer taps')
  })

  it('scopes enforced constraints to graph-tier queries; discovered ones guard nothing', () => {
    const html = render()
    expect(html).not.toContain('discovered constraints guard every query')
    expect(html).toContain('constraints you enforce')
  })
})

describe('LoginView first install (J1-F20)', () => {
  it('tells a first-time operator where the owner account and its password are', () => {
    vi.stubEnv('DEV', false)
    const html = render()
    expect(html).toContain('data-testid="login-first-install"')
    expect(html).toContain('logs aran-app')
    expect(html).toContain('./aran admin reset-password &lt;username&gt;')
  })

  // A login page does not name accounts to anonymous visitors, and the name would not even be
  // reliable: `aran.bootstrap.admin-username` is configurable. AdminBootstrap prints the
  // username in the same server log as the one-time password, so the card points there.
  it('does not name the owner account to an anonymous visitor', () => {
    vi.stubEnv('DEV', false)
    expect(render()).not.toContain('alice')
  })

  // A dev backend creates no owner and prints no password (AdminBootstrap returns early), and a
  // demo build signs in as demo/demo: on both the card would send the reader to a log line that
  // does not exist. Each has its own hint instead.
  it('is not shown on a dev or a demo build', () => {
    vi.stubEnv('DEV', true)
    expect(render()).not.toContain('login-first-install')
    vi.stubEnv('DEV', false)
    vi.stubEnv('VITE_DEMO', 'true')
    expect(render()).not.toContain('login-first-install')
  })
})

describe('LoginView demo hint (J1-F5, UI-OD6)', () => {
  it('calls the demo a simulation, not a scenario "queryable end to end"', () => {
    vi.stubEnv('VITE_DEMO', 'true')
    const html = render()
    expect(html).not.toContain('queryable end to end')
    expect(html).toContain('simulated')
  })
})
