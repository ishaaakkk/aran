import { describe, expect, it } from 'vitest';
import { loadPromoteComparison, type ComparisonSource } from './promoteComparison';
import type { SchemaDiff } from '@/services/types';

const diff: SchemaDiff = { addedClasses: [], removedClasses: ['http://ex/Author'], changedClasses: [] };

/** A version list that can change between calls, as the tenant's can while a page sits open. */
function source(versions: Array<{ id: string; versionNumber: number; status: string }>): ComparisonSource & { diffs: string[] } {
  const diffs: string[] = [];
  return {
    diffs,
    listSchemaVersions: async () => versions,
    diffSchemaVersions: async (a, b) => {
      diffs.push(`${a}→${b}`);
      return diff;
    },
  };
}

describe('loadPromoteComparison (review UA5) — the diff is against ACTIVE as it is now', () => {
  it('compares the draft with the version that is ACTIVE when the dialog opens', async () => {
    const versions = [
      { id: 'v5', versionNumber: 5, status: 'ACTIVE' },
      { id: 'd7', versionNumber: 7, status: 'DRAFT' },
    ];
    const src = source(versions);
    expect(await loadPromoteComparison('d7', src)).toMatchObject({ kind: 'ready', active: { id: 'v5', versionNumber: 5 } });

    // Another project promotes v6 while the page sits open: the next open compares with v6.
    versions[0].status = 'HISTORICAL';
    versions.push({ id: 'v6', versionNumber: 6, status: 'ACTIVE' });
    expect(await loadPromoteComparison('d7', src)).toMatchObject({ kind: 'ready', active: { id: 'v6', versionNumber: 6 } });
    expect(src.diffs).toEqual(['v5→d7', 'v6→d7']);
  });

  it('says there is nothing to compare with when no version is ACTIVE', async () => {
    expect(await loadPromoteComparison('d1', source([{ id: 'd1', versionNumber: 1, status: 'DRAFT' }])))
      .toEqual({ kind: 'first' });
  });

  it('says why when the comparison cannot be read — never an empty diff', async () => {
    const failing: ComparisonSource = {
      listSchemaVersions: async () => { throw new Error('schema store unavailable'); },
      diffSchemaVersions: async () => diff,
    };
    expect(await loadPromoteComparison('d1', failing)).toEqual({ kind: 'failed', message: 'schema store unavailable' });
  });
});
