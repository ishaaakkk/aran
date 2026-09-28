import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { ApiError } from '@/services/api'
import type { Me } from '@/services/types'

/**
 * What a protected route renders when the session cannot be loaded (UI audit J1-F1 = J8-F1).
 *
 * It used to render `<Navigate to="/offline">`, whose green health probes sent the browser
 * straight back — 49 navigations in 8 s on a 401, measured. The failure is now rendered in
 * place, at the URL the user asked for. No jsdom here: static markup is enough to tell a
 * rendered panel from a redirect (which renders nothing) or a spinner.
 */

vi.mock('@/services/keycloak', () => ({
  isAuthenticated: () => true,
  keycloakInitFailed: false,
  login: vi.fn(),
  logout: vi.fn(),
  onSessionEnded: () => () => {},
  ensureFreshToken: async () => 'token',
  tokenParsed: () => undefined,
}))

// Static rendering reads a zustand store through its INITIAL state (getServerSnapshot), so the
// store is replaced by a plain selector over the scenario each test sets.
const auth = vi.hoisted(() => ({ state: {} as Record<string, unknown> }))
vi.mock('@/stores/authStore', () => {
  const useAuthStore = (selector: (s: Record<string, unknown>) => unknown) => selector(auth.state)
  return { useAuthStore, default: useAuthStore }
})

const scenario = (over: Record<string, unknown>) => {
  auth.state = {
    sessionStatus: 'idle', sessionError: null, me: null, sessionExpired: false,
    loadMe: vi.fn(), setSessionExpired: vi.fn(), ...over,
  }
}

const render = async (path = '/oql?q=SELECT%20A.x%20FROM%20A') => {
  const { RequireAuth } = await import('./RequireAuth')
  return renderToStaticMarkup(
    <MemoryRouter initialEntries={[path]}>
      <RequireAuth><div data-testid="workspace">workspace</div></RequireAuth>
    </MemoryRouter>,
  )
}

const member = (tenants: Me['tenants']): Me => ({
  userId: 'u', subject: 's', email: 'ana@example.org', displayName: 'Ana', createdAt: '', tenants,
})

describe('RequireAuth renders session failures in place', () => {
  it('a 401 on /api/me says the sign-in was refused and offers sign-out, instead of redirecting', async () => {
    scenario({
      sessionStatus: 'error',
      sessionError: new ApiError(401, null, 'Aran did not accept your sign-in — your session may have ended. Sign in again.'),
    })

    const html = await render()
    expect(html).toContain('data-testid="session-problem"')
    expect(html).toContain('Aran did not accept your sign-in')
    expect(html).toContain('Sign out and sign in again')
    expect(html).not.toContain('Retrying in')
    // Second review of UA2: the copy says retrying cannot help, so no Retry sits beside it.
    expect(html).not.toContain('Retry now')
    expect(html).not.toContain('data-testid="workspace"')
  })

  it('a slow /api/me is retried on a back-off, with its reference shown', async () => {
    scenario({
      sessionStatus: 'error',
      sessionError: new ApiError(0, null, 'Aran did not answer within 15 s.', { kind: 'timeout', correlationId: 'corr-me' }),
    })

    const html = await render()
    expect(html).toContain('Can&#x27;t load your session')
    expect(html).toContain('Retrying in 2 s')
    expect(html).toContain('corr-me')
    expect(html).toContain('Retry now')
  })

  it('the countdown ticks for the eye only — the alert is not re-announced every second', async () => {
    scenario({
      sessionStatus: 'error',
      sessionError: new ApiError(0, null, 'Aran did not answer within 15 s.', { kind: 'timeout' }),
    })

    const html = await render()
    const countdown = html.match(/<p[^>]*data-testid="session-auto-retry"[^>]*>.*?<\/p>/)?.[0] ?? ''
    expect(countdown, 'the countdown is rendered').not.toBe('')
    expect(countdown).toMatch(/<span aria-hidden="true">Retrying in 2 s…<\/span>/)
    expect(countdown).toContain('class="sr-only"')
    // role="alert" is atomic: any live region or changing text inside it repeats the whole alert.
    expect(html).not.toContain('aria-live')
  })

  it('"Sign out and sign in again" keeps the page it was pressed on (J8-F1)', async () => {
    const { readFileSync } = await import('node:fs')
    const source = readFileSync(new URL('./RequireAuth.tsx', import.meta.url), 'utf8')
    const button = source.slice(source.indexOf('Sign out and sign in again') - 400, source.indexOf('Sign out and sign in again'))
    expect(button).toContain('onClick={() => logout(from)}')
  })

  it('an account in no organization gets a page that says so, not a workspace of 403s (J1-F8)', async () => {
    scenario({ sessionStatus: 'ready', me: member([]) })

    const html = await render('/dashboard')
    expect(html).toContain('data-testid="no-organization"')
    expect(html).toContain('No organization yet')
    expect(html).toContain('ana@example.org')
    expect(html).not.toContain('data-testid="workspace"')
  })

  it('a session that ended keeps the page behind a dialog instead of redirecting away (J7-F6)', async () => {
    scenario({
      sessionStatus: 'ready', sessionExpired: true,
      me: member([{ tenantId: 't', slug: 'org', name: 'Org', role: 'MEMBER', joinedAt: '' }] as Me['tenants']),
    })

    const html = await render('/datasets/abc')
    expect(html).toContain('data-testid="workspace"')
    expect(html).toContain('data-testid="session-ended-dialog"')
    expect(html).toContain('Sign in again')
    expect(html).toContain('Stay on this page')
  })

  it('"Stay on this page" leaves a banner that keeps the way on, not a modal that reopens', async () => {
    const { SessionEndedBanner } = await import('./RequireAuth')
    const html = renderToStaticMarkup(<SessionEndedBanner returnTo="/oql" />)
    expect(html).toContain('role="alert"')
    expect(html).toContain('Sign in again')
  })

  it('a loaded session renders the workspace', async () => {
    scenario({
      sessionStatus: 'ready',
      me: member([{ tenantId: 't', slug: 'org', name: 'Org', role: 'MEMBER', joinedAt: '' }] as Me['tenants']),
    })

    expect(await render('/dashboard')).toContain('data-testid="workspace"')
  })
})
