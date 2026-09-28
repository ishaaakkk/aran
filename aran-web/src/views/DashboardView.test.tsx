import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { TailLog } from '@/components/TailLog'

vi.mock('@/services/keycloak', () => ({ ensureFreshToken: async () => 'token', tokenParsed: () => undefined }))

/**
 * Review of UA2: the tiles said "—"/"couldn't load" for an unread summary, but the Recent
 * activity card beside them — the other half of the same payload — said "No log entries.", a
 * claim about the audit log made before (or without) reading it (FRONTEND-6).
 */
describe('DashboardView before its summary has loaded', () => {
  it('says the activity is loading, not that there is none', async () => {
    const { DashboardView } = await import('./DashboardView')
    const html = renderToStaticMarkup(
      <MemoryRouter initialEntries={['/dashboard']}>
        <DashboardView />
      </MemoryRouter>,
    )
    expect(html).toContain('loading activity…')
    expect(html).not.toContain('No log entries.')
  })
})

describe('TailLog', () => {
  it('says what its caller knows about an empty log', () => {
    const html = renderToStaticMarkup(<TailLog rows={[]} emptyMessage="Couldn't load recent activity." />)
    expect(html).toContain('Couldn&#x27;t load recent activity.')
    expect(html).not.toContain('No log entries.')
  })

  it('keeps its old sentence for a caller that has read an empty log', () => {
    expect(renderToStaticMarkup(<TailLog rows={[]} />)).toContain('No log entries.')
  })
})
