import React, { useEffect, useState } from 'react';
import { api } from '../services/api';
import type { Job } from '../services/types';
import { StatusDot } from './StatusDot';
import { useJobEvents } from '../hooks/useJobEvents';
import { useJobsStore } from '../stores/jobsStore';
import {
  formatDuration,
  isLive,
  jobDurationMs,
  jobFailureReason,
  jobKind,
  jobObjectLabel,
  retryFor,
  type RetryAction,
} from '../lib/jobs';
import { errorMessage } from '../lib/errors';

interface JobCardProps {
  jobId: string;
  /** The operational title. Defaults to the job kind's `aran …` command and what it ran on. */
  title?: string;
  /** Called once, when the job is first seen in a terminal state (see `useJobEvents`). */
  onDone?: (job: Job) => void;
  /**
   * Offer "Retry" on a failed job — the same operation re-invoked (`retryFor`) — and hand the
   * new job's id here so the page can show it in this card's place.
   */
  onRetried?: (newJobId: string) => void;
  /** Offer "Dismiss" on a failed job: the page stops showing it (the jobs drawer keeps it). */
  onDismiss?: () => void;
}

const DOT_VARIANT: Record<Job['status'], 'ok' | 'warn' | 'err' | 'idle' | 'accent'> = {
  PENDING: 'idle',
  RUNNING: 'accent',
  SUCCEEDED: 'ok',
  FAILED: 'err',
  CANCELLED: 'warn',
};

/**
 * Live view of one job.
 *
 * Shows only what the server said: the stage the task reported, a bar only if it reported a
 * percentage, and how long it has run since the server started it — a fact, not progress. A job
 * that reports no stage gets a spinner and a clock, never a fabricated bar.
 *
 * The job lives in the jobs store, so a card mounted after a navigation or a reload picks the job
 * up where it is (UI audit J8-F7), and a failure stays on screen with its reason until someone
 * dismisses it or retries (J2-F5, J6-F7) — it used to vanish with the component that watched it.
 * There is no cancel: nothing can stop a running job (D7).
 */
export const JobCard: React.FC<JobCardProps> = ({ jobId, title, onDone, onRetried, onDismiss }) => {
  const job = useJobEvents(jobId, onDone);
  const started = useJobsStore((s) => s.started);
  const [now, setNow] = useState<number | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [retryError, setRetryError] = useState<string | null>(null);

  const live = !job || isLive(job);
  useEffect(() => {
    if (!live) return;
    // The clock is read here, not during render: a render must not depend on when it ran.
    const read = () => setNow(Date.now());
    const first = setTimeout(read, 0);
    const tick = setInterval(read, 1000);
    return () => {
      clearTimeout(first);
      clearInterval(tick);
    };
  }, [live]);

  const retry = job && onRetried ? retryFor(job, api) : null;
  const runRetry = async () => {
    if (!retry) return;
    setRetrying(true);
    setRetryError(null);
    try {
      const { jobId: next } = await retry.run();
      started(next);
      onRetried?.(next);
    } catch (err: unknown) {
      setRetryError(errorMessage(err, 'Could not start the job again.'));
    } finally {
      setRetrying(false);
    }
  };

  return (
    <JobCardView
      job={job}
      title={title}
      now={now}
      retry={retry}
      retrying={retrying}
      retryError={retryError}
      onRetry={() => void runRetry()}
      onDismiss={onDismiss}
    />
  );
};

interface JobCardViewProps {
  job: Job | null;
  title?: string;
  /** The clock for a live job's duration; null before the first tick. */
  now: number | null;
  retry: RetryAction | null;
  retrying?: boolean;
  retryError?: string | null;
  onRetry?: () => void;
  onDismiss?: () => void;
}

/** The card's markup for one state of one job — no store, no clock, no fetch. */
export const JobCardView: React.FC<JobCardViewProps> = ({
  job, title, now, retry, retrying = false, retryError = null, onRetry, onDismiss,
}) => {
  const status = job?.status ?? 'PENDING';
  const live = !job || isLive(job);
  const duration = job && (!live || now != null) ? jobDurationMs(job, now ?? 0) : null;
  const object = job ? jobObjectLabel(job) : '—';
  const heading = title
    ?? (job ? `${jobKind(job.jobType).command}${object === '—' ? '' : ` ${object}`}` : 'aran job');

  let stageText: string;
  if (job?.progressStage) stageText = job.progressStage;
  else if (status === 'PENDING') stageText = job?.startedAt ? 'starting…' : 'queued — waiting to start';
  else if (status === 'RUNNING') stageText = 'waiting for the first stage…';
  else stageText = '—';

  const failed = !!job && (job.status === 'FAILED' || job.status === 'CANCELLED');

  return (
    <div
      className="bg-card border border-border rounded overflow-hidden font-mono"
      data-testid="job-card"
      data-job-status={status}
    >
      <div className="h-9 flex items-center justify-between px-4 bg-muted/30 border-b border-border">
        <span className="text-[11px] text-muted-foreground select-none">
          <span className="text-primary">$</span> {heading}
        </span>
        <StatusDot variant={DOT_VARIANT[status]} label={status.toLowerCase()} blink={live} />
      </div>

      <div className="p-4 space-y-2">
        <div className="flex items-center justify-between text-[11px]">
          {/* Only the stage is announced: the duration beside it changes every second. */}
          <span className="text-foreground" aria-live="polite">{stageText}</span>
          <span className="text-muted-foreground tabular-nums" title="Time since the server started the job">
            {duration != null ? formatDuration(duration) : ''}
          </span>
        </div>

        {job?.progressPercent != null && (
          <div className="h-1 w-full bg-muted rounded overflow-hidden">
            <div
              className="h-full bg-violet-500 transition-[width] duration-300"
              style={{ width: `${Math.min(100, Math.max(0, job.progressPercent))}%` }}
            />
          </div>
        )}

        {failed && (
          <div
            role="alert"
            data-testid="job-card-error"
            className="border border-error/40 bg-destructive text-destructive-foreground rounded p-3 text-[11px] space-y-2"
          >
            <div>
              <div className="uppercase tracking-wider text-[10px] font-semibold mb-1">
                {job.errorCode ?? 'failed'}
              </div>
              <span className="select-text break-words">{jobFailureReason(job)}</span>
            </div>
            {(retry || onDismiss) && (
              <div className="flex items-center gap-2">
                {retry && (
                  <button
                    type="button"
                    data-testid="job-card-retry"
                    onClick={onRetry}
                    disabled={retrying}
                    className="h-7 px-3 rounded border border-destructive-foreground/40 text-destructive-foreground hover:bg-destructive-foreground/10 uppercase tracking-wider text-[10px] font-semibold disabled:opacity-50"
                  >
                    {retrying ? 'Starting…' : retry.label}
                  </button>
                )}
                {onDismiss && (
                  <button
                    type="button"
                    data-testid="job-card-dismiss"
                    onClick={onDismiss}
                    className="h-7 px-3 rounded border border-border text-muted-foreground hover:text-foreground uppercase tracking-wider text-[10px] font-semibold"
                  >
                    Dismiss
                  </button>
                )}
              </div>
            )}
            {retryError && <div className="font-semibold">{retryError}</div>}
          </div>
        )}
      </div>
    </div>
  );
};

export default JobCard;
