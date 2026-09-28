import { describe, expect, it } from 'vitest';
import { rollbackConsequences } from './rollbackConsequences';

describe('rollbackConsequences (J4-F18, review UA5)', () => {
  it('says a rollback is not held back by the promote guard, so the typed confirmation knows it', () => {
    const lines = rollbackConsequences(1, 2);
    expect(lines.some((l) => l.includes('not refused when it removes classes another project promoted after v1'))).toBe(true);
  });

  it('names the version that becomes historical, and none when nothing is active', () => {
    expect(rollbackConsequences(1, 2)[0]).toContain('v2 becomes historical');
    expect(rollbackConsequences(1, null)[0]).not.toContain('historical');
  });

  it('keeps saying what it does not roll back', () => {
    expect(rollbackConsequences(1, 2).join(' ')).toContain('Integration models are not rolled back');
  });
});
