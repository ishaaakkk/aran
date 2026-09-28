import React from 'react';

interface DatasetFailureNoteProps {
  /** The dataset's name — the testids are keyed on it (`datasets-failure-*`, `datasets-retry-*`). */
  name: string;
  /** Why the last job failed, when the job said; nothing is rendered in its place otherwise. */
  reason: string | null;
  /** The retry the row offers, when one exists for the job that failed. */
  retryLabel?: string | null;
  onRetry?: () => void;
}

/**
 * What a failed dataset row says beside its "Failed" badge: the last job's reason and a Retry of
 * the same operation (UI audit J2-F5 — a failed upload used to be a red badge and nothing else).
 *
 * Coloured with the skin's `--error` token, not a raw palette shade: the light Field Notes skin
 * is the default, and the pale pinks this started with read at under 2:1 on a white row
 * (WCAG AA wants 4.5:1; `tokenContrast.test.ts` holds the tokens to it in both skins).
 */
export const DatasetFailureNote: React.FC<DatasetFailureNoteProps> = ({ name, reason, retryLabel, onRetry }) => (
  <>
    {reason && (
      <span
        data-testid={`datasets-failure-${name}`}
        className="font-mono text-[10px] text-error truncate max-w-[260px]"
        title={reason}
      >
        {reason}
      </span>
    )}
    {retryLabel && onRetry && (
      <button
        type="button"
        data-testid={`datasets-retry-${name}`}
        onClick={(e) => { e.stopPropagation(); onRetry(); }}
        className="font-mono text-[10px] uppercase tracking-wider font-semibold px-1.5 py-0.5 rounded-sm border border-error/50 text-error hover:bg-error/10"
      >
        {retryLabel}
      </button>
    )}
  </>
);

export default DatasetFailureNote;
