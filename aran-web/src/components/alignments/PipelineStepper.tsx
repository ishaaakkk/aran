import React from 'react';
import { Check } from 'lucide-react';
import { pipelineSteps, type PipelineInputs } from '@/lib/pipelineSteps';

/**
 * The integration pipeline as the page's spine: enroll → align → review → compile →
 * promote. The five stages always existed as equal-weight panels with no narration of
 * where the user stands (redesign brief, alignments #1). The state is the server's
 * (`lib/pipelineSteps`, J4-F5), so it survives a reload and agrees with the dashboard.
 */

export interface StepperInputs extends PipelineInputs {
  /** Click a step to jump to its section on the page. */
  onStepClick?: (index: number) => void;
}

export const PipelineStepper: React.FC<StepperInputs> = (props) => {
  const steps = pipelineSteps(props);
  return (
    <div
      data-testid="align-stepper"
      className="bg-card border border-border rounded-lg px-5 py-3 flex items-center shrink-0"
    >
      {steps.map((step, i) => (
        <React.Fragment key={step.label}>
          {i > 0 && (
            <div
              className={`flex-1 h-[2px] mx-3 ${
                step.state === 'todo' && steps[i - 1].state === 'todo'
                  ? 'bg-border'
                  : steps[i - 1].state === 'done'
                    ? 'bg-success/40'
                    : 'bg-border'
              }`}
            />
          )}
          <button
            type="button"
            onClick={() => props.onStepClick?.(i)}
            title={`Jump to ${step.label}`}
            aria-current={step.state === 'current' ? 'step' : undefined}
            className={`flex items-center gap-2.5 shrink-0 bg-transparent border-0 p-0 text-left ${
              props.onStepClick ? 'cursor-pointer' : 'cursor-default'
            } ${
              step.state === 'current'
                ? 'bg-primary/10 border border-primary/40 rounded-md px-3 py-1.5 -my-1.5'
                : ''
            }`}
          >
            <div
              className={`w-6 h-6 rounded-full flex items-center justify-center text-[12px] font-bold ${
                step.state === 'done'
                  ? 'bg-success/10 border border-success/50 text-success'
                  : step.state === 'current'
                    ? 'bg-primary text-primary-foreground'
                    : 'bg-muted border border-input text-muted-foreground'
              }`}
            >
              {step.state === 'done' ? <Check size={12} strokeWidth={3} /> : i + 1}
            </div>
            <div className="flex flex-col leading-tight">
              <span
                className={`text-[13px] font-semibold ${
                  step.state === 'current'
                    ? 'text-primary'
                    : step.state === 'done'
                      ? 'text-foreground'
                      : 'text-muted-foreground'
                }`}
              >
                {step.label}
              </span>
              <span className="text-[12px] text-muted-foreground">{step.sub}</span>
            </div>
          </button>
        </React.Fragment>
      ))}
    </div>
  );
};

export default PipelineStepper;
