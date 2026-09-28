import React from 'react';
import { Loader2, ShieldAlert } from 'lucide-react';
import { TerminalCard } from '@/components/TerminalCard';
import type { DatasetPreview } from '@/services/types';

interface DatasetPreviewTabProps {
  datasetName: string;
  loading: boolean;
  data: DatasetPreview | null;
  error: string | null;
  onRetry: () => void;
}

/** Data preview tab: a records table, or a retry card when the source failed. */
export const DatasetPreviewTab: React.FC<DatasetPreviewTabProps> = ({
  datasetName,
  loading,
  data,
  error,
  onRetry,
}) => {
  return (
    <div className="flex-1 flex flex-col min-h-0 overflow-auto">
      {loading ? (
        <div className="flex flex-col items-center justify-center py-20 gap-3 text-muted-foreground text-xs font-mono flex-grow">
          <Loader2 size={24} className="animate-spin text-primary" />
          Compiling integration catalog and retrieving records preview...
        </div>
      ) : data ? (
        <div className="overflow-auto border border-border rounded flex-1">
          <table data-testid="dataset-preview-table" className="w-full text-xs font-mono">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                {data.headers.map((h, i) => (
                  <th
                    key={i}
                    className="px-4 py-2.5 text-left text-[9px] uppercase tracking-wider font-semibold text-muted-foreground border-r border-border last:border-r-0"
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.rows.length === 0 ? (
                <tr>
                  <td
                    colSpan={data.headers.length}
                    className="px-4 py-8 text-center text-muted-foreground italic border-r border-border last:border-r-0"
                  >
                    No records returned from query check.
                  </td>
                </tr>
              ) : (
                data.rows.map((row, rowIdx) => (
                  <tr
                    key={rowIdx}
                    className="border-b border-border/50 last:border-b-0 hover:bg-muted/10 transition-colors"
                  >
                    {row.map((val, colIdx) => (
                      <td
                        key={colIdx}
                        className="px-4 py-2.5 max-w-[200px] truncate text-foreground border-r border-border/50 last:border-r-0 text-[11px]"
                      >
                        {val === '' ? (
                          <span className="text-emerald-500/35 italic font-medium">[blank]</span>
                        ) : (
                          String(val)
                        )}
                      </td>
                    ))}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      ) : (
        <TerminalCard command={`Preview — ${datasetName}`}>
          <div className="space-y-3 font-mono text-xs">
            <div className="flex items-center gap-2 text-red-400">
              <ShieldAlert size={14} />
              <span className="uppercase tracking-wider font-semibold">Preview failed</span>
            </div>
            <p className="text-muted-foreground break-words select-text whitespace-pre-wrap">
              {error ?? 'The source did not return a preview.'}
            </p>
            <button
              type="button"
              onClick={onRetry}
              className="px-3 py-1.5 rounded border border-border text-foreground hover:border-foreground transition-colors uppercase tracking-wider text-[10px]"
            >
              Retry
            </button>
          </div>
        </TerminalCard>
      )}
    </div>
  );
};

export default DatasetPreviewTab;
