import { describe, expect, it } from 'vitest';
import { compileToReattach } from './compileReattach';
import type { IntegrationModelInfo } from '@/services/types';

const model = (projectId: string): IntegrationModelInfo => ({
  id: 'm', projectId, createdAt: '', updatedAt: '', activeIntegrationRunId: null, rebuildRequired: false,
});
const fresh = { projectId: 'p1', compileJobId: null, submitting: false, settledJobIds: new Set<string>() };

describe('compileToReattach (review UA5, J4-F5) — a reload mid-compile re-attaches instead of offering a second compile', () => {
  it('watches the compile the server says is still running, in its own mode', () => {
    expect(compileToReattach({ model: model('p1'), compileJob: { jobId: 'j1', jobType: 'model:compile', status: 'RUNNING' } }, fresh))
      .toEqual({ jobId: 'j1', mode: 'rebuild' });
    expect(compileToReattach({ model: model('p1'), compileJob: { jobId: 'j2', jobType: 'model:incremental-integrate', status: 'PENDING' } }, fresh))
      .toEqual({ jobId: 'j2', mode: 'incremental' });
  });

  it('does nothing when no compile is running', () => {
    expect(compileToReattach({ model: model('p1'), compileJob: null }, fresh)).toBeNull();
    expect(compileToReattach({ model: model('p1') }, fresh)).toBeNull();
    expect(compileToReattach(null, fresh)).toBeNull();
  });

  it('never replaces the job the page is watching or submitting, nor one it has seen end', () => {
    const bundle = { model: model('p1'), compileJob: { jobId: 'j1', jobType: 'model:compile', status: 'RUNNING' } };
    expect(compileToReattach(bundle, { ...fresh, compileJobId: 'j0' })).toBeNull();
    expect(compileToReattach(bundle, { ...fresh, submitting: true })).toBeNull();
    expect(compileToReattach(bundle, { ...fresh, settledJobIds: new Set(['j1']) })).toBeNull();
  });

  it('never attaches the previous project\'s compile while the new project\'s bundle loads', () => {
    const stale = { model: model('p0'), compileJob: { jobId: 'j1', jobType: 'model:compile', status: 'RUNNING' } };
    expect(compileToReattach(stale, fresh)).toBeNull();
  });
});
