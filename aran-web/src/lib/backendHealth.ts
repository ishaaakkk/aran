/**
 * The one "is the backend up, and its database?" probe the top bar and the offline screen share.
 *
 * Both used to read `/actuator/health` from the page's own origin, which the API port never
 * serves — actuator lives on the internal-only management port — so a healthy standalone stack
 * showed a red OFFLINE. `/api/health` is the backend's same-origin answer: anonymous (the offline
 * screen runs before sign-in), statuses only, in actuator's `status` / `components.db.status`
 * shape.
 */
export const BACKEND_HEALTH_PATH = '/api/health';

export type ProbeStatus = 'operational' | 'outage';

export interface BackendHealth {
  api: ProbeStatus;
  db: ProbeStatus;
}

interface HealthBody {
  status?: string;
  components?: { db?: { status?: string } };
}

const OUTAGE: BackendHealth = { api: 'outage', db: 'outage' };

/** Never throws: an unreachable backend, a non-2xx or an unparseable body is an outage. */
export async function probeBackendHealth(signal?: AbortSignal): Promise<BackendHealth> {
  try {
    const response = await fetch(BACKEND_HEALTH_PATH, signal ? { signal } : undefined);
    if (!response.ok) return OUTAGE;
    const body = (await response.json()) as HealthBody;
    return {
      api: body.status === 'UP' ? 'operational' : 'outage',
      // The database's own status, never inferred from the process's: a process can be ready
      // while its datasource is down, and the old fallback painted that green.
      db: body.components?.db?.status === 'UP' ? 'operational' : 'outage',
    };
  } catch (err) {
    console.warn('Backend health probe failed:', err);
    return OUTAGE;
  }
}
