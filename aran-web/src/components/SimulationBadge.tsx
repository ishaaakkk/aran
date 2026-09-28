import React from 'react';
import { FlaskConical } from 'lucide-react';
import { isDemoUser } from '@/services/demo/demoInterceptor';

/**
 * "Simulated results — demo data", shown in demo mode wherever results appear (J1-F5, UI-OD6:
 * demo mode is labelled a simulation; a real read-only demo tenant is out of scope). The demo's query answers are canned rows chosen by keywords in the query text
 * (`demoInterceptor.demoQueryResult`), so without this a typo returns plausible rows that read as
 * an engine's answer. Reads the one demo gate api.ts uses; renders nothing for anyone else.
 *
 * The words are the foreground colour and the amber is on the tint, border and icon: the warning
 * token on its own tint is under WCAG AA at 11 px in the light skin (`ui/contrast.test.tsx`).
 */
export const SimulationBadge: React.FC<{ className?: string }> = ({ className = '' }) => {
  if (!isDemoUser()) return null;
  return (
    <span
      role="note"
      data-testid="demo-simulation-badge"
      title="Demo mode: no engine runs. Results are canned demo rows chosen by the words in your query, not computed."
      className={`inline-flex items-center gap-1 rounded-full border border-warning/50 bg-warning/10 px-2 py-0.5 text-[11px] font-semibold text-foreground whitespace-nowrap ${className}`}
    >
      <FlaskConical size={11} aria-hidden="true" className="shrink-0 text-warning" />
      Simulated results — demo data
    </span>
  );
};

export default SimulationBadge;
