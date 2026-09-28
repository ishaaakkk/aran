import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import useProjectsStore from '@/stores/projectsStore';
import { api } from '@/services/api';
import { orientation } from '@/lib/orientation';
import { ErrorNotice } from '@/components/ErrorNotice';
import { GettingStarted } from '@/components/GettingStarted';
import { DataTable, Column } from '@/components/DataTable';
import { TerminalCard } from '@/components/TerminalCard';
import { StatusDot } from '@/components/StatusDot';
import { TailLog, LogRow } from '@/components/TailLog';
import { StatStrip, StatTile } from '@/components/StatStrip';
import type { DashboardSummary } from '@/services/types';
import { FolderGit, Database, CheckCircle2, Layers } from 'lucide-react';

/**
 * DashboardView.
 *
 * Every tile is a count the backend derived and every log line is an audit row
 * it wrote. There are deliberately no sparklines: Aran does not retain a time
 * series for any of these, and a trend line drawn from nothing is a lie about
 * what the system knows.
 */
export const DashboardView: React.FC = () => {
  const navigate = useNavigate();
  // Selected field by field: `load()` fires several separate `set()` calls, and a
  // whole-store subscription re-renders this whole view on each one.
  const projects = useProjectsStore((s) => s.projects);
  const projectsStatus = useProjectsStore((s) => s.status);
  const projectsError = useProjectsStore((s) => s.loadError);
  const load = useProjectsStore((s) => s.load);
  const setActiveProject = useProjectsStore((s) => s.setActiveProject);

  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [summaryError, setSummaryError] = useState<unknown>(null);

  const loadSummary = useCallback(() => {
    setSummaryError(null);
    api
      .getDashboardSummary()
      .then((s) => setSummary(s))
      .catch((err: unknown) => setSummaryError(err));
  }, []);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadSummary();
    // `load` is a zustand action and `loadSummary` a memoised callback — both stable — so
    // this still runs exactly once on mount.
  }, [load, loadSummary]);

  const stats: StatTile[] = useMemo(() => {
    // A tile that could not load says so; it never reads as a zero (J1-F7).
    const failed = !summary && summaryError != null;
    const val = (n: number | undefined) => (summary ? String(n ?? 0).padStart(2, '0') : '—');
    const delta = (text: string) => (failed ? "couldn't load" : summary ? text : 'loading');

    return [
      {
        key: 'projects',
        label: 'projects',
        value: val(summary?.projects),
        delta: delta('visible to you'),
        tone: 'var(--primary)',
        icon: <FolderGit size={14} className="text-primary" />,
      },
      {
        key: 'repositories',
        label: 'repositories',
        value: val(summary?.repositories),
        delta: delta('registered sources'),
        tone: 'var(--muted-foreground)',
        icon: <Database size={14} className="text-muted-foreground" />,
      },
      {
        key: 'datasets',
        label: 'datasets',
        value: val(summary?.datasets),
        delta: delta(summary ? `${summary.readyDatasets} bootstrapped` : ''),
        tone: 'var(--success)',
        icon: <CheckCircle2 size={14} className="text-emerald-500" />,
      },
      {
        key: 'schema',
        label: 'active schema',
        value: summary ? (summary.activeSchemaVersion !== null ? `v${summary.activeSchemaVersion}` : 'none') : '—',
        delta: delta(summary?.activeSchemaVersion != null ? 'promoted' : 'nothing promoted yet'),
        tone: 'var(--warning)',
        icon: <Layers size={14} className="text-amber-500" />,
      },
    ];
  }, [summary, summaryError]);

  // No progress column (J1-F10): nothing ever wrote the field, so every project read 0 %
  // forever, a promoted one included.
  const projCols: Column[] = [
    { key: 'idx', label: '', width: '28px', num: true, mono: true },
    { key: 'name', label: 'project', mono: true },
    { key: 'status', label: 'status', width: '110px' },
    { key: 'updated', label: 'created', width: '150px', align: 'right', mono: true },
  ];

  const recentProjectsMapped = useMemo(() => {
    const list = projects || [];
    return list.slice(0, 5).map((p, index) => {
      let updatedTime = '—';
      if (p.createdAt) {
        // Full date, not time-of-day: "14:32" under a column labeled "created" made a
        // project from last month read as today's (redesign brief, dashboard MED).
        const d = new Date(p.createdAt);
        const pad = (n: number) => String(n).padStart(2, '0');
        updatedTime = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
      }
      return {
        id: p.id,
        idx: String(index + 1).padStart(2, '0'),
        name: p.name,
        color: p.color,
        status: (p.status || 'ACTIVE').toLowerCase(),
        updated: updatedTime,
      };
    });
  }, [projects]);

  /** Audit rows rendered as log lines. The level is INFO because that is what an audit row is. */
  const auditRows: LogRow[] = useMemo(() => {
    if (!summary) return [];
    return summary.recentAudit.map((e) => {
      const d = new Date(e.ts);
      const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(
        d.getSeconds(),
      ).padStart(2, '0')}`;
      return {
        time,
        lvl: 'INFO',
        src: e.action,
        msg: `${e.target}${e.actor ? `  by ${e.actor.slice(0, 8)}` : '  by system'}`,
      };
    });
  }, [summary]);

  const statusVar = (s: string): 'ok' | 'warn' | 'idle' => {
    if (s === 'active') return 'ok';
    if (s === 'review') return 'warn';
    return 'idle';
  };

  // The row opens ITS project, not the list (J1-F10), and makes it the working project.
  const openProject = (row: { id: string }) => {
    setActiveProject(row.id);
    navigate('/projects', { state: { open: row.id } });
  };

  return (
    <div className="flex-grow flex flex-col p-6 gap-6 overflow-hidden min-h-0 select-none">
      {summaryError != null && (
        <ErrorNotice
          compact
          testId="dashboard-summary-error"
          title="Couldn't load the workspace summary"
          error={summaryError}
          onRetry={loadSummary}
        />
      )}

      <div id="tour-dashboard-stats">
        <StatStrip tiles={stats} />
      </div>

      {/* First-run orientation, from the server's own counts (J1-F2, J1-F3). */}
      {summary && <GettingStarted guide={orientation(summary)} />}

      {/* Main grid: projects | audit feed */}
      <div className="flex-grow grid grid-cols-1 lg:grid-cols-[1.5fr_1fr] gap-6 overflow-hidden min-h-0">
        <div className="overflow-y-auto">
          <DataTable
            command="Projects"
            columns={projCols}
            rows={recentProjectsMapped}
            onRowClick={openProject}
            emptyMessage={
              projectsStatus === 'error' ? (
                // A failed load is not "No projects yet" (J1-F7): that invited a duplicate.
                <ErrorNotice
                  testId="dashboard-projects-error"
                  title="Couldn't load your projects"
                  error={projectsError}
                  onRetry={() => void load()}
                />
              ) : projectsStatus !== 'ready' ? (
                'loading projects…'
              ) : (
                <div className="flex flex-col items-center gap-3 py-4">
                  <span className="font-mono text-xs text-muted-foreground">
                    No projects yet — projects group your repositories, datasets and products.
                  </span>
                  <button
                    type="button"
                    onClick={() => navigate('/projects', { state: { create: true } })}
                    className="h-8 px-4 rounded-md border border-primary/50 bg-primary/10 text-primary text-[12.5px] font-semibold hover:bg-primary/20 transition-colors cursor-pointer"
                  >
                    + Create your first project
                  </button>
                </div>
              )
            }
            renderCell={{
              name: (row) => (
                <span className="inline-flex items-center gap-2">
                  <span
                    className="w-1.5 h-1.5 rounded-full shrink-0"
                    style={{ backgroundColor: row.color || 'var(--primary)' }}
                  />
                  <span>{row.name}</span>
                </span>
              ),
              status: (row) => (
                <StatusDot variant={statusVar(row.status)} label={row.status} />
              ),
            }}
          />
        </div>

        <div id="tour-dashboard-activity" className="overflow-y-auto">
          <TerminalCard command="Recent activity">
            {/* The other half of the summary payload the tiles read: while it loads, or after it
                failed, "No log entries." would be a claim about the audit log (FRONTEND-6). */}
            <TailLog
              rows={auditRows}
              emptyMessage={
                summary ? 'No activity recorded yet.'
                  : summaryError != null ? "Couldn't load recent activity — see the notice above."
                    : 'loading activity…'
              }
            />
          </TerminalCard>
        </div>
      </div>
    </div>
  );
};

export default DashboardView;
