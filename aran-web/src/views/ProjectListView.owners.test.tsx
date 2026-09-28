import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'

vi.mock('@/services/keycloak', () => ({ ensureFreshToken: async () => 'token', tokenParsed: () => undefined }))

/** The Projects list before its data has loaded (J1-F7, J1-F10). */
describe('ProjectListView', () => {
  it('shows loading, not "No projects found", and no status filters that never match', async () => {
    const { ProjectListView } = await import('./ProjectListView')
    const html = renderToStaticMarkup(
      <MemoryRouter initialEntries={['/projects']}>
        <ProjectListView />
      </MemoryRouter>,
    )

    expect(html).not.toContain('No projects found')
    expect(html).toContain('data-testid="projects-new-button"')
    // REVIEW and DRAFT were filter buttons for statuses nothing ever writes.
    expect(html).not.toMatch(/>\s*review\s*</)
    expect(html).not.toMatch(/>\s*draft\s*</)
  })

  it('labels owners through ownerLabel — never a truncated UUID (J1-F10, second review of UA2)', async () => {
    const { readFileSync } = await import('node:fs')
    const source = readFileSync(new URL('./ProjectListView.tsx', import.meta.url), 'utf8')
    expect(source).toContain('owner: ownerLabel(p.createdBy, myUserId)')
    expect(source).not.toContain('createdBy.slice(')
  })
})
