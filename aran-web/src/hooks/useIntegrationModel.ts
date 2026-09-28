import { useCallback, useEffect, useState } from 'react';
import { suggestOutcome, type SuggestOutcome } from '@/lib/suggestOutcome';
import { api } from '@/services/api';
import type { IntegrationModelBundle, IntegrationModelInfo, MappingKind } from '@/services/types';
import { errorMessage } from '@/lib/errors';

/**
 * Which integration actions the model currently allows.
 *
 * Mirrors the server's own guards so the UI can disable an action rather than let the user
 * submit a job that comes back rejected: `incrementalIntegrateToDraft` throws NO_BASELINE when
 * no run has been promoted, REBUILD_REQUIRED when integrated material changed underneath the
 * baseline, and STALE_INCREMENTAL_BASE when the baseline is no longer the ACTIVE schema — a
 * rollback, or another project's promote, replaced it (audit J4-F2). A full rebuild is always
 * allowed and is the way out of all three.
 *
 * `standing` is the bundle's `baselineVersionId`/`activeVersion`; a bundle that predates them
 * (a fixture) omits it and the third check is skipped rather than guessed.
 */
export function integrationCapabilities(
  model: IntegrationModelInfo | null | undefined,
  standing?: Pick<IntegrationModelBundle, 'baselineVersionId' | 'activeVersion'>,
): {
  hasBaseline: boolean;
  canIncrement: boolean;
  /** The model's baseline is not the ACTIVE schema (STALE_INCREMENTAL_BASE). */
  staleBaseline: boolean;
} {
  const hasBaseline = model?.activeIntegrationRunId != null;
  const staleBaseline = hasBaseline && standing !== undefined && standing.baselineVersionId !== undefined
    && (standing.baselineVersionId == null || standing.baselineVersionId !== standing.activeVersion?.id);
  return {
    hasBaseline,
    canIncrement: hasBaseline && !model?.rebuildRequired && !staleBaseline,
    staleBaseline,
  };
}

/**
 * The project's durable integration model, loaded from and written to the server.
 *
 * This is the single source of truth for enrolled sources, alignments (accepted +
 * the suggestion review queue) and constraints — the alignments view keeps none of
 * it in React state. Each action performs its write and then re-reads the bundle, so
 * the view always renders exactly what the server holds (a hard refresh loses
 * nothing). Deliberately plain `useState`/`useEffect`: no React Query, no Redux.
 */
export interface IntegrationModelActions {
  addSource: (datasetId: string) => Promise<void>;
  removeSource: (datasetId: string) => Promise<void>;
  addMapping: (body: { kind: MappingKind; sourceIri: string; targetIri: string; label?: string; comment?: string }) => Promise<void>;
  accept: (mappingId: string) => Promise<void>;
  reject: (mappingId: string) => Promise<void>;
  remove: (mappingId: string) => Promise<void>;
  /** Resolves with what the run added, so the page can say it (J3-F17); null with no project. */
  suggest: (leftDatasetId: string, rightDatasetId: string) => Promise<SuggestOutcome | null>;
  /** Submit the full-rebuild job. Returns its id so the caller can watch it; the model is unchanged. */
  compile: () => Promise<string>;
  /** Submit an incremental add of one source on top of the promoted baseline. Returns the job id.
   *  Only legal once a baseline exists and no rebuild is pending — see {@link canIncrement}. */
  incrementalIntegrate: (datasetId: string) => Promise<string>;
  /** Model-aware promotion of a run's draft: records the run as PROMOTED, points the model at it
   *  and clears `rebuildRequired` after a rebuild — which is what establishes the baseline every
   *  later incremental add needs. Refused 409 when it would remove another project's classes. */
  promoteRun: (runId: string) => Promise<void>;
}

export interface UseIntegrationModel {
  bundle: IntegrationModelBundle | null;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  actions: IntegrationModelActions;
  /** True when the model has a promoted baseline — i.e. a run has been promoted through the
   *  model-aware endpoint. Until then only a full rebuild is possible. */
  hasBaseline: boolean;
  /** True when an incremental add is currently legal: a baseline exists and nothing has
   *  invalidated it. Mirrors the server's own REBUILD_REQUIRED / NO_BASELINE /
   *  STALE_INCREMENTAL_BASE rejections so the UI can disable the action rather than surface a
   *  failed job. */
  canIncrement: boolean;
  /** The baseline is no longer the ACTIVE schema — the reason `canIncrement` is false, if it is. */
  staleBaseline: boolean;
}

export function useIntegrationModel(projectId: string | null): UseIntegrationModel {
  const [bundle, setBundle] = useState<IntegrationModelBundle | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!projectId) {
      setBundle(null);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      setBundle(await api.getModel(projectId));
    } catch (err: unknown) {
      setError(errorMessage(err, 'Failed to load integration model'));
      setBundle(null);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  // (Re)load whenever the bound project changes. An async network fetch has no
  // render-time equivalent — this is exactly what effects are for.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
  }, [refresh]);

  const actions: IntegrationModelActions = {
    addSource: async (datasetId) => {
      if (!projectId) return;
      await api.addModelSource(projectId, datasetId);
      await refresh();
    },
    removeSource: async (datasetId) => {
      if (!projectId) return;
      await api.removeModelSource(projectId, datasetId);
      await refresh();
    },
    addMapping: async (body) => {
      if (!projectId) return;
      await api.addMapping(projectId, body);
      await refresh();
    },
    accept: async (mappingId) => {
      if (!projectId) return;
      await api.patchMapping(projectId, mappingId, 'ACCEPTED');
      await refresh();
    },
    reject: async (mappingId) => {
      if (!projectId) return;
      await api.patchMapping(projectId, mappingId, 'REJECTED');
      await refresh();
    },
    remove: async (mappingId) => {
      if (!projectId) return;
      await api.deleteMapping(projectId, mappingId);
      await refresh();
    },
    suggest: async (leftDatasetId, rightDatasetId) => {
      if (!projectId) return null;
      // What the run added is the difference from what the model held before it. With no model
      // loaded, read it now; if that fails too, the outcome says it cannot tell what is new rather
      // than counting every returned row as added (second review of UA2).
      const before = bundle?.mappings
        ?? (await api.getModel(projectId).catch(() => null))?.mappings
        ?? null;
      const returned = await api.suggestMappings(projectId, leftDatasetId, rightDatasetId);
      await refresh();
      return suggestOutcome(returned ?? [], before);
    },
    compile: async () => {
      if (!projectId) throw new Error('no project selected');
      const { jobId } = await api.compileModel(projectId);
      return jobId;
    },
    incrementalIntegrate: async (datasetId) => {
      if (!projectId) throw new Error('no project selected');
      const { jobId } = await api.incrementalIntegrate(projectId, datasetId);
      return jobId;
    },
    promoteRun: async (runId) => {
      if (!projectId) throw new Error('no project selected');
      await api.promoteModelRun(projectId, runId);
      await refresh();
    },
  };

  const { hasBaseline, canIncrement, staleBaseline } = integrationCapabilities(
    bundle?.model, bundle ?? undefined);

  return { bundle, loading, error, refresh, actions, hasBaseline, canIncrement, staleBaseline };
}
