import { describe, expect, it } from 'vitest';
import { integrationCapabilities } from './useIntegrationModel';
import type { IntegrationModelInfo } from '@/services/types';

/**
 * The rebuild-vs-incremental gate. These four cases are the UI mirror of the server's
 * NO_BASELINE and REBUILD_REQUIRED rejections — if they drift, the UI offers an action that
 * always comes back as a failed job.
 */
describe('integrationCapabilities', () => {
  const model = (over: Partial<IntegrationModelInfo> = {}): IntegrationModelInfo => ({
    id: 'm1',
    projectId: 'p1',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    activeIntegrationRunId: null,
    rebuildRequired: false,
    ...over,
  });

  it('has no baseline before any run has been promoted', () => {
    expect(integrationCapabilities(model())).toEqual({
      hasBaseline: false,
      canIncrement: false,
      staleBaseline: false,
    });
  });

  it('allows an incremental add once a run has been promoted', () => {
    expect(integrationCapabilities(model({ activeIntegrationRunId: 'run-1' }))).toEqual({
      hasBaseline: true,
      canIncrement: true,
      staleBaseline: false,
    });
  });

  it('blocks the incremental add when a rebuild is required, keeping the baseline', () => {
    expect(integrationCapabilities(
      model({ activeIntegrationRunId: 'run-1', rebuildRequired: true }),
    )).toEqual({ hasBaseline: true, canIncrement: false, staleBaseline: false });
  });

  it('treats a missing model as no baseline rather than throwing', () => {
    expect(integrationCapabilities(null)).toEqual({ hasBaseline: false, canIncrement: false, staleBaseline: false });
    expect(integrationCapabilities(undefined)).toEqual({ hasBaseline: false, canIncrement: false, staleBaseline: false });
  });

  // J4-F2: the server's third refusal, STALE_INCREMENTAL_BASE — the baseline is no longer ACTIVE.
  it('allows the incremental add while the baseline is the active schema', () => {
    expect(integrationCapabilities(model({ activeIntegrationRunId: 'run-1' }),
      { baselineVersionId: 'v2', activeVersion: { id: 'v2', versionNumber: 2 } }))
      .toEqual({ hasBaseline: true, canIncrement: true, staleBaseline: false });
  });

  it('blocks it once a rollback or another project\'s promote replaced the baseline', () => {
    expect(integrationCapabilities(model({ activeIntegrationRunId: 'run-1' }),
      { baselineVersionId: 'v2', activeVersion: { id: 'v3', versionNumber: 3 } }))
      .toEqual({ hasBaseline: true, canIncrement: false, staleBaseline: true });
    expect(integrationCapabilities(model({ activeIntegrationRunId: 'run-1' }),
      { baselineVersionId: 'v2', activeVersion: null }))
      .toEqual({ hasBaseline: true, canIncrement: false, staleBaseline: true });
  });
});

/**
 * Second review of UA2: with the model unloaded, `suggest` diffed against `[]`, so every returned
 * row — including existing ones addMapping hands back unchanged — was reported as added.
 * suggestOutcome.test.ts pins the sentence; this pins that the hook never passes an invented
 * empty "before". No DOM here to run the hook, so the source is read.
 */
describe('the suggest action', () => {
  it('re-reads an unloaded model and passes null, never [], when it cannot', async () => {
    const { readFileSync } = await import('node:fs');
    const source = readFileSync(new URL('./useIntegrationModel.ts', import.meta.url), 'utf8');
    const suggest = source.slice(source.indexOf('suggest: async'), source.indexOf('compile: async'));
    expect(suggest).not.toContain("bundle?.mappings ?? []");
    expect(suggest).toContain('(await api.getModel(projectId).catch(() => null))?.mappings');
    expect(suggest).toContain('?? null');
  });
});
