import type { Project } from '@/services/types'

/**
 * The project the dialog opens on (J6-F5): a choice already made here, else the active project
 * the breadcrumb shows, else the only project there is — never a guess between several, and never
 * an active id whose project no longer exists. It used to open blank, so a first-time save meant
 * picking the project the page header already named.
 */
export function defaultProductProject(
  current: string,
  activeProjectId: string | null,
  projects: Pick<Project, 'id'>[],
): string {
  if (current) return current;
  if (activeProjectId && projects.some(p => p.id === activeProjectId)) return activeProjectId;
  return projects.length === 1 ? projects[0].id : '';
}
