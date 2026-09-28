import type { ComponentStatus } from '@/services/types'

/**
 * What each probed service is, by role, and what stops working while it is down.
 *
 * Audit J7-F3: an owner opens System because something is broken, and the page has to say which
 * part and what that means for their team — "lake DOWN" alone sends them to the docs. Described by
 * role rather than by container or port (J8-F22): the reader may not know what a sidecar is, and a
 * port that differs between the dev and standalone stacks is wrong on one of them.
 */
const ROLES: Record<string, { label: string; impact: string }> = {
  lake: {
    label: 'Lake (file and snapshot storage)',
    impact: 'Uploads, dataset refreshes, product materialization and queries over snapshots fail, '
      + 'and Storage cannot be measured.',
  },
  fuseki: {
    label: 'Graph store (source and integrated schemas)',
    impact: 'Bootstrapping, compile, promote and every query fail.',
  },
  freyja: {
    label: 'Profiling and join-discovery service',
    impact: 'Profiling and join discovery fail. Queries still run.',
  },
  keycloak: {
    label: 'Sign-in service',
    impact: 'Nobody can sign in or renew a session, and members cannot be added or reset.',
  },
  db: {
    label: 'Metadata database',
    impact: 'Aran cannot read or save projects, datasets, members or jobs.',
  },
  diskSpace: {
    label: 'Server disk',
    impact: 'Writes that need local disk — exports and query spill — may fail.',
  },
}

export function componentRole(name: string): { label: string; impact: string } {
  return ROLES[name] ?? {
    label: name,
    impact: 'No description for this component yet — the detail below is what it reported.',
  }
}

/** Components that need attention, in the order the server sent them (broken first). */
export function componentsDown(components: readonly ComponentStatus[]): ComponentStatus[] {
  return components.filter((c) => c.status !== 'UP')
}

/** The details worth showing on one line, in the order an operator reads them. */
export function describeDetails(details: Record<string, string>): string {
  const ordered = ['error', 'url', 'status', 'version', 'database', 'validationQuery']
  const parts = ordered.filter((k) => details[k]).map((k) => `${k}=${details[k]}`)
  return parts.length > 0 ? parts.join('  ') : '—'
}
