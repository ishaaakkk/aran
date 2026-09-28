import React from 'react';
import { InfoTip } from './InfoTip';

interface CmdHeaderProps {
  /** The panel title, e.g. "Projects". (Historically a $-prefixed command string.) */
  command: string;
  /** Concept explanation rendered as an `InfoTip` after the title — pass a
   *  `CONCEPTS` entry when the panel's name is jargon (see `lib/concepts.ts`). */
  info?: string;
  /** Right-side slot — counts, live dot, refresh button, etc. */
  right?: React.ReactNode;
  /** Visual variant. `flat` removes the muted background tint. */
  variant?: 'panel' | 'flat';
  className?: string;
}

/**
 * The canonical `$ aran … ls` header used at the top of every table and
 * terminal-style panel. Previously inlined separately in DataTable and
 * TerminalCard — extracted here so spacing and typography stay locked.
 *
 *   <CmdHeader command="Projects" right={<span>6 workspaces</span>} />
 */
export const CmdHeader: React.FC<CmdHeaderProps> = ({
  command,
  info,
  right,
  variant = 'panel',
  className = '',
}) => {
  return (
    <div
      className={`h-9 flex items-center justify-between gap-3 px-4 border-b border-border ${
        variant === 'panel' ? 'bg-muted/30' : ''
      } ${className}`}
    >
      {/* Panel title, not a terminal prompt: call sites pass human titles now. */}
      <span className="text-[13px] font-semibold text-foreground select-none truncate inline-flex items-center gap-1.5">
        {command}
        {info && <InfoTip text={info} label={command} />}
      </span>
      {right && (
        <div className="flex items-center gap-3 text-[10px] font-mono text-muted-foreground shrink-0">
          {right}
        </div>
      )}
    </div>
  );
};

export default CmdHeader;
