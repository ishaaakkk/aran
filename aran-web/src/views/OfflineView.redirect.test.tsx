import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { OfflineView } from './OfflineView'

/**
 * The offline page's half of the /dashboard↔/offline loop (UI audit J1-F1 = J8-F1): it used to
 * set `window.location.href = '/dashboard'` the moment its probes were green, whatever had sent
 * the user there. `diagnose()` is tested side-effect free in offlineDiagnosis.test.ts; these pin
 * the view that calls it. No jsdom in this project, so the effect cannot be run here — the
 * source guard is what fails if a navigation is added back to the probe path.
 */
describe('OfflineView', () => {
  it('offers no way back before the condition that sent the user here has been re-tested', () => {
    const html = renderToStaticMarkup(
      <MemoryRouter initialEntries={[{ pathname: '/offline', state: { reason: 'identity', from: '/oql?q=x' } }]}>
        <OfflineView />
      </MemoryRouter>,
    )
    expect(html).toContain("Can&#x27;t reach the sign-in service.")
    expect(html).not.toContain('data-testid="offline-continue"')
  })

  it('arriving from the session panel does not headline an outage nothing has reported', () => {
    // Second review of UA2: /api/me was slow, not down, and the page said "Platform offline —
    // Service Outage Detected." before (and after) any probe answered.
    const html = renderToStaticMarkup(
      <MemoryRouter initialEntries={[{ pathname: '/offline', state: { reason: 'session', from: '/datasets' } }]}>
        <OfflineView />
      </MemoryRouter>,
    )
    expect(html).toContain('Checking Aran&#x27;s services.')
    expect(html).not.toContain('Service Outage Detected.')
    expect(html).not.toContain('Platform offline')
  })

  it('navigates from exactly one place — the Continue button — never from the probe path', () => {
    const source = readFileSync(new URL('./OfflineView.tsx', import.meta.url), 'utf8')
    const navigations = source.match(/location\.(?:href\s*=|assign\(|replace\()|\bnavigate\(|<Navigate\b/g) ?? []
    expect(navigations, 'one navigation in the whole view').toHaveLength(1)
    expect(source).toContain('onClick={() => window.location.assign(returnTo)}')
  })
})
