import React from 'react';
import { Loader2 } from 'lucide-react';
import { ErrorNotice } from './ErrorNotice';
import type { EmptyListClaim } from '@/lib/listLoads';

/**
 * What a list shows where its rows would be, when there are none (UI audit J8-F2, J2-F22) — one
 * component for every aggregated list, so no view can skip an arm. `children` is the onboarding
 * empty state ("No … yet" and its Register), and it is rendered ONLY for a complete, empty load:
 * a partial load that brought back nothing says so instead. The "N of M could not be read" banner
 * above the list carries the names, the reference and the one Retry, so this body adds no second
 * button (Snowsight and BigQuery Studio both keep one Retry per failed load).
 */
export const ListEmptyBody: React.FC<{
  claim: EmptyListClaim;
  /** Plural noun, lower case: "datasets", "repositories". */
  noun: string;
  loadingLabel: string;
  errorTitle: string;
  error: unknown;
  onRetry: () => void;
  errorTestId: string;
  incompleteTestId: string;
  children: React.ReactNode;
}> = ({ claim, noun, loadingLabel, errorTitle, error, onRetry, errorTestId, incompleteTestId, children }) => {
  switch (claim) {
    case 'loading':
      return (
        <div role="status" className="flex flex-col items-center gap-3 text-muted-foreground">
          <Loader2 className="animate-spin w-8 h-8 text-primary/50" aria-hidden="true" />
          <span className="font-mono text-xs uppercase tracking-widest">{loadingLabel}</span>
        </div>
      );
    case 'error':
      return <ErrorNotice testId={errorTestId} title={errorTitle} error={error} onRetry={onRetry} />;
    case 'filtered':
      return (
        <div role="status" className="py-8 text-center text-xs text-muted-foreground">
          No {noun} match the current search or filter.
        </div>
      );
    case 'incomplete':
      return (
        <div role="status" data-testid={incompleteTestId} className="flex flex-col items-center gap-2 py-8 px-4 text-center">
          <h3 className="text-base font-semibold text-foreground tracking-tight">
            Nothing loaded from the sources that answered
          </h3>
          <p className="text-xs text-muted-foreground max-w-md">
            Some sources could not be read — they are named above — so this does not mean you
            have no {noun}. Retry once they answer before registering anything again.
          </p>
        </div>
      );
    case 'empty':
      return <>{children}</>;
  }
};

export default ListEmptyBody;
