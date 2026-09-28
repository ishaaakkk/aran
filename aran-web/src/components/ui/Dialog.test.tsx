import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { Dialog } from './Dialog'
import { ProjectDetailPanel } from '../ProjectDetailPanel'
import { ConnectionDetailRow, RepositoryDetailPanel } from '../RepositoryDetailPanel'
import type { DataRepository, Project } from '../../services/types'

/**
 * The one overlay primitive, in its two shapes (UI audit J1-F18, J2-F25). The side panels for a
 * project and a repository were a role="button" backdrop wrapping the panel with no
 * stopPropagation, so a click on "copy" inside closed the panel, Escape did nothing, and a
 * screen reader read the whole panel as one button. They now sit in Dialog's `sheet` variant,
 * which also starts below the top bar instead of under it. Focus and Escape are Dialog's
 * behaviour (effects and key handlers) and are not observable in a static render; what is pinned
 * here is the role, the name, and that the panels no longer position themselves.
 */
describe('Dialog', () => {
  it('renders a named modal dialog', () => {
    const html = renderToStaticMarkup(
      <Dialog open onClose={() => {}} label="Rename">
        <p>body</p>
      </Dialog>,
    )
    expect(html).toContain('role="dialog"')
    expect(html).toContain('aria-modal="true"')
    expect(html).toContain('aria-label="Rename"')
  })

  it('renders the sheet variant as a named dialog anchored right, below the top bar', () => {
    const html = renderToStaticMarkup(
      <Dialog open onClose={() => {}} label="Project details" variant="sheet" testId="project-sheet">
        <p>body</p>
      </Dialog>,
    )
    expect(html).toContain('role="dialog"')
    expect(html).toContain('aria-label="Project details"')
    expect(html).toContain('data-testid="project-sheet"')
    expect(html).toMatch(/data-testid="project-sheet"[^>]*class="[^"]*right-0[^"]*top-12/)
  })
})

describe('the project and repository side panels (J1-F18, J2-F25)', () => {
  it('leave positioning to the sheet', () => {
    const project = { id: 'p', name: 'papers', description: '', createdBy: 'u', createdAt: '2026-08-01T00:00:00Z' } as Project
    const repo = { id: 'r', projectId: 'p', name: 'uploads', type: 'FILE_SYSTEM', description: '' } as unknown as DataRepository
    const projectHtml = renderToStaticMarkup(<MemoryRouter><ProjectDetailPanel project={project} onClose={() => {}} /></MemoryRouter>)
    const repoHtml = renderToStaticMarkup(
      <RepositoryDetailPanel repository={repo} onClose={() => {}} onEdit={() => {}} onDelete={() => {}} />,
    )
    for (const html of [projectHtml, repoHtml]) {
      expect(html).not.toMatch(/^<div class="absolute right-0/)
    }
  })

  it('show a copy control a keyboard user can reach, not only on hover', () => {
    const repo = {
      id: 'r', projectId: 'p', name: 'db', type: 'RELATIONAL_DB', description: '',
      engine: 'POSTGRESQL', host: 'db.internal', port: 5432, database: 'app', username: 'u', schema: 'public',
    } as unknown as DataRepository
    const html = renderToStaticMarkup(
      <RepositoryDetailPanel repository={repo} onClose={() => {}} onEdit={() => {}} onDelete={() => {}} />,
    )
    expect(html).toContain('focus-visible:opacity-100')
  })

  // J5-F17's rule — when the copy fails, the value is still there to select by hand. Both sheets
  // render inside list views whose root is `select-none`, which a value must opt back out of.
  it('keep their values selectable inside the select-none list views', () => {
    const repo = {
      id: 'r', projectId: 'p', name: 'db', type: 'RELATIONAL_DB', description: '',
      engine: 'POSTGRESQL', host: 'db.internal', port: 5432, database: 'app', username: 'u', schema: 'public',
    } as unknown as DataRepository
    const repoHtml = renderToStaticMarkup(
      <RepositoryDetailPanel repository={repo} onClose={() => {}} onEdit={() => {}} onDelete={() => {}} />,
    )
    expect(repoHtml).toMatch(/<span class="[^"]*\bselect-text\b[^"]*">db\.internal:5432<\/span>/)
    const project = { id: 'p', name: 'papers', description: '', createdBy: 'c0ffee-creator', createdAt: '2026-08-01T00:00:00Z' } as Project
    const projectHtml = renderToStaticMarkup(<MemoryRouter><ProjectDetailPanel project={project} onClose={() => {}} /></MemoryRouter>)
    expect(projectHtml).toMatch(/<span class="[^"]*\bselect-all\b[^"]*">c0ffee-creator<\/span>/)
  })

  it('say when a copy did not happen, instead of doing nothing', () => {
    const failed = renderToStaticMarkup(
      <ConnectionDetailRow label="Host" value="db.internal:5432" copied={false} onCopy={() => {}} />,
    )
    expect(failed).toContain('role="status"')
    expect(failed).toContain('couldn’t copy — select it')
    const done = renderToStaticMarkup(
      <ConnectionDetailRow label="Host" value="db.internal:5432" copied={true} onCopy={() => {}} />,
    )
    expect(done).not.toContain('couldn’t copy')
  })
})
