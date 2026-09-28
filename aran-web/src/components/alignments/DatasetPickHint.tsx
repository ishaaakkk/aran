import React from 'react';
import { Link } from 'react-router-dom';
import type { EmptyListClaim } from '@/lib/listLoads';

/** `pick` when there are datasets to choose from; otherwise what the project's dataset load says. */
export type DatasetPickState = 'pick' | EmptyListClaim;

/**
 * The schema pane's hint when no dataset is selected. It names the real control and the missing
 * prerequisite (J1-F15) — the selector is this pane's header, not "the top bar" — and it says "no
 * datasets yet" only when the project's dataset list loaded completely (review of UA2): after a
 * partial failure the missing datasets may be exactly the unread ones.
 */
export const DatasetPickHint: React.FC<{ state: DatasetPickState }> = ({ state }) => {
  const text = 'text-[10.5px] text-muted-foreground font-mono';
  switch (state) {
    case 'empty':
      return (
        <div className={text}>
          This project has no datasets yet.{' '}
          <Link to="/datasets" className="text-primary underline underline-offset-2">Register a dataset</Link>
        </div>
      );
    case 'incomplete':
      return (
        <div className={text} data-testid="align-datasets-incomplete">
          No datasets loaded — some of this project&apos;s repositories could not be read (named
          above), so this is not the whole list.
        </div>
      );
    case 'error':
      return <div className={text}>This project&apos;s datasets could not be loaded — see the notice above.</div>;
    case 'loading':
      return <div className={text}>Loading this project&apos;s datasets…</div>;
    default:
      return <div className={text}>Pick one in this panel&apos;s dataset selector above to inspect its schema.</div>;
  }
};

export default DatasetPickHint;
