import React, { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { INITIAL_SYSTEMS, outageCount, systemsFromProbes, type SystemRow } from '../lib/diagnostics';
import { DiagnosticsList } from '../components/DiagnosticsList';
import { conditionCleared, diagnose, offlineHeadline, type Diagnosis, type OfflineReason } from '../lib/offlineDiagnosis';
import { sameOriginPath } from '../lib/samePath';

export const OfflineView: React.FC = () => {
  const location = useLocation();
  const state = (location.state ?? {}) as { reason?: OfflineReason; from?: string };
  const reason = state.reason;
  const returnTo = sameOriginPath(state.from) ?? '/dashboard';
  const [diagnosis, setDiagnosis] = useState<Diagnosis | null>(null);
  // J1-F14: every row starts "checking" — the old list opened on OUTAGE for three components and
  // OPERATIONAL for a gateway nothing probed. Rows and wording are shared with the top bar.
  const [systems, setSystems] = useState<SystemRow[]>(INITIAL_SYSTEMS);

  const [isChecking, setIsChecking] = useState(false);

  // Probes and renders — and never navigates, whatever the probes say. The way back is the
  // Continue button below, offered only once the condition that sent the user here has cleared
  // (J1-F1 = J8-F1: an automatic return on green probes was half of the /dashboard↔/offline loop,
  // and still would be when Keycloak's initialisation is what failed). OfflineView.test.tsx
  // holds every navigation in this file to that one click.
  const checkDiagnostics = async () => {
    if (isChecking) return;
    setIsChecking(true);

    const result = await diagnose();
    const { api: apiStatus, db: dbStatus, identity: keycloakStatus } = result;

    setSystems(systemsFromProbes({ api: apiStatus, iam: keycloakStatus, db: dbStatus }));
    setDiagnosis(result);
    setIsChecking(false);
  };

  const cleared = diagnosis !== null && conditionCleared(reason, diagnosis);
  const headline = offlineHeadline(reason, diagnosis);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    checkDiagnostics();
    // checkDiagnostics is a plain function re-created every render, not memoized;
    // this intentionally runs once on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const statusOf = (key: SystemRow['key']) => systems.find((s) => s.key === key)?.status;
  const nodeFill = (status: SystemRow['status'] | undefined) =>
    status === 'operational' ? '#10b981' : status === 'outage' ? '#f43f5e' : '#737373';

  const outages = outageCount(systems);
  const probed = systems.some((s) => s.status !== 'unknown');

  return (
    <div className="relative flex items-center justify-center min-h-screen bg-background text-foreground overflow-y-auto px-4 py-8 font-sans">
      {/* Decorative dot grid background */}
      <svg className="absolute inset-0 w-full h-full text-muted-foreground/5 opacity-40 pointer-events-none" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <pattern id="nf-dots" width="22" height="22" patternUnits="userSpaceOnUse">
            <circle cx="1" cy="1" r="1" fill="currentColor" />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#nf-dots)" />
      </svg>

      <div className="relative max-w-lg w-full text-center space-y-6 z-10">
        {/* SVG Illustration */}
        <svg className="w-60 h-36 mx-auto text-muted-foreground/30 select-none" viewBox="0 0 320 180" xmlns="http://www.w3.org/2000/svg">
          {/* Connection Lines */}
          <line x1="60" y1="45" x2="60" y2="135" stroke="currentColor" strokeWidth="1.5" opacity="0.15" />
          <line x1="260" y1="45" x2="260" y2="135" stroke="currentColor" strokeWidth="1.5" opacity="0.15" />
          <line x1="60" y1="90" x2="260" y2="90" stroke="currentColor" strokeWidth="1.5" opacity="0.15" />

          {/* Warning center element */}
          <g transform="translate(160, 90)">
            <circle r="36" fill="rgba(244, 63, 94, 0.08)" stroke="#f43f5e" strokeWidth="1.5" strokeDasharray="4 2" className="animate-pulse" />
            <path d="M-6 -18 L8 -4 L-2 2 L8 18 L-8 4 L2 -2 Z" fill="#f43f5e" />
          </g>

          {/* Gateway Node */}
          <circle cx="60" cy="45" r="16" fill={nodeFill(statusOf('gateway'))} stroke="currentColor" strokeWidth="1.5" />
          <text x="60" y="49" textAnchor="middle" fontSize="9" fontWeight="700" fill="#fff" fontFamily="monospace">GW</text>

          {/* IAM Node */}
          <circle cx="260" cy="45" r="16" fill={nodeFill(statusOf('iam'))} stroke="currentColor" strokeWidth="1.5" />
          <text x="260" y="49" textAnchor="middle" fontSize="9" fontWeight="700" fill="#fff" fontFamily="monospace">IAM</text>

          {/* API Node */}
          <circle cx="60" cy="135" r="16" fill={nodeFill(statusOf('api'))} stroke="currentColor" strokeWidth="1.5" />
          <text x="60" y="139" textAnchor="middle" fontSize="9" fontWeight="700" fill="#fff" fontFamily="monospace">API</text>

          {/* DB Node */}
          <circle cx="260" cy="135" r="16" fill={nodeFill(statusOf('db'))} stroke="currentColor" strokeWidth="1.5" />
          <text x="260" y="139" textAnchor="middle" fontSize="9" fontWeight="700" fill="#fff" fontFamily="monospace">DB</text>
        </svg>

        <div className="space-y-2">
          <div
            data-testid="offline-kicker"
            className={`font-mono text-[10px] uppercase tracking-widest font-bold ${headline.outage ? 'text-rose-500' : 'text-muted-foreground'}`}
          >
            {headline.kicker}
          </div>
          <h1 className="text-2xl md:text-3xl font-extrabold tracking-tight">{headline.title}</h1>
          <p className="text-xs md:text-sm text-muted-foreground max-w-md mx-auto leading-relaxed">{headline.body}</p>
        </div>

        <div className="max-w-sm mx-auto">
          <DiagnosticsList systems={systems} />
        </div>

        <div className="space-y-4 pt-2">
          {cleared && (
            <div className="space-y-2" data-testid="offline-continue">
              <p className="text-xs text-foreground">
                {reason === 'identity' ? 'The sign-in service answers again.' : 'These services answer again.'}
              </p>
              <button
                type="button"
                onClick={() => window.location.assign(returnTo)}
                className="inline-flex items-center gap-2 rounded bg-primary px-5 py-2.5 font-mono text-xs uppercase tracking-widest text-primary-foreground font-semibold hover:bg-primary/95 transition-colors select-none"
              >
                Continue to Aran
              </button>
            </div>
          )}
          <button
            type="button"
            onClick={checkDiagnostics}
            disabled={isChecking}
            className="inline-flex items-center gap-2 rounded bg-primary px-5 py-2.5 font-mono text-xs uppercase tracking-widest text-primary-foreground font-semibold hover:bg-primary/95 transition-colors disabled:opacity-50 select-none"
          >
            <RefreshCw
              size={12}
              className={isChecking ? 'animate-spin' : ''}
            />
            Retry Connection
          </button>

          <div className="font-mono text-[10px] text-muted-foreground">
            Diagnostics Status:{' '}
            <span className="font-bold text-foreground">
              {!probed
                ? 'checking…'
                : outages > 0
                  ? `${outages} service outage(s) detected`
                  : 'All services answered'}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
};

export default OfflineView;
