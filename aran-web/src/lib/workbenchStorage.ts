const HISTORY_KEY = 'aran:qhist';
const WORKSPACE_KEY = 'aran:ws';

export interface HistoryEntry {
  query: string;
  ts: number;
  label: string;
}

export interface WorkspaceState {
  query: string;
  layout: string;
  queryLimit: number;
}

function firstLine(q: string): string {
  const line = q.split('\n')[0].trim();
  return line.length > 72 ? line.slice(0, 69) + '…' : line;
}

function encodeQuery(q: string): string {
  try {
    const bytes = new TextEncoder().encode(q);
    const binary = Array.from(bytes, b => String.fromCharCode(b)).join('');
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  } catch { return ''; }
}

function decodeQuery(s: string): string {
  try {
    const pad = s.length % 4 ? s + '='.repeat(4 - s.length % 4) : s;
    const binary = atob(pad.replace(/-/g, '+').replace(/_/g, '/'));
    const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
    return new TextDecoder().decode(bytes);
  } catch { return ''; }
}

export function saveHistory(query: string): void {
  if (!query.trim()) return;
  try {
    const prev = loadHistory();
    const deduped = prev.filter(e => e.query !== query);
    const next: HistoryEntry[] = [{ query, ts: Date.now(), label: firstLine(query) }, ...deduped].slice(0, 20);
    localStorage.setItem(HISTORY_KEY, JSON.stringify(next));
  } catch {
    // localStorage is unavailable or full; history is a convenience, not state we owe the user.
  }
}

export function loadHistory(): HistoryEntry[] {
  try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]') as HistoryEntry[]; }
  catch { return []; }
}

/**
 * Whether putting `incoming` into the editor would lose text the user wrote — the condition for
 * offering Undo on a gesture that replaces the editor (rule R2). An empty editor, or one already
 * holding `incoming`, has nothing to lose, and an Undo there would only restore the same text.
 */
export function replacesDraft(editorText: string, incoming: string): boolean {
  return editorText.trim() !== '' && editorText.trim() !== incoming.trim();
}

export function saveWorkspace(state: WorkspaceState): void {
  try { localStorage.setItem(WORKSPACE_KEY, JSON.stringify(state)); }
  catch {
    // localStorage is unavailable or full; the workspace is restored best-effort.
  }
}

export function loadWorkspace(): WorkspaceState | null {
  try {
    const raw = localStorage.getItem(WORKSPACE_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as WorkspaceState;
  } catch { return null; }
}

export function getUrlQuery(): string | null {
  try {
    const q = new URLSearchParams(window.location.search).get('q');
    if (!q) return null;
    const decoded = decodeQuery(q);
    return decoded || null;
  } catch { return null; }
}

/**
 * What the editor opens with, given a shared link's query and the saved draft (J5-F7).
 *
 * A shared link used to win outright on every load: reloading after twenty minutes of edits put
 * the shared text back, and the debounced save then overwrote the draft with it. Now a draft that
 * differs stays in the editor and the shared query waits for the user's choice (`pendingShared`).
 */
export function initialEditorText(
  shared: string | null,
  draft: string | null | undefined,
): { text: string; pendingShared: string | null } {
  const d = draft ?? '';
  if (shared === null) return { text: d, pendingShared: null };
  if (d.trim() === '' || d.trim() === shared.trim()) return { text: d.trim() === '' ? shared : d, pendingShared: null };
  return { text: d, pendingShared: shared };
}

/**
 * Drop `?q=` from the address once it has been read, without a navigation, so a reload does not
 * apply the shared query again (J5-F7). The rest of the URL — path, other parameters — is kept.
 */
export function clearShareParam(): void {
  try {
    const url = new URL(window.location.href);
    if (!url.searchParams.has('q')) return;
    url.searchParams.delete('q');
    window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash);
  } catch {
    // No history API (an embedded or test context): the link simply stays in the address.
  }
}

export function buildShareUrl(query: string): string {
  const url = new URL(window.location.href);
  url.search = '';
  url.searchParams.set('q', encodeQuery(query));
  return url.toString();
}

export function savePositions(key: string, pos: Record<string, { x: number; y: number }>): void {
  try { localStorage.setItem('aran:pos:' + key, JSON.stringify(pos)); } catch {
    // localStorage is unavailable or full; node positions fall back to the computed layout.
  }
}

export function loadPositions(key: string): Record<string, { x: number; y: number }> | null {
  try {
    const raw = localStorage.getItem('aran:pos:' + key);
    return raw ? JSON.parse(raw) as Record<string, { x: number; y: number }> : null;
  } catch { return null; }
}

export function relTime(ts: number): string {
  const d = Date.now() - ts;
  if (d < 60_000) return 'just now';
  if (d < 3_600_000) return `${Math.floor(d / 60_000)}m ago`;
  if (d < 86_400_000) return `${Math.floor(d / 3_600_000)}h ago`;
  return `${Math.floor(d / 86_400_000)}d ago`;
}


/**
 * Map a persisted workspace layout onto one the workbench still has.
 *
 * The page used to offer three: `split`, `query` and `canvas`. The first two were the same
 * panels re-proportioned and both ran the graph in explore mode, so they merged into one
 * `explore` layout with a collapsible graph — which is what query-first was really for. A
 * workspace saved before that holds a value the view no longer knows, and an unrecognised
 * layout must not leave the page rendering nothing, so anything unknown lands on `explore`
 * too.
 */
export function migrateLayout(stored: string | undefined): 'explore' | 'canvas' {
  return stored === 'canvas' ? 'canvas' : 'explore';
}
