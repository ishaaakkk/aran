import { create } from 'zustand'
import { api } from '@/services/api'
import { errorDetail } from '@/lib/errors'
import type { Project } from '@/services/types'

interface ProjectsState {
  projects: Project[]
  loading: boolean
  /**
   * Where the project list stands. `projects.length === 0` used to double as "not loaded yet",
   * so a Register button pressed during the first load answered "Create a project first" (F9,
   * J1-F16) and a failed load read as "No projects yet" (FRONTEND-6, J1-F7). Views gate on this.
   */
  status: 'idle' | 'loading' | 'ready' | 'error'
  /** The last load's failure, as thrown — an `ApiError` carries the reference `ErrorNotice` shows. */
  loadError: unknown
  /** The last failure's sentence (load or create), for views that only print text. */
  error: string | null
  /**
   * The project the user is working in, shared across pages. Selection used to be
   * page-local state everywhere, so context evaporated on every navigation — the same
   * defect on Products, Alignments and the breadcrumbs (redesign brief, cross-cutting
   * "Global project context"). Persisted per browser; views still render their own
   * selector, they just read and write this instead of a local useState.
   */
  activeProjectId: string | null
  setActiveProject: (id: string | null) => void
  /** Never throws: the outcome says whether the list can be trusted. */
  load: () => Promise<{ ok: boolean; error?: unknown }>
  /**
   * Create a project and make it the active one (J1-F9 — choosers default to it). Throws the
   * server's error so the caller can show ITS sentence (a 402's limit, a 409's name clash)
   * rather than a fixed "Failed to create project" (J1-F6).
   */
  create: (name: string, description: string, color?: string, privacy?: string) => Promise<Project>
}

const ACTIVE_PROJECT_KEY = 'aran.ui.activeProjectId'

function storedActiveProject(): string | null {
  try {
    return localStorage.getItem(ACTIVE_PROJECT_KEY)
  } catch {
    return null
  }
}

export const useProjectsStore = create<ProjectsState>((set, get) => ({
  projects: [],
  loading: false,
  status: 'idle',
  loadError: null,
  error: null,
  activeProjectId: storedActiveProject(),

  setActiveProject: (id) => {
    try {
      if (id) localStorage.setItem(ACTIVE_PROJECT_KEY, id)
      else localStorage.removeItem(ACTIVE_PROJECT_KEY)
    } catch {
      // Storage can be unavailable (private mode); the in-memory value still works.
    }
    set({ activeProjectId: id })
  },

  load: async () => {
    set({ loading: true, status: 'loading', error: null, loadError: null })
    try {
      const projects = await api.listProjects()
      set({ projects, loading: false, status: 'ready' })
      // A persisted selection pointing at a deleted (or other-tenant) project would
      // silently gate every project-scoped page on nothing — drop it instead.
      const active = get().activeProjectId
      if (active && !projects.some((p) => p.id === active)) {
        get().setActiveProject(null)
      }
      return { ok: true }
    } catch (e: unknown) {
      set({ error: errorDetail(e, 'Failed to load projects'), loadError: e, loading: false, status: 'error' })
      return { ok: false, error: e }
    }
  },

  create: async (name, description, color, privacy) => {
    set({ error: null })
    try {
      const project = await api.createProject(name, description, color, privacy)
      set({ projects: [...get().projects, project] })
      get().setActiveProject(project.id)
      return project
    } catch (e: unknown) {
      set({ error: errorDetail(e, 'Failed to create project') })
      throw e
    }
  },
}))

/**
 * The project a chooser should preselect: the active one when it is still in the list, else the
 * first. Every chooser used `projects[0]`, so a user who had just created `health-records` found
 * `papers` preselected and misfiled a source with one click (J1-F9).
 */
export function preferredProjectId(projects: Project[], activeProjectId: string | null): string {
  if (activeProjectId && projects.some((p) => p.id === activeProjectId)) return activeProjectId
  return projects[0]?.id ?? ''
}

/**
 * The project list as the aggregate loads consume it (`lib/listLoads`): loaded unless it already
 * is — so a tenant with no projects is not re-fetched as if it had never loaded, the old
 * `projects.length === 0` test — and read live after, since a view's closure holds the
 * pre-load snapshot.
 */
export function projectSource(load: ProjectsState['load']) {
  return {
    ensureProjects: () =>
      useProjectsStore.getState().status === 'ready' ? Promise.resolve({ ok: true }) : load(),
    projects: () => useProjectsStore.getState().projects,
  }
}

export default useProjectsStore
