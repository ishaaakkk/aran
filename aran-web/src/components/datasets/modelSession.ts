import { createStore, type StoreApi } from 'zustand/vanilla';
import type { api } from '@/services/api';
import type { CatalogClass, ModelConstraint, ModelSource } from '@/services/types';
import { errorMessage } from '@/lib/errors';
import { describeWrapperSaveCheck, type WrapperSaveCheck } from '@/lib/wrapperSaveCheck';
import { describeEnrolCheck, type EnrolCheck } from '@/lib/enrolCheck';
import { wrapperSeedSelection, type AggregatedDataset } from './types';

/**
 * The integration-model state behind an open dataset's Wrapper and Constraints tabs, and the two
 * writes that change it from there — a wrapper save and an enrol. One per mounted
 * `DatasetsListView`.
 *
 * **Every write, and every re-read it triggers, belongs to the dataset it was made on** (third
 * review of UA2, J4-F11). A wrapper save verifies the wrapper against its source before it answers,
 * with a 150 s deadline, and the user is free to open another dataset meanwhile. When the state
 * was the view's own, a save of acm that answered while dblp was open re-read acm's model into the
 * page: dblp's tab listed acm's class, its editor was re-seeded with acm's wrapper, and a Save then
 * would have written acm's class onto dblp. So:
 *
 * - a model read for a dataset that is no longer on screen **returns** what it read — a save check
 *   compares against it — and **writes nothing**; a read older than one already applied is dropped
 *   the same way, so two re-reads of one dataset cannot land out of order;
 * - in-flight state is keyed — a save by dataset and class (the editor's own
 *   {@link wrapperSeedSelection} key), an enrol by dataset — so dblp's Save is not "Saving…", nor
 *   refused, because acm's is in flight;
 * - an outcome for a dataset that is not on screen is told in a toast that names it.
 *
 * Kept out of React so vitest can drive exactly that sequence: this project has no DOM in its unit
 * tests (`modelSession.test.ts`).
 */

export type ModelSessionApi = Pick<typeof api, 'getModel' | 'queryEngineCatalog' | 'setModelWrapper' | 'addModelSource'>;
export type Notify = (message: string, type?: 'success' | 'error') => void;

/** A save the page stopped waiting for: the SQL it submitted, and what a re-read says of it. */
export interface SaveCheckEntry {
  datasetId: string;
  classIri: string;
  sql: string;
  check: WrapperSaveCheck;
}

export interface ModelSessionState {
  /** The dataset on screen. Every asynchronous write checks against it before it lands. */
  dataset: AggregatedDataset | null;
  modelSources: ModelSource[];
  modelConstraints: ModelConstraint[];
  /** V42: constraints whose integrated class is gone from the active catalog, project-wide. */
  orphanedConstraints: ModelConstraint[];
  datasetClasses: CatalogClass[];
  modelLoading: boolean;
  /** The model could not be read: never shown as "no sources" (J8-F2). */
  modelError: unknown;
  /** The catalog could not be read: never shown as "no integrated classes". */
  catalogError: unknown;
  selectedWrapperClass: string | null;
  selectedConstraintClass: string | null;
  wrapperText: string;
  /** The selection `wrapperText` was last seeded for (V49) — see {@link wrapperSeedSelection}. */
  seededSelection: string | null;
  /** Selection key → the SQL of the save in flight for it. */
  savingWrappers: Record<string, string>;
  /** Selection key → a save the page stopped waiting for, and what a re-read says of it. */
  saveChecks: Record<string, SaveCheckEntry>;
  checkingSaves: Record<string, true>;
  /** Dataset id → an enrol in flight. */
  enrolling: Record<string, true>;
  /** Dataset id → an enrol the page stopped waiting for that a re-read has not seen land. */
  enrolChecks: Record<string, EnrolCheck>;
  checkingEnrols: Record<string, true>;

  /** Put `ds` on screen (null closes it) and read its model. */
  open(ds: AggregatedDataset | null): Promise<ModelSource[] | null>;
  /** Re-read `ds`'s model; state is written only while `ds` is on screen. */
  load(ds: AggregatedDataset): Promise<ModelSource[] | null>;
  /** Re-read the model of the dataset on screen. */
  reload(): Promise<ModelSource[] | null>;
  selectWrapperClass(classIri: string): void;
  selectConstraintClass(classIri: string): void;
  setWrapperText(sql: string): void;
  /** Save the editor's SQL onto the selected class of the dataset on screen. */
  saveWrapper(): Promise<void>;
  /** "Check again" for the selection on screen's timed-out save. */
  recheckWrapperSave(): Promise<void>;
  /** Enrol the dataset on screen in its project's model. */
  enrol(): Promise<void>;
  /** "Check again" for the dataset on screen's timed-out enrol. */
  recheckEnrol(): Promise<void>;
}

