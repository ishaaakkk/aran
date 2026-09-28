import { create } from 'zustand';

/**
 * One key holds every tour's seen-flag as a JSON object. The previous shape was
 * one localStorage key per tour, which does not survive a registry that grows a
 * page at a time. The old `aran.tutorial.dashboard` / `.projects` keys are
 * abandoned deliberately — re-showing two tours once is cheaper than migration
 * code that outlives its usefulness.
 */
const STORAGE_KEY = 'aran.tutorial.seen';

/**
 * Guarded on every path: vitest runs in the node environment, where there is no
 * `localStorage` at all, and a browser in private mode can throw on write. The
 * tour is cosmetic — it must never be the reason the app fails to boot.
 */
function readSeen(): Record<string, boolean> {
  try {
    const raw = globalThis.localStorage?.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    return parsed as Record<string, boolean>;
  } catch {
    return {};
  }
}

function writeSeen(seen: Record<string, boolean>): void {
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, JSON.stringify(seen));
  } catch {
    /* quota or private mode — losing the flag only re-shows a tour */
  }
}

interface TutorialState {
  /** tourId → true once the tour has been completed or dismissed. */
  seenTours: Record<string, boolean>;
  /** tourId the user explicitly asked to replay, or null. */
  manualTrigger: string | null;
  hasSeenTour: (tourId: string) => boolean;
  markTourSeen: (tourId: string) => void;
  triggerTour: (tourId: string) => void;
  clearManualTrigger: () => void;
  resetTours: () => void;
}

export const useTutorialStore = create<TutorialState>((set, get) => ({
  seenTours: readSeen(),
  manualTrigger: null,

  hasSeenTour: (tourId) => get().seenTours[tourId] === true,

  markTourSeen: (tourId) =>
    set((state) => {
      if (state.seenTours[tourId]) return state;
      const seenTours = { ...state.seenTours, [tourId]: true };
      writeSeen(seenTours);
      return { seenTours };
    }),

  triggerTour: (tourId) => set({ manualTrigger: tourId }),
  clearManualTrigger: () => set({ manualTrigger: null }),

  resetTours: () => {
    try {
      globalThis.localStorage?.removeItem(STORAGE_KEY);
    } catch {
      /* see writeSeen */
    }
    set({ seenTours: {} });
  },
}));
