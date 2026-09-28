import { describe, expect, it } from 'vitest'
import { orientation } from './orientation'
import type { DashboardSummary } from '@/services/types'

const summary = (over: Partial<DashboardSummary>): DashboardSummary => ({
  projects: 0, repositories: 0, datasets: 0, readyDatasets: 0,
  activeSchemaVersion: null, draftSchemaVersion: null, recentAudit: [], ...over,
})

describe('first-run orientation (UI audit J1-F2, J1-F3)', () => {
  it('an empty tenant starts at something it can do: create a project', () => {
    const o = orientation(summary({}))
    expect(o.show).toBe(true)
    expect(o.current).toBe(0)
    expect(o.primary).toEqual({ label: 'Create a project', to: '/projects', state: { create: true } })
    expect(o.steps.map((s) => s.key)).toEqual(['project', 'repository', 'dataset', 'integrate', 'promote', 'query'])
  })

  it('ticks each step off a real count and moves the one primary action along', () => {
    expect(orientation(summary({ projects: 1 })).primary?.label).toBe('Register a repository')
    // One empty repository used to make the whole panel vanish with no next step.
    expect(orientation(summary({ projects: 1, repositories: 1 })).primary?.label).toBe('Register a dataset')
    expect(orientation(summary({ projects: 1, repositories: 1, datasets: 2, readyDatasets: 2 })).primary?.label)
      .toBe('Open Alignments')
  })

  it('a compiled draft makes the next step "Promote vN", and its button says it only opens the page', () => {
    const o = orientation(summary({ projects: 1, repositories: 1, datasets: 2, readyDatasets: 2, draftSchemaVersion: 4 }))
    expect(o.steps[o.current].key).toBe('promote')
    expect(o.steps[o.current].title).toBe('Promote v4')
    // The button navigates; promoting is a tenant-wide act it must not appear to perform (J4-F1).
    expect(o.primary).toEqual({ label: 'Open Alignments to promote v4', to: '/alignments' })
  })

  it('once a schema is promoted the setup list is gone for someone with a project', () => {
    expect(orientation(summary({ projects: 2, repositories: 1, datasets: 3, activeSchemaVersion: 3 })).show).toBe(false)
  })

  it('a member in no project on a promoted tenant is offered the query, never told it is locked', () => {
    const o = orientation(summary({ activeSchemaVersion: 3 }))
    expect(o.show).toBe(true)
    expect(o.primary).toEqual({ label: 'Query the active schema (v3)', to: '/oql' })
    expect(o.note).toContain('ask an owner')
    expect(JSON.stringify(o)).not.toMatch(/unlocks/)
  })
})
