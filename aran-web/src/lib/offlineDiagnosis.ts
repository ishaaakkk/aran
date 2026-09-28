import { probeBackendHealth, type BackendHealth, type ProbeStatus } from './backendHealth';

/** Why the user is here — the condition this page re-tests (set by RequireAuth). */
export type OfflineReason = 'identity' | 'session' | undefined;

export interface Diagnosis {
  api: ProbeStatus;
  db: ProbeStatus;
  identity: ProbeStatus;
}

async function probeIdentity(signal: AbortSignal): Promise<ProbeStatus> {
  try {
    const response = await fetch('/auth/realms/aran/.well-known/openid-configuration', { signal });
    return response.ok ? 'operational' : 'outage';
  } catch (err) {
    console.warn('Diagnostics: Keycloak unreachable:', err);
    return 'outage';
  }
}

/**
 * Probe what this page can see. Deliberately side-effect free: it never navigates. The old page
 * redirected to /dashboard the moment these probes were green — but green probes say nothing
 * about the failure that sent the user here (a 401 on /api/me, a slow session load), so the
 * dashboard failed again, came back here, and the browser looped several times a second
 * (UI audit J1-F1 = J8-F1).
 */
export async function diagnose(probes: {
  backend: (signal: AbortSignal) => Promise<BackendHealth>;
  identity: (signal: AbortSignal) => Promise<ProbeStatus>;
} = { backend: probeBackendHealth, identity: probeIdentity }): Promise<Diagnosis> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 3000);
  try {
    const [backend, identity] = await Promise.all([
      probes.backend(controller.signal),
      probes.identity(controller.signal),
    ]);
    return { api: backend.api, db: backend.db, identity };
  } finally {
    clearTimeout(timeoutId);
  }
}

/** Whether any probe reports an outage — the only thing that may headline one. */
export function anyOutage(d: Diagnosis): boolean {
  return d.api === 'outage' || d.db === 'outage' || d.identity === 'outage';
}

export interface OfflineHeadline {
  kicker: string;
  title: string;
  body: string;
  /** Whether the headline states an outage (and takes the error colour). */
  outage: boolean;
}

/**
 * What the page says at the top. An outage is headlined only once a probe reports one: the
 * session panel's "Check which services are reachable" link (reason `session`) sends people here
 * because `/api/me` was slow or failing, not because anything was down, and "Service Outage
 * Detected" over four green rows stated an outage that had not happened (second review of UA2).
 * `identity` keeps its sentence throughout — sign-in failing to start is the condition that sent
 * the user here, whatever the probes say later.
 */
export function offlineHeadline(reason: OfflineReason, d: Diagnosis | null): OfflineHeadline {
  if (reason === 'identity') {
    return {
      kicker: 'Sign-in unavailable',
      title: "Can't reach the sign-in service.",
      body: 'Aran could not start your sign-in. Retry re-checks the sign-in service; once it answers, continue to where you were.',
      outage: true,
    };
  }
  if (d === null) {
    return {
      kicker: 'Service check',
      title: "Checking Aran's services.",
      body: 'Aran is testing which of its services answer.',
      outage: false,
    };
  }
  if (anyOutage(d)) {
    return {
      kicker: 'Platform offline',
      title: 'Service Outage Detected.',
      body: 'We detected a connection failure to Aran local systems. Review the diagnostics below to identify which service is offline.',
      outage: true,
    };
  }
  return {
    kicker: 'Service check',
    title: "Aran's services answer.",
    body: reason === 'session'
      ? 'Every service this page can test answers, so what failed was loading your session — it may have been slow. Continue to try again.'
      : 'Every service this page can test answers.',
    outage: false,
  };
}

/**
 * Whether the condition that sent the user here has cleared — the only thing that may offer the
 * way back. Offered as a button, never taken automatically: going back reloads the app, and if
 * the failure persists for a reason these probes cannot see, an automatic return is the loop.
 */
export function conditionCleared(reason: OfflineReason, d: Diagnosis): boolean {
  if (reason === 'identity') return d.identity === 'operational';
  if (reason === 'session') return d.api === 'operational' && d.db === 'operational';
  return d.api === 'operational' && d.db === 'operational' && d.identity === 'operational';
}
