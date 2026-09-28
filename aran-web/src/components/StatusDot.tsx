import React from 'react';

interface StatusDotProps {
  variant?: 'ok' | 'warn' | 'err' | 'idle' | 'accent';
  label?: string;
  /** Opt-in blinking — reserve for genuinely live indicators. */
  blink?: boolean;
  /** Hide the label, show only the dot. */
  iconOnly?: boolean;
}

/**
 * Status indicator. Pulsing is an explicit `blink` prop rather than always-on, so non-live
 * states (a project's `draft` row, a closed alert) sit still and the actually-live
 * tail/heartbeat reads as live.
 */
export const StatusDot: React.FC<StatusDotProps> = ({
  variant = 'ok',
  label = '',
  blink = false,
  iconOnly = false,
}) => {
  const dotColorClass = {
    ok:     'bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.35)]',
    warn:   'bg-amber-500   shadow-[0_0_8px_rgba(245,158,11,0.35)]',
    err:    'bg-rose-500    shadow-[0_0_8px_rgba(244,63,94,0.35)]',
    idle:   'bg-neutral-500',
    accent: 'bg-violet-500  shadow-[0_0_8px_rgba(139,92,246,0.35)]',
  }[variant];

  const labelColorClass = {
    ok:     'text-emerald-400',
    warn:   'text-amber-400',
    err:    'text-rose-400',
    idle:   'text-muted-foreground',
    accent: 'text-violet-400',
  }[variant];

  return (
    <span className="inline-flex items-center gap-2 font-mono text-[10.5px] select-none">
      <span
        className={`w-1.5 h-1.5 rounded-full shrink-0 ${dotColorClass}`}
        style={blink ? { animation: 'pulse-dot 1.6s ease-in-out infinite' } : undefined}
      />
      {!iconOnly && label && (
        <span className={`uppercase tracking-wider font-semibold ${labelColorClass}`}>
          {label}
        </span>
      )}
    </span>
  );
};

export default StatusDot;
