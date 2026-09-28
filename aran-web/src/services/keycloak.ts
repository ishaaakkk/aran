import Keycloak from 'keycloak-js'
import { sameOriginPath } from '@/lib/samePath'

/**
 * Keycloak / OIDC integration.
 *
 * Aran treats Keycloak as a standards-compliant OIDC provider on the frontend
 * exactly as the backend does — no custom auth, just the standard library.
 * The flow is authorization-code + PKCE, the current best practice for SPAs:
 * no client secret ships in the bundle, and the code interception attack is
 * mitigated by the PKCE challenge.
 *
 * Token lifecycle: keycloak-js holds the access token in memory (never in
 * localStorage — that would expose it to XSS) and refreshes it silently before
 * expiry via `updateToken`. The api service calls `ensureFreshToken` before
 * every request.
 */

const keycloak = new Keycloak({
  // Production default is the SAME ORIGIN under /auth: the only production build that ships
  // without an explicit VITE_KEYCLOAK_URL is the standalone image, where Aran reverse-proxies
  // Keycloak at its own address (KeycloakProxyController) and the browser must never see
  // another origin. Dev keeps the direct dev-compose Keycloak; e2e sets the var explicitly.
  url:
    import.meta.env.VITE_KEYCLOAK_URL ??
    (import.meta.env.PROD ? `${window.location.origin}/auth` : 'http://localhost:8080'),
  realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'aran',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'aran-web',
})

export let keycloakInitFailed = false

/** How Keycloak's `kc_action_status` reports an application-initiated action. */
export type ActionOutcome = 'success' | 'cancelled' | 'error'

let passwordOutcome: ActionOutcome | null = null

// Keycloak returns from `changePassword()` with `kc_action_status` on the redirect, and keycloak-js
// hands it here during init — before anything renders. Without it a change, a cancel and a failure
// all came back to the same silent page. Kept until the account menu has said it.
keycloak.onActionUpdate = (status, action) => {
  if (action === undefined || action === 'UPDATE_PASSWORD') passwordOutcome = status
}

/** How the last "Change password" round trip ended, or null when there was none to report. */
export function passwordChangeOutcome(): ActionOutcome | null {
  return passwordOutcome
}

/** The outcome has been shown; do not show it again. */
export function clearPasswordChangeOutcome(): void {
  passwordOutcome = null
}

/** What to tell the person about it — never silence, never a failure that reads as success. */
export function passwordChangeMessage(outcome: ActionOutcome): string {
  switch (outcome) {
    case 'success':
      return 'Password changed. Use the new one next time you sign in.'
    case 'cancelled':
      return 'Password change cancelled — your password is unchanged.'
    case 'error':
      return 'Password change failed — your password is unchanged. Try again from this menu.'
  }
}

let initialized = false

/**
 * Initialise Keycloak once, on app startup. `check-sso` means: if there is an
 * existing session, adopt it silently; otherwise return unauthenticated rather
 * than immediately redirecting — the router guard decides when to force login.
 */
export async function initKeycloak(): Promise<boolean> {
  if (initialized) {
    return keycloak.authenticated ?? false
  }
  try {
    const authenticated = await keycloak.init({
      onLoad: 'check-sso',
      pkceMethod: 'S256',
      silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
    })
    initialized = true
    return authenticated
  } catch (err) {
    console.error('Keycloak initialization failed:', err)
    keycloakInitFailed = true
    initialized = true
    return false
  }
}

/**
 * Where Keycloak sends the browser back after sign-in: the page the user was on (J1-F17 — a
 * shared `/oql?q=…` link, or the page a session ended on, used to come back as the dashboard).
 * Only a same-origin path is honoured; anything else is the origin, so the sign-in page can
 * never be used as an open redirect.
 */
export function returnUrl(returnTo?: string): string {
  const path = sameOriginPath(returnTo)
  return path ? window.location.origin + path : window.location.origin
}

