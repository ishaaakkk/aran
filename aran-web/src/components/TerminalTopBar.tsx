import React, { useState, useEffect, useMemo } from 'react';
import { useLocation } from 'react-router-dom';
import { RefreshCw, Search, ListChecks } from 'lucide-react';
import { StatusDot } from './StatusDot';
import { CommandPalette } from './CommandPalette';
import { JobsDrawer } from './JobsDrawer';
import { useProjectsStore } from '../stores/projectsStore';
import { useJobsStore } from '../stores/jobsStore';
import { useJobsPolling } from '../hooks/useJobEvents';
import { isLive, unseenFailures } from '../lib/jobs';
import { probeBackendHealth } from '../lib/backendHealth';
import { INITIAL_SYSTEMS, systemsFromProbes, type SystemRow } from '../lib/diagnostics';
import { DiagnosticsList } from './DiagnosticsList';
import { shortcutLabel } from '../lib/commandPalette';
import { SimulationBadge } from './SimulationBadge';

/** When the jobs drawer was last opened, so the button flags only failures that are news. */
const JOBS_SEEN_KEY = 'aran.ui.jobsSeenAt';

function storedJobsSeenAt(): number {
  try {
    return Number(localStorage.getItem(JOBS_SEEN_KEY)) || 0;
  } catch {
    return 0;
  }
}

/**
 * The top bar's way into the jobs drawer (UI audit J8-F7), on every page: how many jobs are
 * running, and whether any failed since the drawer was last opened.
 */
const JobsButton: React.FC = () => {
  const [open, setOpen] = useState(false);
  const [seenAt, setSeenAt] = useState<number>(storedJobsSeenAt);
  useJobsPolling(open);
  const jobs = useJobsStore((s) => s.jobs);
  const running = useMemo(() => Object.values(jobs).filter(isLive).length, [jobs]);
  const newFailures = useMemo(() => unseenFailures(Object.values(jobs), seenAt), [jobs, seenAt]);

  const openDrawer = () => {
    const at = Date.now();
    try {
      localStorage.setItem(JOBS_SEEN_KEY, String(at));
    } catch {
      // Storage can be unavailable (private mode); the in-memory value still works.
    }
    setSeenAt(at);
    setOpen(true);
  };

  return (
    <>
      <JobsButtonView running={running} newFailures={newFailures} onOpen={openDrawer} />
      <JobsDrawer open={open} onClose={() => setOpen(false)} />
    </>
  );
};

interface JobsButtonViewProps {
  running: number;
  newFailures: number;
  onOpen: () => void;
}

/** The jobs button for one count of running and unseen failed jobs — no store, no clock. */
export const JobsButtonView: React.FC<JobsButtonViewProps> = ({ running, newFailures, onOpen }) => {
  const summary = [
    running > 0 ? `${running} running` : null,
    newFailures > 0 ? `${newFailures} failed since you last looked` : null,
  ].filter(Boolean).join(', ');

  return (
    <button
      type="button"
      data-testid="topbar-jobs-button"
      onClick={onOpen}
      aria-label={summary ? `Jobs — ${summary}` : 'Jobs'}
      title={summary ? `Jobs — ${summary}` : 'Jobs: running, failed and recent'}
      className="relative inline-flex items-center gap-1.5 text-[12px] px-2.5 py-1.5 rounded border border-border text-muted-foreground hover:text-foreground hover:border-primary/40 transition-colors"
    >
      <ListChecks className="w-3.5 h-3.5" />
      <span>Jobs</span>
      {running > 0 && (
        // Solid primary, not a tint: the count must read at AA contrast in both skins.
        <span data-testid="topbar-jobs-running"
          className="min-w-[18px] h-[18px] px-1 rounded-full bg-primary text-primary-foreground text-[10px] font-mono font-semibold tabular-nums flex items-center justify-center">
          {running}
        </span>
      )}
      {newFailures > 0 && (
        <span data-testid="topbar-jobs-failed" aria-hidden="true"
          className="absolute -top-1 -right-1 w-2.5 h-2.5 rounded-full bg-error ring-2 ring-card" />
      )}
    </button>
  );
};


