import { useMemo, useState } from 'react';
import { QueryRunResult } from '../services/types';
import { compareCells } from '@/lib/compareCells';
import './resultTable.css';

/**
 * The sortable result table, extracted verbatim from the workbench's ResultPanel
 * (M8) so the data-product preview renders rows exactly the way the workbench does.
 * Styling comes from its own resultTable.css, so it renders the same in any host.
 */
export function ResultTable({ result: r }: { result: QueryRunResult }) {
  const [sortCol, setSortCol] = useState<number | null>(null);
  const [sortAsc, setSortAsc] = useState(true);

  // Reset sort when the result identity changes. Adjusted during render (the
  // documented alternative to an effect for "reset state when a prop changes")
  // rather than in a useEffect, so there is no stale-sorted-rows flash on the
  // render in between.
  const [prevResult, setPrevResult] = useState(r);
  if (r !== prevResult) {
    setPrevResult(r);
    setSortCol(null);
    setSortAsc(true);
  }

  function handleSort(i: number) {
    if (sortCol === i) setSortAsc(a => !a);
    else { setSortCol(i); setSortAsc(true); }
  }

  const sortedRows = useMemo(() => {
    if (!r) return [];
    if (sortCol === null) return r.rows;
    return [...r.rows].sort((a, b) => {
      const cmp = compareCells(a[sortCol], b[sortCol]);
      return sortAsc ? cmp : -cmp;
    });
  }, [r, sortCol, sortAsc]);

  return (
    <table className="rtable" data-testid="oql-results">
      <thead>
        <tr>{r.headerNames.map((col, i) => (
          <th key={i} className="th-sort" onClick={() => handleSort(i)}>
            {col}
            <span className="th-sort-icon">{sortCol === i ? (sortAsc ? '↑' : '↓') : '⇅'}</span>
          </th>
        ))}</tr>
      </thead>
      <tbody>
        {sortedRows.map((row, ri) => (
          <tr key={ri}>
            {row.map((cell, ci) => (
              <td key={ci} className={cell === null ? 'cell-null' : undefined}>
                {cell === null ? '∅' : cell}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export default ResultTable;
