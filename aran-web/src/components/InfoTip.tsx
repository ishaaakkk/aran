import React, { useId, useState } from 'react';
import { Info } from 'lucide-react';

interface InfoTipProps {
  /** The explanation shown in the popover — usually a `CONCEPTS` entry. */
  text: string;
  /** What the tip explains, for the icon's accessible name ("About federation"). */
  label: string;
  /** Which edge of the icon the popover grows from. `end` keeps it on-screen
   *  when the icon sits near the right edge of a panel. */
  align?: 'start' | 'end';
  className?: string;
}

/**
 * A small ⓘ next to a term, opening a short explanation on hover or focus.
 *
 * Exists because the app otherwise explains itself through native `title`
 * attributes, which cannot be reached from the keyboard, cannot wrap long
 * copy readably, and are invisible on touch. Concept copy lives in
 * `lib/concepts.ts`, not at call sites — see the note there.
 *
 * Hover and focus toggle a real element (not CSS-only) so Escape can dismiss
 * it and `aria-describedby` can point at it while open. Click toggles too,
 * for touch.
 */
export const InfoTip: React.FC<InfoTipProps> = ({ text, label, align = 'start', className = '' }) => {
  const [open, setOpen] = useState(false);
  const tipId = useId();

  return (
    <span
      className={`relative inline-flex ${className}`}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      <button
        type="button"
        aria-label={`About ${label}`}
        aria-expanded={open}
        aria-describedby={open ? tipId : undefined}
        data-testid="info-tip"
        onClick={() => setOpen(v => !v)}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onKeyDown={(e) => { if (e.key === 'Escape') setOpen(false); }}
        className="inline-flex items-center justify-center w-4 h-4 rounded-full border-0 bg-transparent p-0 text-muted-foreground/70 hover:text-foreground focus:outline-none focus:ring-1 focus:ring-primary/60 cursor-help shrink-0"
      >
        <Info size={12} aria-hidden="true" />
      </button>
      {open && (
        <span
          id={tipId}
          role="tooltip"
          data-testid="info-tip-popover"
          className={`absolute top-full mt-1.5 z-50 w-72 max-w-[80vw] rounded border border-border bg-card p-3 text-[11px] leading-relaxed text-muted-foreground shadow-lg text-left font-sans normal-case tracking-normal font-normal whitespace-normal ${
            align === 'end' ? 'right-0' : 'left-0'
          }`}
        >
          {text}
        </span>
      )}
    </span>
  );
};

export default InfoTip;
