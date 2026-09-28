import type { QueryRunResult } from '../services/types';

export type QueryLang = 'oql' | 'sql';

/**
 * The run whose result is on screen.
 *
 * Every action on a result — the pager, page CSV, export-all, save as data product, the chart —
 * reads this, never the editor (rule R3 of the 2026-09-24 UI audit). The editor is where the
 * NEXT question is typed; the result belongs to the question that produced it. J5-F1 was the
 * difference: an analyst edited the WHERE, clicked "next →", and got rows 1,001–2,000 of a query
 * that had never been run from row 1.
 */
export interface LastRun {
  text: string;
  lang: QueryLang;
  /** The ACTIVE schema version the run executed against, when the catalog knew it. */
  schemaVersion: number | null;
  result: QueryRunResult;
}

export type RunAction = 'run' | 'page' | 'export' | 'save';

/**
 * The statement an action executes.
 *
 * `run` executes the editor — the text is the query of record (R1). Every other action executes
 * the run on screen, and has nothing to execute when no run is on screen. This is the whole
 * binding rule in one place, so the view's handlers cannot each re-decide it.
 */
export function statementFor(
  action: RunAction,
  editor: { text: string; lang: QueryLang },
  lastRun: LastRun | null,
): { text: string; lang: QueryLang } | null {
  if (action === 'run') return { text: editor.text, lang: editor.lang };
  return lastRun ? { text: lastRun.text, lang: lastRun.lang } : null;
}

/**
 * Whether the editor no longer holds the query that produced the result on screen.
 *
 * Whitespace at either end is not a change — a trailing newline typed before re-reading a
 * result must not flag it stale. Anything else is: a result pane cannot know which edits are
 * cosmetic, and saying "changed" when it did not matter costs a glance, while saying nothing
 * when it did is J5-F1.
 */
export function editorDiverged(lastRun: LastRun | null, text: string, lang: QueryLang): boolean {
  if (!lastRun) return false;
  return lastRun.lang !== lang || lastRun.text.trim() !== text.trim();
}

/** What the result pane is showing — carried as `data-state` so a test reads the lifecycle, not copy. */
export type ResultState = 'idle' | 'running' | 'ok' | 'error' | 'stale' | 'stopped';

export function resultState(s: {
  running: boolean;
  stopped: boolean;
  error: string | null;
  hasResult: boolean;
  /** {@link editorDiverged} for the run on screen. */
  stale: boolean;
}): ResultState {
  if (s.running) return 'running';
  if (s.error) return 'error';
  if (s.stopped) return 'stopped';
  if (!s.hasResult) return 'idle';
  return s.stale ? 'stale' : 'ok';
}

export type ExportFormat = 'csv' | 'parquet';

/**
 * The row cap a query sets with its own trailing `LIMIT`, or null.
 *
 * A hint, not a parse — there is no TypeScript OQL parser (D6): it reads only a final
 * `LIMIT <n>`, which is the one place the grammar allows it, so a "LIMIT 5" inside a string
 * literal (followed by its closing quote) is not mistaken for one. Export and save honour the
 * query's LIMIT, and the builder appends one, which is how "every row" silently became 100 (J5-F14).
 */
export function limitOf(text: string): number | null {
  const m = /\bLIMIT\s+(\d+)\s*;?\s*$/i.exec(text);
  return m ? Number(m[1]) : null;
}

/**
 * What a finished export says: how many rows the file holds (the server's `X-Aran-Row-Count`),
 * and — only when it actually cut the file — that the query's own LIMIT did.
 *
 * `rowCount` is null when the response carried no count (a proxy stripped the header, or an older
 * backend never sent it): the notice then says the file downloaded and states no number, rather
 * than "0 rows" for a file that may hold millions.
 */
export function exportNotice(rowCount: number | null, format: ExportFormat, limit: number | null): string {
  const as = `as ${format === 'csv' ? 'CSV' : 'Parquet'}`;
  if (rowCount === null) {
    const base = `Downloaded the file ${as}; the server did not report its row count.`;
    return limit !== null
      ? `${base} Your query ends in LIMIT ${limit}, so it holds at most ${limit.toLocaleString('en-US')} rows.`
      : base;
  }
  const rows = `${rowCount.toLocaleString('en-US')} row${rowCount === 1 ? '' : 's'}`;
  const base = `Downloaded ${rows} ${as}.`;
  return limit !== null && rowCount >= limit
    ? `${base} Your query's LIMIT ${limit} applied — remove it to export every row.`
    : base;
}
