import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * Audit J7-F14: a member who suspected a password leak had no way to change it themselves. The
 * account menu's "Change password" asks Keycloak for its UPDATE_PASSWORD action and comes back to
 * the page it left — pinned here against a stand-in for keycloak-js, since no test here drives a
 * real identity provider.
 */

const login = vi.fn(async () => {})

vi.mock('keycloak-js', () => ({
  default: class {
    login = login
    onActionUpdate?: (status: string, action?: string) => void
  },
}))

afterEach(() => {
  vi.unstubAllGlobals()
  login.mockClear()
})

describe('changePassword', () => {
  it('asks Keycloak for UPDATE_PASSWORD and returns to the current page', async () => {
    vi.stubGlobal('window', { location: { href: 'https://aran.example.org/admin/users?role=OWNER' } })
    const { changePassword } = await import('./keycloak')

    changePassword()

    expect(login).toHaveBeenCalledWith({
      action: 'UPDATE_PASSWORD',
      redirectUri: 'https://aran.example.org/admin/users?role=OWNER',
    })
  })
})

describe('the way back from Change password', () => {
  // Review of J7-F14: kc_action_status was ignored, so a change, a cancel and a failure all came
  // back to the same silent page.
  it('remembers how the UPDATE_PASSWORD round trip ended, until it has been shown', async () => {
    const kc = await import('./keycloak')
    expect(kc.passwordChangeOutcome()).toBeNull()

    kc.default.onActionUpdate?.('cancelled', 'UPDATE_PASSWORD')
    expect(kc.passwordChangeOutcome()).toBe('cancelled')
    expect(kc.passwordChangeMessage('cancelled')).toMatch(/cancelled — your password is unchanged/)

    kc.clearPasswordChangeOutcome()
    expect(kc.passwordChangeOutcome()).toBeNull()
  })

  it('ignores the outcome of an action that is not a password change', async () => {
    const kc = await import('./keycloak')
    kc.default.onActionUpdate?.('success', 'CONFIGURE_TOTP')
    expect(kc.passwordChangeOutcome()).toBeNull()
  })

  it('says a failure left the password unchanged, rather than nothing', async () => {
    const { passwordChangeMessage } = await import('./keycloak')
    expect(passwordChangeMessage('error')).toMatch(/failed — your password is unchanged/)
    expect(passwordChangeMessage('success')).toMatch(/^Password changed/)
  })
})
