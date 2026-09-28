import React from 'react';
import { AlertTriangle, Lock } from 'lucide-react';
import type { RenameImpact } from '@/lib/datasetAccess';

/**
 * What renaming this dataset does, said under the name field before Update is pressed
 * (UI-OD8, audit J2-F6). Pressing Update after reading the warning is the confirmation — the
 * warning never adds a step, because d1-schema-drift renames an upload on purpose.
 */
export const DatasetRenameNote: React.FC<{ impact: RenameImpact; id: string }> = ({ impact, id }) => {
  if (impact === 'blocked') {
    return (
      <p id={id} data-testid="dataset-rename-blocked"
        className="flex items-start gap-1.5 font-mono text-[10.5px] leading-relaxed text-muted-foreground pt-1">
        <Lock size={11} className="shrink-0 mt-0.5" aria-hidden="true" />
        <span>
          This dataset is read live from its source, and its name is the table every query reads.
          Renaming it would break every query over its class, so it cannot be renamed. The
          description can still be edited; to read a different table, register it as its own dataset.
        </span>
      </p>
    );
  }
  if (impact === 'warn') {
    return (
      <p id={id} data-testid="dataset-rename-warning" role="status"
        className="flex items-start gap-1.5 font-mono text-[10.5px] leading-relaxed text-warning pt-1">
        <AlertTriangle size={11} className="shrink-0 mt-0.5" aria-hidden="true" />
        <span>
          The name seeds this dataset&apos;s schema IRIs. Its next re-bootstrap or refresh re-mints
          them, and if any alignment uses this dataset that re-bootstrap or refresh is held as
          BREAKING drift until you repair the alignments in the drift panel. Queries keep reading
          the current schema until then.
        </span>
      </p>
    );
  }
  return null;
};

export default DatasetRenameNote;
