import React from 'react';
import { ErrorNotice } from './ErrorNotice';
import type { SourceFailure } from '@/lib/fanOut';

/**
 * The banner a partial load shows above the rows that did arrive (UI audit J8-F2): how many of
 * the sources failed, which ones, the first failure's sentence and reference, and a Retry. A
 * list with a source unaccounted for is not complete, and this is how it says so.
 */
export const LoadFailures: React.FC<{
  failures: SourceFailure[];
  total: number;
  /** Singular noun for a source, e.g. "project" or "repository". */
  sourceNoun: string;
  onRetry: () => void;
  retrying?: boolean;
  testId: string;
}> = ({ failures, total, sourceNoun, onRetry, retrying, testId }) => {
  if (failures.length === 0) return null;
  const plural = total === 1 ? sourceNoun : sourceNoun.endsWith('y') ? `${sourceNoun.slice(0, -1)}ies` : `${sourceNoun}s`;
  return (
    <ErrorNotice
      compact
      testId={testId}
      title={`${failures.length} of ${total} ${plural} could not be read — this list is incomplete.`}
      error={failures[0].error}
      onRetry={onRetry}
      retrying={retrying}
    >
      <span className="text-muted-foreground">
        Not loaded: <span className="font-mono text-foreground">{failures.map((f) => f.name).join(', ')}</span>
      </span>
    </ErrorNotice>
  );
};

export default LoadFailures;
