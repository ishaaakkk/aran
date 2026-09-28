import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'

/**
 * The way back from "Change password" (review of J7-F14): Keycloak reports how the action ended,
 * and the page says so instead of landing silently. A failure is an alert, because the person may
 * believe the password changed when it did not.
 */

vi.mock('keycloak-js', () => ({ default: class {} }))

const { PasswordChangeNotice } = await import('./PasswordChangeNotice')

const render = (outcome: 'success' | 'cancelled' | 'error') =>
  renderToStaticMarkup(<PasswordChangeNotice outcome={outcome} onDismiss={() => {}} />)

describe('PasswordChangeNotice', () => {
  it('a failure is an alert that says the password is unchanged', () => {
    const html = render('error')
    expect(html).toContain('role="alert"')
    expect(html).toContain('your password is unchanged')
  })

  it('a change and a cancel are statuses, each saying which it was', () => {
    expect(render('success')).toContain('role="status"')
    expect(render('success')).toContain('Password changed')
    expect(render('cancelled')).toContain('Password change cancelled')
  })

  it('can be dismissed from the keyboard', () => {
    expect(render('success')).toMatch(/<button[^>]*aria-label="Dismiss"/)
  })
})
