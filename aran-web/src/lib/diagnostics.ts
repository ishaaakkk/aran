/**
 * The four components the offline page and the top-bar popover report on — one definition, so
 * the two screens cannot describe the same outage differently (UI audit J1-F14).
 *
 * Each row speaks to two readers: `name`/`role` to anyone (what the component does for them),
 * `operatorDetail` to whoever runs the stack (what it is and where it listens). Ports differ per
 * stack — the dev backend is on 8081, the standalone compose publishes Aran on 8080 and keeps
 * Keycloak behind /auth — so a role-level line is the only one true for every reader, and the
 * ports go behind "Operator details" (J8-F22).
 */

export type ProbeStatus = 'unknown' | 'operational' | 'outage';

export interface SystemRow {
  key: 'api' | 'iam' | 'db' | 'gateway';
  name: string;
  role: string;
  operatorDetail: string;
  status: ProbeStatus;
}

const DEFINITIONS: Array<Omit<SystemRow, 'status'>> = [
  {
    key: 'api',
    name: 'Aran server',
    role: 'Answers every request this page makes.',
    operatorDetail: 'aran-application, probed at /api/health — port 8081 in a development stack, 8080 in the standalone compose.',
  },
  {
    key: 'iam',
    name: 'Sign-in service',
    role: 'Checks who you are when you sign in.',
    operatorDetail: 'Keycloak (realm aran), reached through this page’s /auth path.',
  },
  {
    key: 'db',
    name: 'Metadata database',
    role: 'Stores projects, datasets and schema versions.',
    operatorDetail: 'PostgreSQL, as reported by the Aran server’s own health check.',
  },
  {
    key: 'gateway',
    name: 'Web gateway',
    role: 'Serves this page and forwards its requests.',
    operatorDetail: 'The reverse proxy or dev server in front of the app. Not probed directly: it is up when the server or the sign-in service answers through it.',
  },
];

export const INITIAL_SYSTEMS: SystemRow[] = DEFINITIONS.map((d) => ({ ...d, status: 'unknown' }));

/** Rows from the three real probes. The gateway is derived: both probes travel through it. */
export function systemsFromProbes(probes: {
  api: Exclude<ProbeStatus, 'unknown'>;
  iam: Exclude<ProbeStatus, 'unknown'>;
  db: Exclude<ProbeStatus, 'unknown'>;
}): SystemRow[] {
  const gateway: ProbeStatus =
    probes.api === 'operational' || probes.iam === 'operational' ? 'operational' : 'outage';
  const status: Record<SystemRow['key'], ProbeStatus> = { ...probes, gateway };
  return DEFINITIONS.map((d) => ({ ...d, status: status[d.key] }));
}

export function outageCount(rows: SystemRow[]): number {
  return rows.filter((r) => r.status === 'outage').length;
}

/** The status word shown beside a row. */
export function statusLabel(status: ProbeStatus): string {
  return status === 'unknown' ? 'checking' : status;
}
