import React from 'react';

export interface LogRow {
  time: string;
  lvl: string;
  src: string;
  msg: string;
}

interface TailLogProps {
  rows: LogRow[];
  /** What to say with no rows. The default claims the log IS empty — pass the loading or failed
   *  state instead whenever the rows have not been read (J1-F7, FRONTEND-6). */
  emptyMessage?: React.ReactNode;
}

const levelColor: Record<string, string> = {
  INFO: 'text-emerald-400',
  WARN: 'text-amber-400',
  ERROR: 'text-rose-400',
  DEBUG: 'text-sky-400',
  TRACE: 'text-neutral-400',
};

export const TailLog: React.FC<TailLogProps> = ({ rows, emptyMessage = 'No log entries.' }) => {
  return (
    <div className="overflow-x-auto">
      {rows.length === 0 ? (
        <div className="py-6 text-center text-[10.5px] font-mono text-muted-foreground">
          {emptyMessage}
        </div>
      ) : (
        <div className="flex flex-col">
          {rows.map((row, idx) => (
            <div
              key={idx}
              className="flex items-baseline gap-2 px-3 py-[2px] font-mono leading-snug hover:bg-muted/20 transition-colors"
              style={{ fontSize: '10.5px' }}
            >
              <span className="text-muted-foreground shrink-0 tabular-nums">
                {row.time}
              </span>
              <span
                className={`shrink-0 font-semibold uppercase w-[42px] text-right ${
                  levelColor[row.lvl] ?? 'text-muted-foreground'
                }`}
              >
                {row.lvl}
              </span>
              <span className="shrink-0 text-primary">
                {row.src}
              </span>
              <span className="text-foreground truncate">
                {row.msg}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default TailLog;
