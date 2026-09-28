import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Check } from 'lucide-react';
import type { Orientation } from '@/lib/orientation';

const primaryButton =
  'self-start h-8 px-4 rounded-md bg-primary text-primary-foreground text-[12.5px] font-semibold hover:bg-primary/90 transition-colors cursor-pointer focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary';

/**
 * The dashboard's first-run orientation (UI audit J1-F2, J1-F3): it starts at what an empty
 * tenant can do, ticks each step off a real count, carries exactly one primary action, and stays
 * until a schema is promoted. The steps themselves are decided in `lib/orientation`.
 */
export const GettingStarted: React.FC<{ guide: Orientation }> = ({ guide }) => {
  const navigate = useNavigate();
  if (!guide.show) return null;

  return (
    <section
      className="bg-card border border-border rounded-lg overflow-hidden shrink-0"
      data-testid="dashboard-getting-started"
      aria-labelledby="getting-started-title"
    >
      <div className="h-9 flex items-center justify-between px-4 border-b border-border bg-muted/30">
        <h2 id="getting-started-title" className="text-[13px] font-semibold text-foreground select-none">
          {guide.steps.length > 0 ? 'Set up your workspace' : 'Your workspace'}
        </h2>
        {guide.steps.length > 0 && (
          <span className="text-[12px] text-muted-foreground">
            step {guide.current + 1} of {guide.steps.length}
          </span>
        )}
      </div>

      {guide.steps.length > 0 ? (
        <ol className="grid grid-cols-1 md:grid-cols-3 xl:grid-cols-6 gap-3 p-4">
          {guide.steps.map((step, i) => {
            const current = i === guide.current;
            return (
              <li
                key={step.key}
                aria-current={current ? 'step' : undefined}
                className={`rounded-lg p-3 flex flex-col gap-2 border ${
                  current ? 'border-primary/50 bg-primary/5' : 'border-border bg-muted/10'
                }`}
              >
                <div className="flex items-center gap-2">
                  <span
                    aria-hidden="true"
                    className={`w-6 h-6 rounded-full font-mono text-[11px] font-bold flex items-center justify-center shrink-0 ${
                      step.done
                        ? 'bg-success text-white'
                        : current
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted border border-input text-muted-foreground'
                    }`}
                  >
                    {step.done ? <Check size={12} /> : i + 1}
                  </span>
                  <span className={`text-[13px] font-semibold ${current ? 'text-primary' : 'text-foreground'}`}>
                    {step.title}
                  </span>
                  {step.done && <span className="sr-only">(done)</span>}
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">{step.detail}</p>
                {current ? (
                  <button
                    type="button"
                    onClick={() => navigate(step.link.to, { state: step.link.state })}
                    className={primaryButton}
                  >
                    {step.link.label}
                  </button>
                ) : (
                  <Link
                    to={step.link.to}
                    state={step.link.state}
                    className="self-start text-[12px] text-primary underline underline-offset-2"
                  >
                    {step.done ? 'Review' : step.link.label}
                  </Link>
                )}
              </li>
            );
          })}
        </ol>
      ) : (
        <div className="flex flex-wrap items-center gap-4 p-4">
          {guide.primary && (
            <button type="button" onClick={() => navigate(guide.primary!.to)} className={primaryButton}>
              {guide.primary.label}
            </button>
          )}
          {guide.note && <p className="text-xs text-muted-foreground max-w-xl">{guide.note}</p>}
        </div>
      )}
    </section>
  );
};

export default GettingStarted;
