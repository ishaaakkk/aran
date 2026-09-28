import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { Toast } from './Toast'
import { toastLifetimeMs } from '../../lib/toast'

/**
 * UI audit J8-F8: a failed save was reported in a toast that vanished after four seconds — gone
 * before a user reading another panel looked, and never announced, since the workspace had no
 * live region. One toast now serves Datasets and Repositories: a success is a polite status that
 * still hides itself (DatasetsPage.saveWrapper relies on that), an error is an alert that stays
 * until dismissed.
 */
describe('Toast (J8-F8)', () => {
  it('announces a success politely, and it hides itself', () => {
    const html = renderToStaticMarkup(
      <Toast toast={{ message: 'Saved.', type: 'success' }} onDismiss={() => {}} testId="datasets-toast" />,
    )
    expect(html).toMatch(/<div role="status"[^>]*><div data-testid="datasets-toast"/)
    expect(toastLifetimeMs('success')).toBeGreaterThan(0)
  })

  it('announces an error as an alert that persists until dismissed', () => {
    const html = renderToStaticMarkup(
      <Toast toast={{ message: 'Failed to save wrapper.', type: 'error' }} onDismiss={() => {}} testId="datasets-toast" />,
    )
    expect(html).toMatch(/<div role="alert"[^>]*><div data-testid="datasets-toast" data-type="error"/)
    expect(html).toContain('aria-label="Dismiss notification"')
    expect(toastLifetimeMs('error')).toBeNull()
  })

  // An error stays up until dismissed, so where it sits decides what it covers. At `top-14
  // right-4` it lay over the toolbar's primary action (`repos-new-button`,
  // `datasets-register-button`, a detail page's `dataset-close`): the "Create a project first"
  // raised by a click that beat the project list covered the very button the retry needed.
  // Bottom-right is clear of every page toolbar and of the top bar.
  it('sits in the bottom-right corner, clear of the top bar and every page toolbar', () => {
    for (const type of ['success', 'error'] as const) {
      const html = renderToStaticMarkup(<Toast toast={{ message: 'x', type }} onDismiss={() => {}} />)
      const fixed = html.match(/class="([^"]*\bfixed\b[^"]*)"/)?.[1] ?? ''
      expect(fixed.split(/\s+/)).toEqual(expect.arrayContaining(['bottom-4', 'right-4']))
      expect(fixed).not.toMatch(/\btop-/)
    }
  })

  // A live region inserted together with its text is often not announced (J8-F8's "no live
  // region", still true for successes while the region came and went with the message). Both
  // regions are mounted before any message and only their content changes.
  it('keeps both live regions mounted, and empty, when there is no toast', () => {
    const html = renderToStaticMarkup(<Toast toast={null} onDismiss={() => {}} testId="datasets-toast" />)
    expect(html).toMatch(/<div role="status"[^>]*aria-live="polite"[^>]*><\/div>/)
    expect(html).toMatch(/<div role="alert"[^>]*aria-live="assertive"[^>]*><\/div>/)
    expect(html).not.toContain('data-testid="datasets-toast"')
  })
})