const EMPTY_MODEL = {
  modelSources: [] as ModelSource[],
  modelConstraints: [] as ModelConstraint[],
  orphanedConstraints: [] as ModelConstraint[],
  datasetClasses: [] as CatalogClass[],
};

function without<V>(map: Record<string, V>, key: string): Record<string, V> {
  if (!(key in map)) return map;
  const next = { ...map };
  delete next[key];
  return next;
}

function mayStillBeRunning(err: unknown): boolean {
  return (err as { mayStillBeRunning?: boolean } | null)?.mayStillBeRunning === true;
}

/**
 * Seeds the editor when — and only when — the selection moved (V49): a re-read of the same
 * selection must never overwrite what the user typed. A check that already says "saved" is done
 * once its selection is left; one that says "not stored yet" stays, because it is what stops a
 * second verify of the same wrapper.
 */
function seeded(s: ModelSessionState): ModelSessionState {
  const selection = wrapperSeedSelection(s.dataset?.id, s.selectedWrapperClass);
  if (selection === s.seededSelection) return s;
  const src = s.modelSources.find((m) => m.classIri === s.selectedWrapperClass);
  const saveChecks = Object.fromEntries(
    Object.entries(s.saveChecks).filter(([, entry]) => entry.check.kind !== 'saved'),
  );
  // Returning to a selection whose save is still in flight, or whose timed-out save is not yet
  // decided, seeds the SQL the user submitted — not the stored text it is replacing (fourth
  // review of UA2): seeding from storage let the next Save silently revert the save just made.
  const pending = selection === null ? undefined : (s.savingWrappers[selection] ?? saveChecks[selection]?.sql);
  return { ...s, seededSelection: selection, wrapperText: pending ?? src?.wrapperSql ?? '', saveChecks };
}

function keep(prev: string | null, available: string[]): string | null {
  return prev !== null && available.includes(prev) ? prev : (available[0] ?? null);
}

