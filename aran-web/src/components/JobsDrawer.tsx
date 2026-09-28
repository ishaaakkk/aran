import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2, RefreshCw, X } from 'lucide-react';
import { Dialog } from '@/components/ui/Dialog';
import { StatusDot } from '@/components/StatusDot';
import { api } from '@/services/api';
import type { Job } from '@/services/types';
import { useJobsStore } from '@/stores/jobsStore';
import { useProjectsStore } from '@/stores/projectsStore';
import { relativeTime } from '@/lib/adminFormat';
import { errorMessage } from '@/lib/errors';
import {
  formatDuration,
  jobDurationMs,
  jobFailureReason,
  jobHref,
  jobKind,
  jobObjectLabel,
  jobSections,
  retryFor,
  type RetryAction,
} from '@/lib/jobs';

/**
 * Every job you can see, in one place, from every page (UI audit J8-F7 — "nothing ever says
 * whether it finished or why it failed; the docs send users to curl"). Running, failed and recent
 * jobs, each with its kind, the object it ran on (a link to it), when it started, how long it ran,
 * the stage it reported or the reason it failed, and a Retry that re-invokes the same operation
 * where one exists. Databricks' job-runs list, dbt Cloud's run history and Snowsight's query
 * history are the exemplars: one list, filterable by state, where a failure keeps its reason.
 *
 * No cancel — nothing can stop a running job (D7), and the footer says so rather than letting the
 * absence of a button be read as an oversight.
 */
interface JobsDrawerProps {
  open: boolean;
  onClose: () => void;
}

export const JobsDrawer: React.FC<JobsDrawerProps> = ({ open, onClose }) => {
  const jobs = useJobsStore((s) => s.jobs);
  const loadState = useJobsStore((s) => s.loadState);
  const loadError = useJobsStore((s) => s.loadError);
  const refresh = useJobsStore((s) => s.refresh);
  const started = useJobsStore((s) => s.started);
  const projects = useProjectsStore((s) => s.projects);
  const loadProjects = useProjectsStore((s) => s.load);
  const setActiveProject = useProjectsStore((s) => s.setActiveProject);
  const navigate = useNavigate();
  const [now, setNow] = useState<number | null>(null);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [retryErrors, setRetryErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (!open) return;
    // A job's project names only it by id; the list of projects names it in words.
    if (useProjectsStore.getState().projects.length === 0) void loadProjects();
    const read = () => setNow(Date.now());
    const first = setTimeout(read, 0);
    const tick = setInterval(read, 1000);
    return () => {
      clearTimeout(first);
      clearInterval(tick);
    };
  }, [open, loadProjects]);

  const projectNames = useMemo(
    () => Object.fromEntries(projects.map((p) => [p.id, p.name])),
    [projects],
  );

  const retry = async (job: Job, action: RetryAction) => {
    setRetrying(job.id);
    setRetryErrors((prev) => {
      const next = { ...prev };
      delete next[job.id];
      return next;
    });
    try {
      const { jobId } = await action.run();
      started(jobId);
    } catch (err: unknown) {
      setRetryErrors((prev) => ({ ...prev, [job.id]: errorMessage(err, 'Could not start the job again.') }));
    } finally {
      setRetrying(null);
    }
  };

  const open_ = (job: Job) => {
    const href = jobHref(job);
    if (!href) return;
    if (job.projectId) setActiveProject(job.projectId);
    onClose();
    navigate(href);
  };

  return (
    <Dialog open={open} onClose={onClose} label="Jobs" variant="sheet" maxWidth="max-w-md" testId="jobs-drawer">
      <JobsDrawerView
        jobs={Object.values(jobs)}
        loadState={loadState}
        loadError={loadError}
        now={now}
        projectNames={projectNames}
        retrying={retrying}
        retryErrors={retryErrors}
        onRefresh={() => void refresh()}
        onClose={onClose}
        onOpen={open_}
        onRetry={(job, action) => void retry(job, action)}
      />
    </Dialog>
  );
};

interface JobsDrawerViewProps {
  jobs: Job[];
  loadState: 'idle' | 'loading' | 'ready' | 'error';
  loadError: string | null;
  now: number | null;
  projectNames: Record<string, string>;
  retrying: string | null;
  retryErrors: Record<string, string>;
  onRefresh: () => void;
  onClose: () => void;
  onOpen: (job: Job) => void;
  onRetry: (job: Job, action: RetryAction) => void;
}

