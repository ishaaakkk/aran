import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * The docs walkthrough and the seminar fixtures it uploads, read as data by
 * `t1-walkthrough.spec.ts`.
 *
 * The spec performs the page rather than a copy of it: the OQL it runs, the
 * alignments it adds and the constraints it enforces are the page's own code
 * blocks and tables, so an edit to the page is an edit to the test. That is the
 * point — the walkthrough sat broken for weeks (S4-2 refused its JSON fixture;
 * its join label collided with its relationship name; its wrappers renamed the
 * columns the integrated views select) because nothing executed it.
 *
 * The expected rows are the other half, and they come from the fixture files,
 * never from the page's prose: a claim the page makes is checked against the
 * files, not trusted.
 */

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');

export const WALKTHROUGH_PATH = path.join(REPO, 'docs-site/docs/getting-started/walkthrough.md');
export const SEMINAR_DIR = path.join(REPO, 'demo/seminar');

export function seminarPath(file: string): string {
  return path.join(SEMINAR_DIR, file);
}

// ── the page ────────────────────────────────────────────────────────────────

export interface DocSection {
  /** Heading text without the leading hashes, e.g. `Step 5 — Align the schemas`. */
  heading: string;
  level: number;
  /** Everything up to the next heading of the same or a higher level. */
  body: string;
}

/** Every `##`/`###` section of the walkthrough, in page order. Fenced code is skipped when
 *  looking for headings, so a `#` inside a code block never splits a section. */
export function walkthroughSections(): DocSection[] {
  const lines = readFileSync(WALKTHROUGH_PATH, 'utf8').split('\n');
  const heads: Array<{ line: number; level: number; heading: string }> = [];
  let inFence = false;
  lines.forEach((line, i) => {
    if (line.startsWith('```')) inFence = !inFence;
    const m = !inFence && /^(#{2,3}) (.+)$/.exec(line);
    if (m) heads.push({ line: i, level: m[1].length, heading: m[2].trim() });
  });
  return heads.map((h, idx) => {
    const next = heads.slice(idx + 1).find((n) => n.level <= h.level);
    return {
      heading: h.heading,
      level: h.level,
      body: lines.slice(h.line + 1, next ? next.line : lines.length).join('\n'),
    };
  });
}

/** The section whose heading is exactly `heading`; throws naming the headings that do exist. */
export function section(heading: string): DocSection {
  const all = walkthroughSections();
  const found = all.find((s) => s.heading === heading);
  if (!found) {
    throw new Error(`walkthrough has no heading "${heading}"; it has: ${all.map((s) => s.heading).join(' / ')}`);
  }
  return found;
}

/** The contents of each fenced code block in `body`, in order, trimmed. */
export function fencedBlocks(body: string): string[] {
  return [...body.matchAll(/^```[^\n]*\n([\s\S]*?)^```$/gm)].map((m) => m[1].trim());
}

/** Non-blank lines of a code block — one constraint expression per line. */
export function blockLines(block: string): string[] {
  return block.split('\n').map((l) => l.trim()).filter((l) => l.length > 0);
}

/**
 * The first markdown table in `body`: its header row and each data row, as the backticked
 * tokens they contain, in order. Backticks are the page's own markup for "type exactly this",
 * so they are what a reader copies and what the spec types.
 */
export function backtickTable(body: string): { header: string[]; rows: string[][] } {
  const tableLines = body.split('\n').filter((l) => l.trim().startsWith('|'));
  if (tableLines.length < 3) {
    throw new Error(`expected a markdown table (header, separator, rows); found ${tableLines.length} table lines`);
  }
  const ticks = (l: string) => [...l.matchAll(/`([^`]+)`/g)].map((m) => m[1]);
  return { header: ticks(tableLines[0]), rows: tableLines.slice(2).map(ticks) };
}

/** Whitespace-collapsed prose, so a claim wrapped across lines can be searched for. */
export function prose(body: string): string {
  return body.replace(/\s+/g, ' ');
}

// ── the fixtures ────────────────────────────────────────────────────────────

export interface PaperAuthorRow {
  source: 'acm' | 'semantic';
  pid: string;
  title: string;
  year: number;
  venue: string | null;
  citationCount: number | null;
  authorId: string;
}

export interface Researcher {
  id: string;
  fullName: string;
  affiliation: string;
  country: string;
  hIndex: number;
}

/**
 * One row per (paper, author) across both paper sources — the grain IPaper has once the
 * Semantic Scholar wrapper has unnested `authors`.
 */
export function seminarPaperAuthorRows(): PaperAuthorRow[] {
  const [header, ...lines] = readFileSync(seminarPath('acm_papers.csv'), 'utf8').trim().split('\n');
  const cols = header.split(',');
  const acm = lines.map((line) => {
    // The file has no quoted fields; a comma inside a title would silently shift every column
    // after it, so refuse rather than guess.
    const cells = line.split(',');
    if (cells.length !== cols.length) {
      throw new Error(`acm_papers.csv: expected ${cols.length} fields, got ${cells.length}: ${line}`);
    }
    const r = Object.fromEntries(cols.map((c, i) => [c, cells[i]]));
    return {
      source: 'acm' as const,
      pid: r.paper_id,
      title: r.title,
      year: Number(r.year),
      venue: r.venue,
      citationCount: null,
      authorId: r.author_id,
    };
  });

  const semantic = (JSON.parse(readFileSync(seminarPath('semantic_papers.json'), 'utf8')) as Array<{
    doi: string; title: string; year: number; authors: string[]; citation_count: number;
  }>).flatMap((p) =>
    p.authors.map((a) => ({
      source: 'semantic' as const,
      pid: p.doi,
      title: p.title,
      year: p.year,
      venue: null,
      citationCount: p.citation_count,
      authorId: a,
    })),
  );
  return [...acm, ...semantic];
}

export function seminarResearchers(): Map<string, Researcher> {
  const rows = JSON.parse(readFileSync(seminarPath('researchers.json'), 'utf8')) as Array<{
    researcher_id: string; full_name: string; affiliation: string; country: string; h_index: number;
  }>;
  return new Map(rows.map((r) => [r.researcher_id, {
    id: r.researcher_id,
    fullName: r.full_name,
    affiliation: r.affiliation,
    country: r.country,
    hIndex: r.h_index,
  }]));
}