/**
 * Change your own password (audit J7-F14): Keycloak's application-initiated `UPDATE_PASSWORD`
 * action, through the same-origin `/auth` proxy, returning to the page it was started from.
 *
 * Self-service rather than an owner reset — an owner reset shows the owner the new password,
 * which is the wrong person to see it when the reason is a suspected leak.
 */
export function changePassword(): void {
  void keycloak.login({ action: 'UPDATE_PASSWORD', redirectUri: window.location.href })
}

/**
 * The page a "sign out and sign in again" started from, kept across the sign-out.
 *
 * Keycloak accepts only the realm's registered post-logout URI — the bare origin — so the page
 * cannot ride on the logout redirect the way it rides on the login one; it waits in this tab's
 * sessionStorage instead, and the next sign-in consumes it. Without it a refused session's only
 * way out dropped the shared `/oql?q=…` link the user had opened (J8-F1). Storage can be
 * unavailable (a private window, blocked site data), so every access is guarded and losing the
 * page is the fallback, never an error.
 */
const RETURN_KEY = 'aran.auth.returnTo'

/** The page a sign-out-and-in should come back to, if one is waiting. Read-only. */
export function pendingReturnTo(): string | undefined {
  try {
    return sameOriginPath(window.sessionStorage.getItem(RETURN_KEY)) ?? undefined
  } catch {
    return undefined
  }
}

function forgetReturnTo(): void {
  try {
    window.sessionStorage.removeItem(RETURN_KEY)
  } catch {
    // Nothing was stored, or nothing can be.
  }
}

/** Redirect to the Keycloak login page, coming back to `returnTo` (a path) afterwards. */
export function login(returnTo?: string): void {
  // Whichever sign-in happens next is the one a waiting page was kept for.
  forgetReturnTo()
  void keycloak.login({ redirectUri: returnUrl(returnTo) })
}

/**
 * End the session at Keycloak and return to the app. With `returnTo` (a path), the sign-in that
 * follows comes back to it — for "sign out and sign in again", not for a plain sign-out.
 */
export function logout(returnTo?: string): void {
  const path = sameOriginPath(returnTo)
  if (path) {
    try {
      window.sessionStorage.setItem(RETURN_KEY, path)
    } catch {
      // The page is lost across the sign-out; signing out still works.
    }
  }
  void keycloak.logout({ redirectUri: window.location.origin })
}

/** Raised when the session can no longer be refreshed — the realm's idle or absolute limit passed. */
export class SessionEndedError extends Error {
  constructor() {
    super('Your session has ended — sign in again to continue.')
    this.name = 'SessionEndedError'
  }
}

const sessionEndedListeners = new Set<() => void>()

/** Be told when the session ends, so the page can ask before leaving (see `ensureFreshToken`). */
export function onSessionEnded(listener: () => void): () => void {
  sessionEndedListeners.add(listener)
  return () => {
    sessionEndedListeners.delete(listener)
  }
}

/**
 * Ensure the access token is valid for at least the next 30 seconds,
 * refreshing it if not. Called before every API request.
 *
 * A failed refresh used to call `login()` on the spot: mid-edit, the page navigated away and an
 * unsaved wrapper or alignment was gone (UI audit J7-F6, J8-F5 — the realm's two-hour maximum
 * makes that daily). Now listeners are told and the request fails, so the page can say so and
 * let the user choose when to leave. A token that is still valid is used as it is: a refresh that
 * failed for a moment (Keycloak restarting) has not ended anything yet.
 */
export async function ensureFreshToken(): Promise<string | undefined> {
  try {
    await keycloak.updateToken(30)
  } catch {
    try {
      if (keycloak.token && !keycloak.isTokenExpired()) return keycloak.token
    } catch {
      // isTokenExpired throws when there is no parsed token at all: that session is over too.
    }
    sessionEndedListeners.forEach((listener) => listener())
    throw new SessionEndedError()
  }
  return keycloak.token
}

/** The raw token claims, once authenticated. */
export function tokenParsed() {
  return keycloak.tokenParsed
}

export function isAuthenticated(): boolean {
  return keycloak.authenticated ?? false
}

export default keycloak
