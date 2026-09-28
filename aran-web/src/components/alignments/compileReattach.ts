import type { IntegrationModelBundle } from '@/services/types';

/**
 * Which compile the page should watch after a reload (review UA5, audit J4-F5).
 *
 * The compile job id was React state only, and the bundle carried only DRAFT runs, so a reload
 * mid-compile forgot the compile: Rebuild came back enabled and no draft was restored (the compile
 * had superseded it), inviting a second compile. The bundle now names the project's compile still
 * in flight, and the page re-attaches its job card to it — unless it is already watching a job,
 * is submitting one, has already seen this one end, or the bundle is still the previous project's.
 */
export function compileToReattach(
  bundle: Pick<IntegrationModelBundle, 'model' | 'compileJob'> | null | undefined,
  page: {
    projectId: string | null;
    compileJobId: string | null;
    submitting: boolean;
    settledJobIds: ReadonlySet<string>;
  },
): { jobId: string; mode: 'rebuild' | 'incremental' } | null {
  const job = bundle?.compileJob;
  if (!job || !bundle || bundle.model.projectId !== page.projectId) return null;
  if (page.compileJobId || page.submitting || page.settledJobIds.has(job.jobId)) return null;
  return { jobId: job.jobId, mode: job.jobType === 'model:incremental-integrate' ? 'incremental' : 'rebuild' };
}
