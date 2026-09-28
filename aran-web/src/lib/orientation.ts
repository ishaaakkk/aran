import type { DashboardSummary } from '@/services/types'

/** Where a step's link goes; `state` rides along as router state (e.g. open the create dialog). */
export interface StepLink {
  label: string
  to: string
  state?: Record<string, unknown>
}

export interface OrientationStep {
  key: 'project' | 'repository' | 'dataset' | 'integrate' | 'promote' | 'query'
  title: string
  detail: string
  done: boolean
  link: StepLink
}

export interface Orientation {
  /** Render the panel at all. */
  show: boolean
  steps: OrientationStep[]
  /** The step the one primary action belongs to; -1 when there is no step list. */
  current: number
  /** The one primary action. */
  primary: StepLink | null
  /** A sentence for the case the steps do not cover (a member in no project). */
  note: string | null
}

/**
 * The dashboard's first-run orientation, from the server's own counts (UI audit J1-F2, J1-F3).
 *
 * The old panel was keyed on `repositories === 0 && datasets === 0`, said "step 1 of 3" whatever
 * was true, and offered "Register a repository" first — which on a fresh install ends in "Create
 * a project first". It then vanished after one empty repository with no next step, and told a
 * member on a tenant with v3 promoted that querying "unlocks after a schema is promoted".
 *
 * Now: the steps start at what an empty tenant can do (create a project), each is ticked from a
 * real count, the list stays until a schema is promoted, and a promoted schema is never
 * described as locked.
 */
export function orientation(s: DashboardSummary): Orientation {
  const promoted = s.activeSchemaVersion !== null && s.activeSchemaVersion !== undefined
  const draft = s.draftSchemaVersion ?? null

  if (promoted) {
    // The workspace is set up; a member who belongs to no project is the one left without a way
    // in — the tenant-wide schema is queryable, their own sources are not reachable yet.
    if (s.projects > 0) {
      return { show: false, steps: [], current: -1, primary: null, note: null }
    }
    return {
      show: true,
      steps: [],
      current: -1,
      primary: { label: `Query the active schema (v${s.activeSchemaVersion})`, to: '/oql' },
      note: 'You are not in a project yet. Create one to add your own sources, or ask an owner to add you to an existing project.',
    }
  }

  const steps: OrientationStep[] = [
    {
      key: 'project',
      title: 'Create a project',
      detail: 'A project holds the sources, alignments and products you work on together.',
      done: s.projects > 0,
      link: { label: 'Create a project', to: '/projects', state: { create: true } },
    },
    {
      key: 'repository',
      title: 'Connect a source',
      detail: 'Register a repository: a database, an Iceberg catalog, or a place to upload files.',
      done: s.repositories > 0,
      link: { label: 'Register a repository', to: '/repositories' },
    },
    {
      key: 'dataset',
      title: 'Add a dataset',
      detail: s.datasets > 0
        ? `${s.readyDatasets} of ${s.datasets} bootstrapped — its schema is read when it lands.`
        : 'Upload a file or pick a table; Aran reads its schema when it lands.',
      done: s.datasets > 0,
      link: { label: 'Register a dataset', to: '/datasets' },
    },
    {
      key: 'integrate',
      title: 'Align and compile',
      detail: 'Enrol the datasets, review the suggested alignments, and compile one schema.',
      done: draft !== null,
      link: { label: 'Open Alignments', to: '/alignments' },
    },
    {
      key: 'promote',
      title: draft !== null ? `Promote v${draft}` : 'Promote the schema',
      detail: 'Promoting makes the compiled schema the one every query and product reads.',
      done: false,
      // The link opens the page the promote happens on; it does not promote. A label naming the
      // act ("Promote v3") promised a tenant-wide change a click would not make (J4-F1).
      link: { label: draft !== null ? `Open Alignments to promote v${draft}` : 'Open Alignments', to: '/alignments' },
    },
    {
      key: 'query',
      title: 'Query and publish',
      detail: 'Query the schema in the workbench, save products, serve them over JDBC or to agents.',
      done: false,
      link: { label: 'Open the workbench', to: '/oql' },
    },
  ]
  const current = steps.findIndex((step) => !step.done)
  return { show: true, steps, current, primary: steps[current].link, note: null }
}
