import React from 'react';

export interface StatTile {
  key: string;
  label: string;
  value: number | string;
  /** Small contextual line under the value, e.g. "12 bootstrapped". */
  delta?: string;
  /** Token from `--color-*` — typically a status color. Default: primary. */
  tone?: string;
  /** Optional leading icon node (from lucide-react). */
  icon?: React.ReactNode;
}

interface StatStripProps {
  tiles: StatTile[];
  /** Number of columns on `lg` breakpoint. Default 4. */
  columns?: number;
}

/**
 * Dashboard hero strip — a row of stat tiles separated by 1px gutters that
 * read as a single bordered slab. Value uses mono+tabular figures.
 *
 * There is no trend sparkline: Aran retains no time series for these counts,
 * and a trend line has to come from somewhere real or not be drawn at all.
 */
export const StatStrip: React.FC<StatStripProps> = ({ tiles, columns = 4 }) => {
  const gridClass =
    columns === 4
      ? 'grid-cols-1 sm:grid-cols-2 lg:grid-cols-4'
      : columns === 3
        ? 'grid-cols-1 sm:grid-cols-3'
        : 'grid-cols-1 sm:grid-cols-2';

  return (
    <div
      className={`grid ${gridClass} gap-[1px] bg-border border border-border rounded overflow-hidden shrink-0`}
    >
      {tiles.map((t) => {
        const tone = t.tone ?? 'var(--primary)';
        return (
          <div
            key={t.key}
            className="bg-card p-4 flex flex-col gap-2.5 justify-between min-h-[96px]"
          >
            <div className="flex items-center justify-between text-muted-foreground">
              <span className="font-mono text-[9px] uppercase tracking-widest font-bold text-muted-foreground/70 select-none">
                {t.label}
              </span>
              {t.icon ?? (
                <span
                  className="w-1.5 h-1.5 rounded-full"
                  style={{ backgroundColor: tone }}
                />
              )}
            </div>

            <div className="flex items-baseline justify-between gap-3">
              <span className="font-mono text-3xl font-medium tracking-tight tabular-nums text-foreground select-none leading-none">
                {t.value}
              </span>
            </div>

            {t.delta && (
              <span className="font-mono text-[10px] text-muted-foreground select-none">
                {t.delta}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
};

export default StatStrip;
