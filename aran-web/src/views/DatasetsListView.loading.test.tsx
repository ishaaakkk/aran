import { beforeAll, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'

vi.mock('@/services/keycloak', () => ({ ensureFreshToken: async () => 'token', tokenParsed: () => undefined }))

beforeAll(() => {
  // No jsdom in this project; the view reads the theme class off <html> during render.
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { documentElement: { classList: { contains: () => false } } },
  })
})

/**
 * F9 / UI audit J1-F16 = J2-F31: "Register dataset" was live before the projects and
 * repositories its wizard lists had loaded; pressed then, it answered "Create a project first",
 * and the e2e suite reloaded and retried to cope. The first render is that window.
 */
describe('DatasetsListView before its data has loaded', () => {
  it('renders Register disabled and makes no "No datasets found" claim', async () => {
    const { DatasetsListView } = await import('./DatasetsListView')
    const html = renderToStaticMarkup(
      <MemoryRouter initialEntries={['/datasets']}>
        <DatasetsListView />
      </MemoryRouter>,
    )

    const button = html.match(/<button[^>]*data-testid="datasets-register-button"[^>]*>/)?.[0] ?? ''
    expect(button, 'the Register button is rendered').not.toBe('')
    expect(button).toContain('disabled=""')
    expect(button).toContain('aria-describedby="datasets-register-reason"')
    expect(html).toMatch(/<span id="datasets-register-reason" role="status"[^>]*>Loading your projects and repositories…<\/span>/)
    expect(html).not.toContain('No datasets found')
  })

  it('a deep link says nothing about the dataset until the list has answered', async () => {
    const { DatasetsListView } = await import('./DatasetsListView')
    const html = renderToStaticMarkup(
      <MemoryRouter initialEntries={['/datasets/00000000-0000-0000-0000-000000000000']}>
        <DatasetsListView />
      </MemoryRouter>,
    )
    expect(html).not.toContain('Dataset not found')
  })
})

/**
 * Review of UA2 (MAJOR): after a partial load with no rows, the list still offered "No datasets
 * found — Register Dataset" under the "this list is incomplete" banner. ListEmptyBody decides
 * (ListEmptyBody.test.tsx, listLoads.test.ts); without a DOM the effect-driven state cannot be
 * rendered here, so this pins the wiring: the onboarding state is ListEmptyBody's child, and
 * the claim it is given counts the failed sources.
 */
describe('DatasetsListView empty state', () => {
  it('is rendered through ListEmptyBody, whose claim counts the sources that failed', () => {
    const source = readFileSync(new URL('./DatasetsListView.tsx', import.meta.url), 'utf8')
    const body = source.slice(source.indexOf('<ListEmptyBody'), source.indexOf('</ListEmptyBody>'))
    expect(body).toContain('claim={emptyListClaim({ loading, error, failures: loadFailures, loaded: datasets.length })}')
    expect(body).toContain('No datasets found')
    expect(body).toContain('Register Dataset')
  })
})

describe('the dataset wizard\'s project step', () => {
  const source = () => readFileSync(new URL('./DatasetsListView.tsx', import.meta.url), 'utf8')

  it('decides "no repository" through projectRepositoryClaim, so an unread project is not called empty', () => {
    expect(source()).toContain('projectRepositoryClaim(activeProjectId, repositories, unreadProjectIds)')
    expect(source()).toContain('testId="datasets-project-repositories-unread"')
  })

  it('"Register a repository in it first" makes that project the working one before leaving (J1-F9)', () => {
    const s = source()
    const link = s.slice(s.lastIndexOf('<Link', s.indexOf('Register a repository in it first')), s.indexOf('Register a repository in it first'))
    expect(link).toContain('onClick={() => setStoreActiveProject(activeProjectId)}')
  })
})

describe('the wrapper editor', () => {
  it('is told it is seeded only when the seed matches the selection on screen (J8-F13)', () => {
    const source = readFileSync(new URL('./DatasetsListView.tsx', import.meta.url), 'utf8')
    expect(source).toContain('const wrapperSelection = wrapperSeedSelection(selectedDataset?.id, selectedWrapperClass);')
    expect(source).toContain('wrapperSeeded={seededSelection !== null && seededSelection === wrapperSelection}')
  })

  // J4-F11, wrapper half (second and third reviews of UA2). The session decides — in flight,
  // refused twice, re-read on a timeout, scoped to its dataset (modelSession.test.ts drives it);
  // DatasetWrapperTab.test.tsx pins the markup. This pins that the view renders the session's
  // keyed state for the selection ON SCREEN, never a view-wide flag another dataset could set.
  it('is told a save is in flight only for the dataset and class on screen', () => {
    const source = readFileSync(new URL('./DatasetsListView.tsx', import.meta.url), 'utf8')
    expect(source).toContain('const handleSaveWrapper = () => void session.getState().saveWrapper();')
    expect(source).toContain('const handleEnrollDataset = () => void session.getState().enrol();')
    expect(source).toContain('savingWrapper={wrapperSelection !== null && wrapperSelection in savingWrappers}')
    expect(source).toContain('saveCheck={wrapperSelection === null ? null : (saveChecks[wrapperSelection]?.check ?? null)}')
    expect(source).toContain('enrolling={selectedDataset.id in enrollingDatasets}')
    expect(source).toContain('modelError={modelError}')
    expect(source).not.toContain('savingWrapperRef')
    expect(source).not.toContain('loadModelForDataset')
  })

  // Third review of UA2: a preview of acm that answered after dblp was opened rendered under
  // dblp's editor. It is keyed by the selection it was run on — and, since the fourth review, by
  // the SQL it ran, so a re-seeded editor never sits beside the rows of a discarded edit.
  it('shows a preview only for the selection and the SQL it was run on', () => {
    const source = readFileSync(new URL('./DatasetsListView.tsx', import.meta.url), 'utf8')
    expect(source).toContain('setWrapperPreview({ selection, sql, data: res });')
    expect(source).toContain('wrapperPreviewData={wrapperPreview !== null && wrapperPreview.selection === wrapperSelection && wrapperPreview.sql === wrapperText ? wrapperPreview.data : null}')
  })
})