export function createModelSession(deps: { api: ModelSessionApi; notify: Notify }): StoreApi<ModelSessionState> {
  // Reads are numbered so that one older than a read already applied is dropped; `epoch` moves
  // whenever another dataset is put on screen, so a read of the previous one cannot clear the
  // loading state of the next.
  let loadSeq = 0;
  let appliedSeq = 0;
  let epoch = 0;
  let pendingLoads = 0;
  // The dataset whose model is on screen from a read that succeeded. Only a read with nothing on
  // screen yet (the first, or a retry after a failure) is "loading": a re-read after a write keeps
  // the tab as it is instead of swapping it for a spinner — which unmounted the editor, the Save
  // button a keyboard user had just pressed, and the save's own status (third review of UA2).
  let readFor: string | null = null;

  return createStore<ModelSessionState>()((set, get) => {
    const apply = (patch: Partial<ModelSessionState>) => set((s) => seeded({ ...s, ...patch }));
    const onScreen = (ds: AggregatedDataset) => get().dataset?.id === ds.id;
    /** A toast about a dataset that is not the one on screen names it. */
    const about = (ds: AggregatedDataset, message: string) => (onScreen(ds) ? message : `${ds.name}: ${message}`);

    /**
     * Every successful read re-decides the dataset's unresolved save checks against what it read,
     * so a "could not be read" or "not stored yet" answer does not outlive a read that settles it
     * (third review of UA2) — whether the read came from Check again, a retry, or another write.
     */
    const redecide = (ds: AggregatedDataset, sources: ModelSource[]) => {
      const current = get().saveChecks;
      let next: Record<string, SaveCheckEntry> | null = null;
      for (const [key, entry] of Object.entries(current)) {
        if (entry.datasetId !== ds.id || entry.check.kind === 'saved') continue;
        const stored = sources.find((s) => s.classIri === entry.classIri)?.wrapperSql ?? null;
        const check = describeWrapperSaveCheck(entry.sql, stored);
        if (check.kind === entry.check.kind) continue;
        next = { ...(next ?? current), [key]: { ...entry, check } };
      }
      if (next !== null) set({ saveChecks: next });
      const enrolCheck = get().enrolChecks[ds.id];
      if (enrolCheck !== undefined) {
        const settled = describeEnrolCheck(sources.length);
        if (settled === null) set((s) => ({ enrolChecks: without(s.enrolChecks, ds.id) }));
        else if (settled.kind !== enrolCheck.kind) set((s) => ({ enrolChecks: { ...s.enrolChecks, [ds.id]: settled } }));
      }
    };

    const load = async (ds: AggregatedDataset): Promise<ModelSource[] | null> => {
      const seq = ++loadSeq;
      const startedIn = epoch;
      const counted = onScreen(ds);
      if (counted) {
        pendingLoads += 1;
        if (readFor !== ds.id) set({ modelLoading: true });
      }
      const lands = () => onScreen(ds) && seq > appliedSeq;
      try {
        // The catalog only feeds the Constraints tab, so its failure must not blank the Wrapper
        // tab — but it must not read there as "no integrated classes" either.
        let catalogFailure: unknown = null;
        const [bundle, catalog] = await Promise.all([
          deps.api.getModel(ds.projectId),
          deps.api.queryEngineCatalog().catch((err: unknown) => { catalogFailure = err; return null; }),
        ]);
        const sources = bundle.sources.filter((s) => s.datasetId === ds.id);
        redecide(ds, sources);
        if (lands()) {
          appliedSeq = seq;
          readFor = ds.id;
          // Integrated classes this dataset contributes to (matched by datasetId in the catalog).
          const classes = (catalog && !catalog.empty ? catalog.classes : [])
            .filter((c) => c.sources.some((sv) => sv.datasetId === ds.id));
          const classIris = new Set(classes.map((c) => c.iri));
          apply({
            catalogError: catalogFailure,
            modelSources: sources,
            datasetClasses: classes,
            modelConstraints: bundle.constraints.filter((c) => classIris.has(c.classIri)),
            // V42: an orphaned constraint has no class in `classes` to be filtered under, which is
            // how a dead constraint used to be visible nowhere; kept project-wide on purpose.
            orphanedConstraints: bundle.constraints.filter((c) => c.orphaned),
            selectedWrapperClass: keep(get().selectedWrapperClass, sources.map((s) => s.classIri)),
            selectedConstraintClass: keep(get().selectedConstraintClass, classes.map((c) => c.iri)),
            modelError: null,
          });
        }
        return sources;
      } catch (err: unknown) {
        if (lands()) {
          appliedSeq = seq;
          readFor = null;
          apply({ ...EMPTY_MODEL, modelError: err });
        }
        return null;
      } finally {
        if (counted && startedIn === epoch) {
          pendingLoads = Math.max(0, pendingLoads - 1);
          if (pendingLoads === 0) set({ modelLoading: false });
        }
      }
    };

    // Re-reads the model and says whether the wrapper a timed-out save submitted is the one stored.
    const checkSave = async (ds: AggregatedDataset, classIri: string, sql: string) => {
      const key = wrapperSeedSelection(ds.id, classIri)!;
      set((s) => ({ checkingSaves: { ...s.checkingSaves, [key]: true } }));
      try {
        const sources = await load(ds);
        const storedNow = sources === null ? undefined : (sources.find((s) => s.classIri === classIri)?.wrapperSql ?? null);
        const check = describeWrapperSaveCheck(sql, storedNow);
        set((s) => ({ saveChecks: { ...s.saveChecks, [key]: { datasetId: ds.id, classIri, sql, check } } }));
        if (!onScreen(ds)) {
          deps.notify(
            about(ds, check.kind === 'saved' ? check.message : `${check.message} Its Wrapper tab keeps this note, with Check again.`),
            check.kind === 'saved' ? 'success' : 'error',
          );
        }
      } finally {
        set((s) => ({ checkingSaves: without(s.checkingSaves, key) }));
      }
    };

    // Re-reads the model and says whether a timed-out enrol has landed. It stays on the tab until a
    // read settles it — not a toast that fades while Enroll is clickable again, since a second
    // enrol while the first is still seeding can collide with it.
    const checkEnrol = async (ds: AggregatedDataset) => {
      set((s) => ({ checkingEnrols: { ...s.checkingEnrols, [ds.id]: true } }));
      try {
        const sources = await load(ds);
        const check = describeEnrolCheck(sources === null ? undefined : sources.length);
        if (check === null) {
          set((s) => ({ enrolChecks: without(s.enrolChecks, ds.id) }));
          deps.notify(about(ds, 'Dataset enrolled — the server finished after the page stopped waiting.'), 'success');
        } else {
          set((s) => ({ enrolChecks: { ...s.enrolChecks, [ds.id]: check } }));
          if (!onScreen(ds)) deps.notify(about(ds, `${check.message} Its Wrapper tab keeps this note, with Check again.`), 'error');
        }
      } finally {
        set((s) => ({ checkingEnrols: without(s.checkingEnrols, ds.id) }));
      }
    };

    return {
      dataset: null,
      ...EMPTY_MODEL,
      modelLoading: false,
      modelError: null,
      catalogError: null,
      selectedWrapperClass: null,
      selectedConstraintClass: null,
      wrapperText: '',
      seededSelection: null,
      savingWrappers: {},
      saveChecks: {},
      checkingSaves: {},
      enrolling: {},
      enrolChecks: {},
      checkingEnrols: {},

      open: async (ds) => {
        if (ds === null || get().dataset?.id !== ds.id) {
          epoch += 1;
          pendingLoads = 0;
          readFor = null;
          apply({
            dataset: ds,
            ...EMPTY_MODEL,
            selectedWrapperClass: null,
            selectedConstraintClass: null,
            modelLoading: false,
            modelError: null,
            catalogError: null,
          });
        } else {
          set({ dataset: ds });
        }
        return ds === null ? null : load(ds);
      },

      load,

      reload: async () => {
        const ds = get().dataset;
        return ds === null ? null : load(ds);
      },

      selectWrapperClass: (classIri) => apply({ selectedWrapperClass: classIri }),
      selectConstraintClass: (classIri) => set({ selectedConstraintClass: classIri }),
      setWrapperText: (sql) => set({ wrapperText: sql }),

      // In flight, Save is off and says "Saving…" (J4-F11): the PUT verifies the wrapper before it
      // answers, and a second click would start a second verify — on a federated table, a second
      // scan of the live source. A save the page stopped waiting for is checked against a re-read
      // of the model and the answer stays on the tab, with "Check again", rather than a toast that
      // fades in four seconds (Databricks' notebook save indicator and dbt Cloud's IDE both keep
      // "saving" and its outcome on the editor they concern).
      saveWrapper: async () => {
        const { dataset: ds, selectedWrapperClass: classIri, wrapperText: sql } = get();
        if (ds === null || classIri === null) return;
        const key = wrapperSeedSelection(ds.id, classIri)!;
        if (key in get().savingWrappers || key in get().checkingSaves) return;
        set((s) => ({ savingWrappers: { ...s.savingWrappers, [key]: sql }, saveChecks: without(s.saveChecks, key) }));
        try {
          await deps.api.setModelWrapper(ds.projectId, ds.id, classIri, sql);
          deps.notify(about(ds, 'Wrapper SQL saved to the integration model.'), 'success');
          await load(ds);
        } catch (err: unknown) {
          if (mayStillBeRunning(err)) {
            await checkSave(ds, classIri, sql);
          } else {
            deps.notify(about(ds, errorMessage(err, 'Failed to save wrapper.')), 'error');
          }
        } finally {
          set((s) => ({ savingWrappers: without(s.savingWrappers, key) }));
        }
      },

      recheckWrapperSave: async () => {
        const { dataset: ds, selectedWrapperClass: classIri } = get();
        const key = wrapperSeedSelection(ds?.id, classIri);
        const entry = key === null ? undefined : get().saveChecks[key];
        if (ds === null || classIri === null || key === null || entry === undefined || key in get().checkingSaves) return;
        await checkSave(ds, classIri, entry.sql);
      },

      // Seeds one editable wrapper per source class. Off while in flight; an enrol the page stopped
      // waiting for is answered by re-reading the model, which shows the editor if it landed and a
      // note with Check again if it has not.
      enrol: async () => {
        const ds = get().dataset;
        if (ds === null || ds.id in get().enrolling || ds.id in get().checkingEnrols) return;
        set((s) => ({ enrolling: { ...s.enrolling, [ds.id]: true }, enrolChecks: without(s.enrolChecks, ds.id) }));
        try {
          await deps.api.addModelSource(ds.projectId, ds.id);
          deps.notify(about(ds, 'Dataset enrolled in the integration model.'), 'success');
          await load(ds);
        } catch (err: unknown) {
          if (mayStillBeRunning(err)) {
            await checkEnrol(ds);
          } else {
            deps.notify(about(ds, errorMessage(err, 'Failed to enroll dataset.')), 'error');
          }
        } finally {
          set((s) => ({ enrolling: without(s.enrolling, ds.id) }));
        }
      },

      recheckEnrol: async () => {
        const ds = get().dataset;
        if (ds === null || !(ds.id in get().enrolChecks) || ds.id in get().checkingEnrols) return;
        await checkEnrol(ds);
      },
    };
  });
}
