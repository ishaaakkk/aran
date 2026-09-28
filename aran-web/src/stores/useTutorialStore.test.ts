import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * vitest runs in the node environment here (no jsdom — see vite.config.ts), so
 * `localStorage` does not exist. The store must therefore survive its absence,
 * which is also what makes these tests possible without a new dependency.
 */
function fakeLocalStorage() {
  const map = new Map<string, string>();
  return {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
    key: (i: number) => [...map.keys()][i] ?? null,
    get length() { return map.size; },
  } as Storage;
}

/** Re-imports the module so the hydrate-on-import path runs against fresh storage. */
async function freshStore() {
  vi.resetModules();
  const mod = await import('./useTutorialStore');
  return mod.useTutorialStore;
}

describe('useTutorialStore', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', fakeLocalStorage());
  });

  it('starts with nothing seen', async () => {
    const store = await freshStore();
    expect(store.getState().seenTours).toEqual({});
    expect(store.getState().hasSeenTour('dashboard')).toBe(false);
  });

  it('marks a tour seen without disturbing the others', async () => {
    const store = await freshStore();
    store.getState().markTourSeen('alignments');
    expect(store.getState().hasSeenTour('alignments')).toBe(true);
    expect(store.getState().hasSeenTour('oql')).toBe(false);
  });

  it('persists every seen tour under one key and rehydrates from it', async () => {
    const store = await freshStore();
    store.getState().markTourSeen('dashboard');
    store.getState().markTourSeen('versions');
    expect(JSON.parse(localStorage.getItem('aran.tutorial.seen')!))
      .toEqual({ dashboard: true, versions: true });

    const rehydrated = await freshStore();
    expect(rehydrated.getState().hasSeenTour('dashboard')).toBe(true);
    expect(rehydrated.getState().hasSeenTour('versions')).toBe(true);
  });

  it('resetTours clears both memory and storage', async () => {
    const store = await freshStore();
    store.getState().markTourSeen('projects');
    store.getState().resetTours();
    expect(store.getState().seenTours).toEqual({});
    expect(localStorage.getItem('aran.tutorial.seen')).toBeNull();
  });

  it('round-trips a manual trigger', async () => {
    const store = await freshStore();
    expect(store.getState().manualTrigger).toBeNull();
    store.getState().triggerTour('oql');
    expect(store.getState().manualTrigger).toBe('oql');
    store.getState().clearManualTrigger();
    expect(store.getState().manualTrigger).toBeNull();
  });

  it('ignores corrupt persisted state instead of throwing', async () => {
    localStorage.setItem('aran.tutorial.seen', 'not json');
    const store = await freshStore();
    expect(store.getState().seenTours).toEqual({});
  });

  it('imports cleanly when localStorage does not exist at all', async () => {
    vi.stubGlobal('localStorage', undefined);
    const store = await freshStore();
    expect(store.getState().seenTours).toEqual({});
    expect(() => store.getState().markTourSeen('dashboard')).not.toThrow();
  });
});
