import React from 'react';
import { Link } from 'react-router-dom';

/**
 * What the workbench says before any schema is promoted (UI audit J1-F15 = J5-F16). It used to
 * show empty panels, an enabled Run and a red "No active global schema" with no way on.
 *
 * Deliberately a notice beside the editor, not a replacement for it: `oql-editor` stays mounted
 * and Run stays enabled — the server decides (R1), and `workbench-contract.spec.ts` runs a query
 * on a never-promoted tenant and expects the NO_SCHEMA error text unchanged.
 */
export const NoSchemaNotice: React.FC = () => (
  <div
    role="status"
    data-testid="wb-no-schema"
    className="mx-4 mt-3 rounded-md border border-border bg-muted/30 px-4 py-3 text-xs text-foreground flex flex-col gap-1.5"
  >
    <span className="font-semibold">No schema is promoted yet.</span>
    <span className="text-muted-foreground">
      The workbench queries the promoted integrated schema; until there is one, every query answers
      &ldquo;No active global schema&rdquo;. To get one:
    </span>
    <ol className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[12px]">
      <li><Link to="/datasets" className="text-primary underline underline-offset-2">register datasets</Link></li>
      <li aria-hidden="true">→</li>
      <li><Link to="/alignments" className="text-primary underline underline-offset-2">enrol, align and compile</Link></li>
      <li aria-hidden="true">→</li>
      <li><Link to="/alignments" className="text-primary underline underline-offset-2">promote</Link></li>
      <li aria-hidden="true">→</li>
      <li className="text-muted-foreground">query here</li>
    </ol>
  </div>
);

export default NoSchemaNotice;
