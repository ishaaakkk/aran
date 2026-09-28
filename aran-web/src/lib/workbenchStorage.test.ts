import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  saveHistory, loadHistory, saveWorkspace, loadWorkspace,
  savePositions, loadPositions, buildShareUrl, getUrlQuery, relTime, migrateLayout,
  initialEditorText, clearShareParam, replacesDraft,
} from './workbenchStorage';

// Node test environment: back localStorage with a Map and window.location
// with a fixed URL, matching what the browser provides.
const store = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => { store.set(k, v); },
  removeItem: (k: string) => { store.delete(k); },
});

function stubLocation(href: string) {
  vi.stubGlobal('window', { location: new URL(href) });
}

beforeEach(() => {
  store.clear();
  stubLocation('http://localhost:5173/workbench');
});

describe('history', () => {
  it('round-trips, dedupes, and caps at 20 entries', () => {
    saveHistory('SELECT A.*');
    saveHistory('SELECT B.*');
    saveHistory('SELECT A.*'); // dedupe: moves to front
    const hist = loadHistory();
    expect(hist.map(h => h.query)).toEqual(['SELECT A.*', 'SELECT B.*']);

    for (let i = 0; i < 25; i++) saveHistory(`SELECT C${i}.*`);
    expect(loadHistory()).toHaveLength(20);
  });

  it('ignores blank queries and corrupt stored JSON', () => {
    saveHistory('   ');
    expect(loadHistory()).toEqual([]);
    store.set('aran:qhist', '{not json');
    expect(loadHistory()).toEqual([]);
  });
});

describe('workspace', () => {
  it('round-trips workspace state and returns null when absent', () => {
    expect(loadWorkspace()).toBeNull();
    saveWorkspace({ query: 'Q', layout: 'split', queryLimit: 50 });
    expect(loadWorkspace()).toEqual({ query: 'Q', layout: 'split', queryLimit: 50 });
  });
});

describe('positions', () => {
  it('round-trips per-key node positions', () => {
    expect(loadPositions('v1')).toBeNull();
    savePositions('v1', { Artist: { x: 1, y: 2 } });
    expect(loadPositions('v1')).toEqual({ Artist: { x: 1, y: 2 } });
    expect(loadPositions('v2')).toBeNull();
  });
});

describe('share URLs', () => {
  it('encodes the query into ?q= and decodes it back, unicode included', () => {
    const query = 'SELECT Artist.name -- Miró ✓';
    const url = buildShareUrl(query);
    stubLocation(url);
    expect(getUrlQuery()).toBe(query);
  });

  it('returns null when no q param is present', () => {
    expect(getUrlQuery()).toBeNull();
  });
});

describe('replacesDraft — when a text-replacing gesture offers Undo (rule R2)', () => {
  it('a draft that differs is lost unless Undo is offered — restoring history over a never-run draft', () => {
    expect(replacesDraft('SELECT B.y FROM B WHERE B.y = 1', 'SELECT A.x FROM A')).toBe(true);
  });

  it('an empty editor, or one already holding the text, loses nothing', () => {
    expect(replacesDraft('', 'SELECT A.x FROM A')).toBe(false);
    expect(replacesDraft('  \n', 'SELECT A.x FROM A')).toBe(false);
    expect(replacesDraft('SELECT A.x FROM A\n', 'SELECT A.x FROM A')).toBe(false);
  });
});

describe('opening a shared link (J5-F7)', () => {
  it('opens the shared query when there is no draft to lose', () => {
    expect(initialEditorText('SELECT A.x FROM A', null)).toEqual({ text: 'SELECT A.x FROM A', pendingShared: null });
    expect(initialEditorText('SELECT A.x FROM A', '   ')).toEqual({ text: 'SELECT A.x FROM A', pendingShared: null });
  });

  it('keeps a different draft in the editor and holds the shared query for the user to choose', () => {
    // A reload used to re-apply ?q= over twenty minutes of edits, and the debounced save then
    // overwrote the draft with it.
    expect(initialEditorText('SELECT A.x FROM A', 'SELECT B.y FROM B WHERE B.y = 1'))
      .toEqual({ text: 'SELECT B.y FROM B WHERE B.y = 1', pendingShared: 'SELECT A.x FROM A' });
  });

  it('asks nothing when the draft already is the shared query', () => {
    expect(initialEditorText('SELECT A.x FROM A', 'SELECT A.x FROM A\n'))
      .toEqual({ text: 'SELECT A.x FROM A\n', pendingShared: null });
  });

  it('restores the draft when no link was opened', () => {
    expect(initialEditorText(null, 'Q')).toEqual({ text: 'Q', pendingShared: null });
    expect(initialEditorText(null, null)).toEqual({ text: '', pendingShared: null });
  });

  it('consumes ?q= once, so a reload does not apply it again', () => {
    const calls: string[] = [];
    const url = buildShareUrl('SELECT A.x FROM A');
    vi.stubGlobal('window', {
      location: new URL(url),
      history: { state: null, replaceState: (_s: unknown, _t: string, u: string) => { calls.push(u); } },
    });
    clearShareParam();
    expect(calls).toHaveLength(1);
    expect(calls[0]).not.toContain('q=');
    expect(calls[0]).toContain('/workbench');
  });
});

describe('relTime', () => {
  it('buckets into now/minutes/hours/days', () => {
    const now = Date.now();
    expect(relTime(now - 10_000)).toBe('just now');
    expect(relTime(now - 5 * 60_000)).toBe('5m ago');
    expect(relTime(now - 3 * 3_600_000)).toBe('3h ago');
    expect(relTime(now - 2 * 86_400_000)).toBe('2d ago');
  });
});

describe('migrateLayout', () => {
  it('keeps the visual builder, which is a genuinely different mode', () => {
    // `canvas` runs the graph in mode='build', where clicking a node edits the query rather
    // than navigating it. That survived the merge because it is a different tool.
    expect(migrateLayout('canvas')).toBe('canvas')
  })

  it('lands both of the old explore arrangements on `explore`', () => {
    // `split` and `query` were the same panels re-proportioned, both in explore mode.
    expect(migrateLayout('split')).toBe('explore')
    expect(migrateLayout('query')).toBe('explore')
  })

  it('falls back rather than leaving the page with no layout to render', () => {
    // A workspace saved by a future build, or a corrupted one. Returning the stored value
    // unchanged would match no branch in the view and render nothing at all.
    expect(migrateLayout(undefined)).toBe('explore')
    expect(migrateLayout('')).toBe('explore')
    expect(migrateLayout('something-else')).toBe('explore')
  })

  it('round-trips through the saved workspace', () => {
    saveWorkspace({ query: 'Q', layout: 'canvas', queryLimit: 50 })
    expect(migrateLayout(loadWorkspace()?.layout)).toBe('canvas')
  })
})
