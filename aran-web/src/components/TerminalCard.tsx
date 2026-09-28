import React from 'react';

interface TerminalCardProps {
  command: string;
  live?: boolean;
  children: React.ReactNode;
}

export const TerminalCard: React.FC<TerminalCardProps> = ({
  command,
  live = false,
  children,
}) => {
  return (
    <div className="bg-card border border-border rounded overflow-hidden">
      {/* Terminal prompt header */}
      <div className="h-9 flex items-center justify-between px-4 bg-muted/30 border-b border-border">
        <span className="text-[11px] font-mono text-muted-foreground select-none">
          <span className="text-primary">$</span> {command}
        </span>

        {live && (
          <span className="inline-flex items-center gap-1.5">
            <span
              className="w-1.5 h-1.5 rounded-full bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.4)]"
              style={{ animation: 'pulse-dot 2s ease-in-out infinite' }}
            />
            <span className="text-[9px] font-mono uppercase tracking-wider text-emerald-400 font-semibold select-none">
              live
            </span>
          </span>
        )}
      </div>

      {/* Card body */}
      <div className="p-4">{children}</div>
    </div>
  );
};

export default TerminalCard;
