import React from 'react';
import { statusLabel, type SystemRow } from '@/lib/diagnostics';

/**
 * The one rendering of the service diagnostics, used by the offline page and the top-bar
 * popover (UI audit J1-F14). Rows say what each component does; hosts and ports sit behind
 * "Operator details", for whoever runs the stack.
 */
const DOT: Record<SystemRow['status'], string> = {
  unknown: 'bg-neutral-500',
  operational: 'bg-emerald-500',
  outage: 'bg-rose-500',
};

const BADGE: Record<SystemRow['status'], string> = {
  unknown: 'text-muted-foreground',
  operational: 'text-success',
  outage: 'text-error',
};

export const DiagnosticsList: React.FC<{ systems: SystemRow[]; compact?: boolean }> = ({ systems, compact = false }) => (
  <div className="flex flex-col gap-1.5 text-left" data-testid="diagnostics-list">
    <ul className="flex flex-col gap-1.5">
      {systems.map((sys) => (
        <li
          key={sys.key}
          className={`flex items-center gap-2.5 border border-border/60 rounded bg-muted/20 ${compact ? 'p-2' : 'px-4 py-3'}`}
        >
          <span className={`w-2 h-2 rounded-full shrink-0 ${DOT[sys.status]}`} aria-hidden="true" />
          <div className="flex-1 min-w-0">
            <div className="text-[11px] font-semibold text-foreground leading-tight">{sys.name}</div>
            <div className="text-[10.5px] text-muted-foreground mt-0.5">{sys.role}</div>
          </div>
          <span className={`text-[10.5px] font-bold uppercase tracking-wider font-mono ${BADGE[sys.status]}`}>
            {statusLabel(sys.status)}
          </span>
        </li>
      ))}
    </ul>
    <details className="text-[10.5px] text-muted-foreground">
      <summary className="cursor-pointer">Operator details</summary>
      <dl className="mt-1.5 space-y-1">
        {systems.map((sys) => (
          <div key={sys.key}>
            <dt className="font-semibold text-foreground/85 inline">{sys.name}: </dt>
            <dd className="inline">{sys.operatorDetail}</dd>
          </div>
        ))}
      </dl>
    </details>
  </div>
);

export default DiagnosticsList;