/** The drawer's contents for one state of the jobs list — no store, no clock, no fetch. */
export const JobsDrawerView: React.FC<JobsDrawerViewProps> = ({
  jobs, loadState, loadError, now, projectNames, retrying, retryErrors,
  onRefresh, onClose, onOpen, onRetry,
}) => {
  const sections = jobSections(jobs);
  const neverLoaded = jobs.length === 0 && (loadState === 'idle' || loadState === 'loading');
  return (
    <>
      <div className="h-12 shrink-0 flex items-center justify-between px-4 border-b border-border bg-muted/20">
        <h2 className="text-sm font-semibold text-foreground">Jobs</h2>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={onRefresh}
            aria-label="Reload the jobs list"
            title="Reload the jobs list"
            className="p-1.5 rounded text-muted-foreground hover:text-foreground"
          >
            <RefreshCw size={14} className={loadState === 'loading' ? 'animate-spin' : ''} />
          </button>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close jobs"
            className="p-1.5 rounded text-muted-foreground hover:text-foreground"
          >
            <X size={16} />
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-5">
        {loadState === 'error' && (
          // A failed list is said as such — never shown as "no jobs" (UI audit J8-F2's rule).
          <div role="alert" data-testid="jobs-drawer-error"
            className="rounded border border-error/40 bg-destructive text-destructive-foreground p-3 text-xs space-y-2">
            <div className="font-semibold">Couldn't load jobs{jobs.length > 0 ? ' — showing what this page last knew' : ''}.</div>
            <div className="font-mono text-[11px]">{loadError}</div>
            <button type="button" onClick={onRefresh}
              className="h-7 px-3 rounded border border-destructive-foreground/40 uppercase tracking-wider text-[10px] font-semibold hover:bg-destructive-foreground/10">
              Retry
            </button>
          </div>
        )}

        {neverLoaded && (
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 size={14} className="animate-spin" /> Loading jobs…
          </div>
        )}

        {!neverLoaded && loadState !== 'error' && jobs.length === 0 && (
          <p className="text-xs text-muted-foreground" data-testid="jobs-drawer-empty">
            No jobs yet. Imports, bootstraps, refreshes, profiling, rebuilds and materializations
            appear here while they run and after they finish.
          </p>
        )}

        <JobSection title="Running" testId="jobs-running" jobs={sections.running} now={now}
          projectNames={projectNames} retrying={retrying} retryErrors={retryErrors} onOpen={onOpen} onRetry={onRetry} />
        <JobSection title="Failed" testId="jobs-failed" jobs={sections.failed} now={now}
          projectNames={projectNames} retrying={retrying} retryErrors={retryErrors} onOpen={onOpen} onRetry={onRetry} />
        <JobSection title="Recent" testId="jobs-recent" jobs={sections.recent} now={now}
          projectNames={projectNames} retrying={retrying} retryErrors={retryErrors} onOpen={onOpen} onRetry={onRetry} />
      </div>

      <p className="shrink-0 border-t border-border px-4 py-3 text-[11px] text-muted-foreground">
        Jobs run on the server: leaving a page or closing this panel does not stop them, and a
        running job cannot be cancelled.
      </p>
    </>
  );
};

interface JobSectionProps {
  title: string;
  testId: string;
  jobs: Job[];
  now: number | null;
  projectNames: Record<string, string>;
  retrying: string | null;
  retryErrors: Record<string, string>;
  onOpen: (job: Job) => void;
  onRetry: (job: Job, action: RetryAction) => void;
}

const STATUS_VARIANT: Record<Job['status'], 'ok' | 'warn' | 'err' | 'idle' | 'accent'> = {
  PENDING: 'idle', RUNNING: 'accent', SUCCEEDED: 'ok', FAILED: 'err', CANCELLED: 'warn',
};

const JobSection: React.FC<JobSectionProps> = ({
  title, testId, jobs, now, projectNames, retrying, retryErrors, onOpen, onRetry,
}) => {
  if (jobs.length === 0) return null;
  return (
    <section aria-label={`${title} jobs`} data-testid={testId} className="space-y-2">
      <h3 className="text-[10px] font-mono uppercase tracking-wider text-muted-foreground">
        {title} · {jobs.length}
      </h3>
      <ul className="space-y-2">
        {jobs.map((job) => {
          const kind = jobKind(job.jobType);
          const object = jobObjectLabel(job, projectNames);
          const href = jobHref(job);
          const startedIso = job.startedAt ?? job.createdAt;
          const duration = job.finishedAt || now != null ? jobDurationMs(job, now ?? 0) : null;
          const action = retryFor(job, api);
          const failed = job.status === 'FAILED' || job.status === 'CANCELLED';
          return (
            <li key={job.id} data-testid={`jobs-item-${job.id}`} data-job-status={job.status}
              className="rounded border border-border bg-muted/10 p-3 text-xs space-y-1.5">
              <div className="flex items-center justify-between gap-2">
                <span className="min-w-0 truncate">
                  <span className="font-semibold text-foreground">{kind.label}</span>
                  <span className="text-muted-foreground"> · </span>
                  {href ? (
                    <button type="button" onClick={() => onOpen(job)}
                      className="font-mono text-primary hover:underline" title={`Open ${object}`}>
                      {object}
                    </button>
                  ) : (
                    <span className="font-mono text-foreground">{object}</span>
                  )}
                </span>
                <StatusDot variant={STATUS_VARIANT[job.status]} label={job.status.toLowerCase()}
                  blink={job.status === 'RUNNING'} />
              </div>
              <div className="flex items-center gap-3 font-mono text-[10.5px] text-muted-foreground">
                <span title={new Date(startedIso).toLocaleString()}>
                  {job.startedAt ? 'started' : 'queued'} {now != null ? relativeTime(startedIso, now) : ''}
                </span>
                {duration != null && <span>ran {formatDuration(duration)}</span>}
                {job.projectId && projectNames[job.projectId] && job.subject?.kind !== 'project' && (
                  <span className="truncate">{projectNames[job.projectId]}</span>
                )}
              </div>
              {!failed && job.progressStage && job.status !== 'SUCCEEDED' && (
                <div className="text-[11px] text-foreground">{job.progressStage}</div>
              )}
              {failed && (
                <div className="text-[11px] text-error space-y-1.5">
                  <div>
                    {job.errorCode && <span className="font-mono font-semibold">{job.errorCode}: </span>}
                    <span className="select-text break-words">{jobFailureReason(job)}</span>
                  </div>
                  {action && (
                    <button type="button" data-testid={`jobs-retry-${job.id}`}
                      onClick={() => onRetry(job, action)} disabled={retrying === job.id}
                      className="h-7 px-3 rounded border border-error/50 text-error hover:bg-error/10 uppercase tracking-wider text-[10px] font-semibold disabled:opacity-50">
                      {retrying === job.id ? 'Starting…' : action.label}
                    </button>
                  )}
                  {retryErrors[job.id] && <div role="alert">{retryErrors[job.id]}</div>}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
};

export default JobsDrawer;
