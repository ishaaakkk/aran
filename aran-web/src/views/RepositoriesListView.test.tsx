import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'

vi.mock('@/services/keycloak', () => ({ ensureFreshToken: async () => 'token', tokenParsed: () => undefined }))

/**
 * F9 / UI audit J1-F16: "Register repository" was live before the project list it reads had
 * loaded, and pressed then it answered "Create a project first" to a user with projects — the
 * e2e suite retried the click to survive it. The first render (before any effect has run) is
 * exactly that window, and static markup is exactly the first render.
 */
describe('RepositoriesListView before its data has loaded', () => {
  it('renders the Register button disabled, saying why', async () => {
    const { RepositoriesListView } = await import('./RepositoriesListView')
    const html = renderToStaticMarkup(
      <MemoryRouter initialEntries={['/repositories']}>
        <RepositoriesListView />
      </MemoryRouter>,
    )

    const button = html.match(/<button[^>]*data-testid="repos-new-button"[^>]*>/)?.[0] ?? ''
    expect(button, 'the Register button is rendered').not.toBe('')
    expect(button).toContain('disabled=""')
    // The reason is visible text the button points at, not a `title` only a mouse hover finds.
    expect(button).toContain('aria-describedby="repos-register-reason"')
    expect(html).toMatch(/<span id="repos-register-reason" role="status"[^>]*>Loading your projects…<\/span>/)
    expect(html, 'no onboarding claim while loading').not.toContain('No repositories yet')
  })
})

/** Review of UA2 (MAJOR) — see DatasetsListView.loading.test.tsx for why this reads the source. */
describe('RepositoriesListView empty state', () => {
  it('is rendered through ListEmptyBody, whose claim counts the projects that failed', () => {
    const source = readFileSync(new URL('./RepositoriesListView.tsx', import.meta.url), 'utf8')
    const body = source.slice(source.indexOf('<ListEmptyBody'), source.indexOf('</ListEmptyBody>'))
    expect(body).toContain('claim={emptyListClaim({ loading, error, failures: projectFailures, loaded: repositories.length })}')
    expect(body).toContain('No repositories yet')
  })
})
