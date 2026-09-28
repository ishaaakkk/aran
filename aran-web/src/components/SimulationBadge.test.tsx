import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'

/**
 * J1-F5 / UI-OD6: demo mode answers queries with canned rows chosen by
 * keywords — a mistyped `SELECT IPapr.titel` returned four plausible rows — so it must say it is
 * a simulation wherever results appear. The badge reads the same gate api.ts uses for the mocked
 * backend (`isDemoUser`: the VITE_DEMO build opt-in AND the demo identity).
 */
vi.mock('../services/keycloak', () => ({
  tokenParsed: () => ({ preferred_username: 'demo' }),
  ensureFreshToken: async () => 'demo-token',
}))

const { SimulationBadge } = await import('./SimulationBadge')
const { ResultPanel } = await import('./workbench/WorkbenchPanels')

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('SimulationBadge (J1-F5, UI-OD6)', () => {
  it('labels demo mode a simulation', () => {
    vi.stubEnv('VITE_DEMO', 'true')
    const html = renderToStaticMarkup(<SimulationBadge />)
    expect(html).toContain('data-testid="demo-simulation-badge"')
    expect(html).toContain('Simulated results — demo data')
  })

  it('renders nothing outside a demo build', () => {
    vi.stubEnv('VITE_DEMO', 'false')
    expect(renderToStaticMarkup(<SimulationBadge />)).toBe('')
  })

  it('sits on the result and plan panes in demo mode', () => {
    vi.stubEnv('VITE_DEMO', 'true')
    const html = renderToStaticMarkup(<ResultPanel result={null} activeTab="explain" />)
    expect(html).toContain('demo-simulation-badge')
  })
})
