import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { GettingStarted } from './GettingStarted'
import { orientation } from '@/lib/orientation'
import type { DashboardSummary } from '@/services/types'

const summary = (over: Partial<DashboardSummary>): DashboardSummary => ({
  projects: 0, repositories: 0, datasets: 0, readyDatasets: 0,
  activeSchemaVersion: null, draftSchemaVersion: null, recentAudit: [], ...over,
})
const render = (s: DashboardSummary) =>
  renderToStaticMarkup(<MemoryRouter><GettingStarted guide={orientation(s)} /></MemoryRouter>)

/** The rendered panel — the `ui1-soft-ui` spec clicks its one primary button by name. */
describe('GettingStarted (J1-F2, J1-F3)', () => {
  it('an empty tenant\'s one primary button is "Create a project" — not "Register a repository"', () => {
    const html = render(summary({}))
    const buttons = html.match(/<button[^>]*>[^<]*<\/button>/g) ?? []
    expect(buttons).toHaveLength(1)
    expect(buttons[0]).toContain('Create a project')
    expect(html).toContain('step 1 of 6')
    expect(html).toContain('data-testid="dashboard-getting-started"')
  })

  it('a member in no project on a promoted tenant is offered the query, with no "unlocks" line', () => {
    const html = render(summary({ activeSchemaVersion: 3 }))
    expect(html).toContain('Query the active schema (v3)')
    expect(html).not.toContain('unlocks')
  })

  it('is gone once a schema is promoted and the user has a project', () => {
    expect(render(summary({ projects: 1, activeSchemaVersion: 2 }))).toBe('')
  })
})
