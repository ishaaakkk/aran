import type { Me } from '@/services/types'

/**
 * What a protected route renders, decided from the session's state alone.
 *
 * The rule that matters (UI audit J1-F1 = J8-F1): a failure to load `/api/me` is `problem`,
 * rendered in place at the URL the user asked for — never `offline`. Sending it to /offline,
 * whose health probes are green whenever the backend is merely slow or refusing one user,
 * redirected straight back and looped several times a second. `offline` is reserved for the
 * one failure that page can actually re-test: the identity provider could not be initialised.
 */
export type SessionGate = 'offline' | 'login' | 'loading' | 'problem' | 'no-organization' | 'ready'

export interface SessionState {
  keycloakInitFailed: boolean
  authenticated: boolean
  status: 'idle' | 'loading' | 'ready' | 'error'
  me: Me | null
}

export function sessionGate(s: SessionState): SessionGate {
  if (s.keycloakInitFailed) return 'offline'
  if (!s.authenticated) return 'login'
  if (s.status === 'error') return 'problem'
  if (s.status !== 'ready' || !s.me) return 'loading'
  // Signed in to Keycloak but a member of no tenant: every workspace call would 403 (J1-F8).
  if ((s.me.tenants ?? []).length === 0) return 'no-organization'
  return 'ready'
}

/** Seconds before each automatic retry of a transient session failure; then only the button. */
export const SESSION_RETRY_BACKOFF = [2, 4, 8, 16, 30] as const

/** Whether a failure is worth retrying by itself — a slow or unreachable server, not a refusal. */
export function isTransient(error: unknown): boolean {
  const kind = (error as { kind?: string } | null)?.kind
  return kind === 'timeout' || kind === 'network' || kind === 'server'
}

/** A router location as the path Keycloak and the offline page should return to. */
export function pathOf(location: { pathname: string; search?: string; hash?: string } | null | undefined): string | undefined {
  if (!location?.pathname) return undefined
  return `${location.pathname}${location.search ?? ''}${location.hash ?? ''}`
}

/** Where a sign-out's redirect to the origin lands, which is all `state.from` can say after one. */
const DEFAULT_LANDINGS = new Set(['/', '/dashboard'])

/**
 * Which page the sign-in on /login returns to (J1-F17, J8-F1).
 *
 * A page kept across "sign out and sign in again" waits in this tab (`pendingReturnTo`) because
 * the sign-out lands on the origin, so the route that then leads to /login only ever says the
 * default landing. But if that sign-in never completed and the user later opened a deep link in
 * the same tab, the route names that link — a newer intent than the kept page, which used to win
 * over it (second review of UA2). A route that names anything but the default landing wins.
 */
export function signInReturnPath(pending: string | undefined, routeFrom: string | undefined): string | undefined {
  if (routeFrom && !DEFAULT_LANDINGS.has(routeFrom)) return routeFrom
  return pending ?? routeFrom
}
