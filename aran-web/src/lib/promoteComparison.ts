import { errorMessage } from '@/lib/errors';
import type { SchemaDiff, SchemaVersion, VersionRef } from '@/services/types';

/** What the promote confirmation shows (audit J4-F1): the diff against ACTIVE, or why there is none. */
export type PromoteComparison =
  | { kind: 'first' }
  | { kind: 'ready'; active: VersionRef; diff: SchemaDiff }
  | { kind: 'failed'; message: string };

export interface ComparisonSource {
  listSchemaVersions(): Promise<Array<Pick<SchemaVersion, 'id' | 'versionNumber' | 'status'>>>;
  diffSchemaVersions(a: string, b: string): Promise<SchemaDiff>;
}

/**
 * Compare a draft with the ACTIVE version as it is when this is called — never a version the page
 * read when it loaded (review UA5). Another project can promote while a page sits open, and the
 * diff a user approves must be against what the promote will actually replace. The server's guard
 * still has the last word; this keeps the dialog from asking approval for the wrong list.
 */
export async function loadPromoteComparison(draftId: string, source: ComparisonSource): Promise<PromoteComparison> {
  try {
    const versions = await source.listSchemaVersions();
    const active = versions.find((v) => v.status === 'ACTIVE');
    if (!active) return { kind: 'first' };
    const diff = await source.diffSchemaVersions(active.id, draftId);
    return { kind: 'ready', active: { id: active.id, versionNumber: active.versionNumber }, diff };
  } catch (err: unknown) {
    return { kind: 'failed', message: errorMessage(err, 'unknown error') };
  }
}