interface TerminalTopBarProps {
  crumbs?: string[];
}

export const TerminalTopBar: React.FC<TerminalTopBarProps> = ({ crumbs: customCrumbs }) => {
  const location = useLocation();
  const [showStatusMenu, setShowStatusMenu] = useState(false);
  const [isChecking, setIsChecking] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const shortcut = useMemo(() => shortcutLabel(globalThis.navigator?.platform), []);
  // Rows start "unknown" (checking): the popover once defaulted every service to operational.
  const [systems, setSystems] = useState<SystemRow[]>(INITIAL_SYSTEMS);
  const [lastChecked, setLastChecked] = useState<Date | null>(null);

  // Real breadcrumbs: the route, plus the REAL active project from the shared store
  // (never the hardcoded fictional demo project the old bar showed on every page —
  // redesign brief, chrome HIGH #1 + cross-cutting "Global project context").
  const activeProjectId = useProjectsStore((s) => s.activeProjectId);
  const projects = useProjectsStore((s) => s.projects);
  const loadProjects = useProjectsStore((s) => s.load);
  // The crumb can only name the active project if the store holds the project list —
  // and not every page loads it (AlignmentsView fetches projects with its own state).
  // Found by ui1-soft-ui.spec: a direct /alignments load showed no project crumb.
  useEffect(() => {
    if (activeProjectId && projects.length === 0) void loadProjects();
    // Deliberately keyed on activeProjectId only: projects.length would re-fire on
    // a legitimately empty tenant, and loadProjects is a stable zustand action.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeProjectId]);
  const crumbs = useMemo(() => {
    if (customCrumbs && customCrumbs.length > 0) return customCrumbs;
    const pathParts = location.pathname.split('/').filter(Boolean);
    const projectName = projects.find((p) => p.id === activeProjectId)?.name;
    return [
      ...(projectName ? [projectName] : []),
      ...(pathParts.length > 0 ? pathParts.map((p) => decodeURIComponent(p).toLowerCase()) : ['home']),
    ];
  }, [customCrumbs, location.pathname, projects, activeProjectId]);

  const checkHealth = async () => {
    if (isChecking) return;
    setIsChecking(true);

    const { api: apiStatus, db: dbStatus } = await probeBackendHealth();

    let keycloakStatus: 'operational' | 'outage' = 'outage';
    try {
      const response = await fetch('/auth/realms/aran/.well-known/openid-configuration');
      if (response.ok) {
        keycloakStatus = 'operational';
      }
    } catch (err) {
      console.warn('Failed to fetch identity provider configuration:', err);
    }

    // The gateway is derived inside systemsFromProbes: both probes travel through the proxy.
    setSystems(systemsFromProbes({ api: apiStatus, iam: keycloakStatus, db: dbStatus }));
    setLastChecked(new Date());

    setIsChecking(false);
  };

  useEffect(() => {
    // checkHealth is intentionally not memoized/depended-on: this runs once on mount
    // and owns its own 30s interval — re-running it on every render (or every time an
    // unmemoized checkHealth identity "changes") would tear the interval down constantly.
    // Its own re-entrancy guard (`if (isChecking) return`) is what setIsChecking(true)
    // synchronously flips, which is the setState this effect body reaches into.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void checkHealth();
    const intervalId = setInterval(() => {
      void checkHealth();
    }, 30000);
    return () => clearInterval(intervalId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ⌘K / Ctrl+K opens the palette from anywhere in the shell.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((o) => !o);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const overallStatus = useMemo(() => {
    const statuses = systems.map((s) => s.status);
    if (statuses.every((s) => s === 'unknown')) return 'checking';
    if (statuses.includes('outage')) {
      const coreDown = systems.some(
        (s) => (s.key === 'api' || s.key === 'iam') && s.status === 'outage'
      );
      return coreDown ? 'major_outage' : 'partial_outage';
    }
    return 'operational';
  }, [systems]);

  const overallStatusLabel = {
    checking: 'checking…',
    major_outage: 'offline',
    partial_outage: 'degraded',
    operational: 'online',
  }[overallStatus];

  const overallStatusDotVariant = {
    checking: 'idle' as const,
    major_outage: 'err' as const,
    partial_outage: 'warn' as const,
    operational: 'ok' as const,
  }[overallStatus];

  const lastCheckedLabel = useMemo(() => {
    if (!lastChecked) return null;
    return `${String(lastChecked.getHours()).padStart(2, '0')}:${String(lastChecked.getMinutes()).padStart(2, '0')}:${String(lastChecked.getSeconds()).padStart(2, '0')}`;
  }, [lastChecked]);

  return (
    <header className="h-12 px-4 border-b border-border bg-card flex items-center gap-3 shrink-0 select-none z-30">
      <nav className="flex items-center gap-2" data-testid="topbar-crumbs">
        {crumbs.map((c, i) => (
          <React.Fragment key={i}>
            <span
              className={`text-[13px] ${
                i === crumbs.length - 1
                  ? 'text-foreground font-semibold'
                  : 'text-muted-foreground'
              }`}
            >
              {c}
            </span>
            {i < crumbs.length - 1 && <span className="text-muted-foreground/40 text-[13px]">/</span>}
          </React.Fragment>
        ))}
      </nav>

      <div className="flex-grow" />

      {/* UI-OD6: demo mode is a simulation, said on every screen, not only where rows appear. */}
      <SimulationBadge />

      <button
        type="button"
        data-testid="topbar-command-button"
        onClick={() => setPaletteOpen(true)}
        className="flex items-center gap-2 text-[12.5px] px-3 py-1.5 bg-muted/40 border border-border rounded-full text-muted-foreground hover:text-foreground hover:border-primary/40 transition-colors cursor-pointer"
      >
        <Search size={12} className="shrink-0" />
        {/* J8-F17: the palette goes to pages; it does not search objects (Tier C). */}
        <span>Go to…</span>
        <span className="w-[1px] h-2.5 bg-border" />
        <kbd className="text-muted-foreground/80 font-semibold font-sans">{shortcut}</kbd>
      </button>

      <JobsButton />

      {/* Real-time Diagnostics Trigger */}
      <div className="relative">
        <button
          type="button"
          onClick={() => setShowStatusMenu(!showStatusMenu)}
          className="inline-flex items-center outline-none cursor-pointer"
        >
          <StatusDot variant={overallStatusDotVariant} label={overallStatusLabel} />
        </button>

        {showStatusMenu && (
          <>
            <div className="fixed inset-0 z-40" onClick={() => setShowStatusMenu(false)} role="presentation" onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setShowStatusMenu(false); }} />
            <div className="absolute right-0 mt-2 z-50 min-w-[270px] glass rounded-lg border border-border p-4 shadow-2xl animate-in fade-in slide-in-from-top-2 duration-150">
              <div className="flex justify-between items-center mb-1">
                <div className="flex items-center gap-1.5">
                  <StatusDot variant={overallStatusDotVariant} />
                  <span className="text-[11px] font-bold uppercase tracking-wider text-foreground">System Diagnostics</span>
                </div>
                <button
                  type="button"
                  onClick={checkHealth}
                  disabled={isChecking}
                  className="p-1 text-muted-foreground hover:text-primary hover:border-primary/30 border border-border rounded transition-all cursor-pointer disabled:opacity-50"
                >
                  <RefreshCw className={`w-3.5 h-3.5 ${isChecking ? 'animate-spin' : ''}`} />
                </button>
              </div>
              <div className="text-[11px] text-muted-foreground font-sans leading-normal">
                {lastCheckedLabel
                  ? `Probed live — last checked ${lastCheckedLabel}, every 30 s.`
                  : 'First probe in flight — nothing reported yet.'}
              </div>

              <span className="block h-[1px] bg-border my-2.5" />

              <DiagnosticsList systems={systems} compact />
            </div>
          </>
        )}
      </div>

      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
    </header>
  );
};

export default TerminalTopBar;
