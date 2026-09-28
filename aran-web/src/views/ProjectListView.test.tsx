import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { CreateProjectModal, ProjectListView } from './ProjectListView'
import { ProjectDetailPanel } from '../components/ProjectDetailPanel'
import type { Project } from '../services/types'

/**
 * UI-OD3 / audit J1-F4: PUBLIC was offered, stored and never read — an admin made a "public"
 * project for the team and nobody could see it. The option leaves the create form, the list stops
 * showing a privacy column, and the detail panel shows who can see the project instead.
 *
 * The testids `ProjectsPage` drives (`projects-name-input`, `projects-description-input`,
 * `projects-submit`) are pinned here too, so the extraction of the form cannot move them.
 */

function modal() {
  return renderToStaticMarkup(
    <CreateProjectModal
      name="papers" onName={() => {}} description="" onDescription={() => {}}
      color="#8B5CF6" colorOptions={['#8B5CF6', '#10B981']} onColor={() => {}}
      formError={null} creating={false} onSubmit={() => {}} onClose={() => {}}
    />,
  )
}

describe('create project form', () => {
  it('offers no visibility choice, and no PUBLIC anywhere', () => {
    const html = modal()
    expect(html).not.toContain('PUBLIC')
    expect(html).not.toContain('project-visibility')
    expect(html).toContain('Only members see a project')
  })

  it('keeps the e2e contract testids', () => {
    const html = modal()
    for (const id of ['projects-name-input', 'projects-description-input', 'projects-submit']) {
      expect(html).toContain(`data-testid="${id}"`)
    }
  })
})

describe('project list', () => {
  it('has no privacy column', () => {
    const html = renderToStaticMarkup(<MemoryRouter><ProjectListView /></MemoryRouter>)
    expect(html.toLowerCase()).not.toContain('>privacy<')
    expect(html).toContain('data-testid="projects-new-button"')
  })
})

describe('project detail panel', () => {
  const project: Project = {
    id: 'p-1', tenantId: 't-1', name: 'papers', description: '', createdBy: 'u-1',
    createdAt: '2026-09-01T00:00:00Z', color: '#8B5CF6', privacy: 'PUBLIC', status: 'ACTIVE', progress: 0,
  }

  it('does not show a stored PUBLIC — a value nothing honours — and shows Members instead', () => {
    const html = renderToStaticMarkup(
      <MemoryRouter><ProjectDetailPanel project={project} onClose={() => {}} /></MemoryRouter>,
    )
    expect(html).not.toContain('PUBLIC')
    expect(html).not.toContain('Privacy')
    expect(html).toContain('data-testid="project-members"')
  })
})
