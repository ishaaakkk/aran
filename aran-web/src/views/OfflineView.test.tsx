import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { OfflineView } from './OfflineView'

/**
 * UI audit J1-F14: before any probe answered, the offline page showed three components in
 * OUTAGE and a Gateway OPERATIONAL that nothing probed, described by developer ports ("Keycloak
 * IAM on port 8080" — on the standalone stack 8080 is Aran itself). Server-rendered, so no probe
 * runs: this is exactly the first paint.
 */
describe('OfflineView first paint (J1-F14)', () => {
  const html = renderToStaticMarkup(<MemoryRouter><OfflineView /></MemoryRouter>)

  it('claims nothing before a probe answers', () => {
    expect(html).toContain('checking')
    expect(html).not.toMatch(/>\s*outage\s*</)
    expect(html).not.toMatch(/>\s*operational\s*</)
  })

  it('describes components by role, with hosts and ports behind "Operator details"', () => {
    expect(html).not.toContain('Keycloak IAM on port 8080')
    expect(html).not.toContain('aran-application on port 8081')
    expect(html).toContain('Sign-in service')
    expect(html).toContain('Operator details')
    expect(html).toContain('data-testid="diagnostics-list"')
  })
})
