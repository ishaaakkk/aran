import React from 'react';
import { InfoTip } from './InfoTip';

export interface Column {
  key: string;
  label: string;
  width?: string;
  mono?: boolean;
  num?: boolean;
  align?: 'left' | 'right';
}

/** How the table reads a row: by column key, whatever the caller's row type is. */
type RowIndex = Record<string, unknown>;

interface DataTableProps<R> {
  command: string;
  /** Concept explanation rendered as an `InfoTip` after the header — pass a
   *  `CONCEPTS` entry when the table's subject is jargon (see `lib/concepts.ts`). */
  info?: string;
  columns: Column[];
  rows: R[];
  onRowClick?: (row: R) => void;
  emptyMessage?: React.ReactNode;
  renderCell?: Record<string, (row: R) => React.ReactNode>;
  /** Optional `data-testid` for each row's `<tr>`, e.g. `(row) => `repos-row-${row.name}``. */
  getRowTestId?: (row: R) => string;
}

/** Render a cell the caller has no custom renderer for. */
function defaultCell(value: unknown): React.ReactNode {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

export function DataTable<R>({
  command,
  info,
  columns,
  rows,
  onRowClick,
  emptyMessage = 'No data available.',
  renderCell,
  getRowTestId,
}: DataTableProps<R>) {
  return (
    <div className="bg-card border border-border rounded overflow-hidden">
      {/* Terminal prompt header */}
      <div className="h-9 flex items-center px-4 bg-muted/30 border-b border-border">
        <span className="text-[11px] font-mono text-muted-foreground select-none inline-flex items-center gap-1.5">
          <span><span className="text-primary">$</span> {command}</span>
          {info && <InfoTip text={info} label={command} />}
        </span>
      </div>

      {rows.length === 0 ? (
        <div className="flex items-center justify-center py-16 px-4">
          {emptyMessage}
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-border">
                {columns.map((col) => (
                  <th
                    key={col.key}
                    className={`px-4 py-2.5 font-mono text-[9px] uppercase tracking-wider text-muted-foreground font-semibold whitespace-nowrap ${
                      col.align === 'right' ? 'text-right' : 'text-left'
                    }`}
                    style={col.width ? { width: col.width } : undefined}
                  >
                    {col.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, rowIdx) => {
                const cells = row as RowIndex;
                return (
                <tr
                  key={typeof cells.id === 'string' ? cells.id : rowIdx}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  data-testid={getRowTestId?.(row)}
                  className={`border-b border-border/50 last:border-b-0 transition-colors ${
                    onRowClick
                      ? 'cursor-pointer hover:bg-muted/50'
                      : 'hover:bg-muted/30'
                  }`}
                >
                  {columns.map((col) => (
                    <td
                      key={col.key}
                      className={`px-4 py-2.5 whitespace-nowrap ${
                        col.mono ? 'font-mono' : ''
                      } ${col.num ? 'tabular-nums' : ''} ${
                        col.align === 'right' ? 'text-right' : 'text-left'
                      }`}
                    >
                      {renderCell?.[col.key]
                        ? renderCell[col.key](row)
                        : defaultCell(cells[col.key])}
                    </td>
                  ))}
                </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default DataTable;
