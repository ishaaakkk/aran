import React, { useState } from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import { MIN_REFRESH_INTERVAL_SECONDS } from '@/services/types';
import type { DatasetAccessMode, DatasetRefreshPolicy } from '@/services/types';

interface DatasetFreshnessProps {
  /** The dataset's policy, or null when it has no upstream to refresh from. */
  policy: DatasetRefreshPolicy | null;
  /**
   * How the dataset is read (J2-F3). A null policy means "no copy to refresh", which is an upload
   * for a SNAPSHOT dataset and a live read for a LIVE one — two different facts.
   */
  accessMode?: DatasetAccessMode;
  onRefreshNow: () => Promise<void>;
  onSetPolicy: (policy: 'MANUAL' | 'EVERY', intervalSeconds?: number) => Promise<void>;
  disabled?: boolean;
  /**
   * What the dataset's live job is doing ("Refreshing…"), or null when none runs. While set,
   * "Refresh now" is disabled and says so: a second click used to start a second concurrent
   * refresh of the same dataset (J2-F11).
   */
  busyLabel?: string | null;
}

/** The intervals offered. Anything shorter than the floor the backend enforces is not offered. */
const INTERVALS: { label: string; seconds: number }[] = [
  { label: 'every 15 minutes', seconds: 900 },
  { label: 'every hour', seconds: 3_600 },
  { label: 'every 6 hours', seconds: 21_600 },
  { label: 'daily', seconds: 86_400 },
  { label: 'weekly', seconds: 604_800 },
];

/** "3 days", "4 hours", "12 minutes" — coarse on purpose; nobody needs seconds of staleness. */
function humanizeSeconds(seconds: number): string {
  const units: [number, string][] = [
    [86_400, 'day'],
    [3_600, 'hour'],
    [60, 'minute'],
  ];
  for (const [size, name] of units) {
    if (seconds >= size) {
      const n = Math.floor(seconds / size);
      return `${n} ${name}${n === 1 ? '' : 's'}`;
    }
  }
  return 'less than a minute';
}

/**
 * How fresh a dataset's copy is, and how often it is remade.
 *
 * Rendered for every dataset, including those with no upstream — because "this cannot be
 * refreshed, and here is why" is the answer a user needs when looking for a refresh button that
 * is not there. Absence of a policy is the signal, not an error.
 */
export const DatasetFreshness: React.FC<DatasetFreshnessProps> = ({
  policy,
  accessMode,
  onRefreshNow,
  onSetPolicy,
  disabled = false,
  busyLabel = null,
}) => {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (policy === null && accessMode === 'LIVE') {
    return (
      <div className="space-y-1.5" data-testid="dataset-freshness">
        <div className="text-[10px] text-muted-foreground uppercase">Freshness</div>
        <div
          className="text-[10px] text-muted-foreground font-mono leading-relaxed"
          data-testid="dataset-freshness-live"
        >
          Read live — every query reads the source directly, so there is no copy to refresh or to
          go stale.
        </div>
      </div>
    );
  }

  if (policy === null) {
    return (
      <div className="space-y-1.5" data-testid="dataset-freshness">
        <div className="text-[10px] text-muted-foreground uppercase">Freshness</div>
        <div
          className="text-[10px] text-muted-foreground font-mono leading-relaxed"
          data-testid="dataset-freshness-no-upstream"
        >
          No upstream — this dataset&apos;s data was uploaded, so the file it was created from is
          the source. Import from a repository table to make it refreshable.
        </div>
      </div>
    );
  }

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const selectValue = policy.policy === 'EVERY' ? String(policy.intervalSeconds ?? '') : 'MANUAL';

  return (
    <div className="space-y-1.5" data-testid="dataset-freshness">
      <div className="text-[10px] text-muted-foreground uppercase">Freshness</div>

      <div className="text-xs text-foreground font-mono" data-testid="dataset-freshness-source">
        from <span className="font-semibold">{policy.sourceTable}</span>
      </div>

      {/* Never refreshed reads differently from refreshed-just-now, so it is said differently.
          stalenessSeconds is null in the first case and 0 in the second. */}
      <div className="text-[10px] text-muted-foreground font-mono" data-testid="dataset-freshness-age">
        {policy.stalenessSeconds === null
          ? 'never refreshed'
          : `copy is ${humanizeSeconds(policy.stalenessSeconds)} old`}
      </div>

      <div className="flex items-center gap-2 pt-1">
        <select
          value={selectValue}
          disabled={disabled || busy}
          data-testid="dataset-refresh-policy-select"
          onChange={(e) =>
            run(() =>
              e.target.value === 'MANUAL'
                ? onSetPolicy('MANUAL')
                : onSetPolicy('EVERY', Number(e.target.value)),
            )
          }
          className="flex-1 rounded border border-border bg-background px-2 py-1 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
        >
          <option value="MANUAL">on request only</option>
          {INTERVALS.filter((i) => i.seconds >= MIN_REFRESH_INTERVAL_SECONDS).map((i) => (
            <option key={i.seconds} value={i.seconds}>
              {i.label}
            </option>
          ))}
        </select>

        <button
          type="button"
          disabled={disabled || busy || !!busyLabel}
          data-testid="dataset-refresh-now"
          onClick={() => run(onRefreshNow)}
          title={busyLabel ? 'This dataset’s job is still running — its card above says how far it got.' : undefined}
          className="flex items-center gap-1 rounded border border-border px-2 py-1 font-mono text-[10px] text-foreground hover:bg-muted/20 disabled:opacity-50"
        >
          <RefreshCw size={11} className={busy || busyLabel ? 'animate-spin' : ''} />
          {busyLabel ?? 'Refresh now'}
        </button>
      </div>

      {policy.nextRunAt && (
        <div className="text-[10px] text-muted-foreground font-mono" data-testid="dataset-refresh-next">
          next run {new Date(policy.nextRunAt).toLocaleString()}
        </div>
      )}

      {/* The last failure, not the last job. A schedule that has been failing for a week is the
          thing a user most needs to see, and it is not visible anywhere else. */}
      {policy.lastError && (
        <div
          className="flex items-start gap-1.5 text-[10px] text-amber-400 font-mono leading-relaxed"
          data-testid="dataset-refresh-error"
        >
          <AlertTriangle size={11} className="mt-0.5 shrink-0" />
          <span>last refresh failed: {policy.lastError}</span>
        </div>
      )}

      {error && (
        <div className="text-[10px] text-red-400 font-mono" data-testid="dataset-refresh-action-error">
          {error}
        </div>
      )}
    </div>
  );
};

export default DatasetFreshness;
