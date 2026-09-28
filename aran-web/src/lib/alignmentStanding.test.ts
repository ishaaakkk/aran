import { describe, expect, it } from 'vitest';
import { alignmentStanding, compileBacklog, joinPropertyName, joinTier, standingContext } from './alignmentStanding';
import type { CubeEvidenceEdge, MappingApplicationInfo } from '@/services/types';

const active = { id: 'v3', versionNumber: 3 };
const draft = { draftVersionId: 'v4', draftVersionNumber: 4 };
/** v3 is ACTIVE and it is this model's own last promote; v4 is its unpromoted draft. */
const ctx = { activeVersion: active, draft, baselineVersionId: 'v3' };
const applied = (versionId: string): { application: MappingApplicationInfo } =>
  ({ application: { state: 'APPLIED', versionId, reason: null } });

describe('alignmentStanding (J4-F5) — live, in a draft, pending or deferred, from the application row', () => {
  it('an accepted alignment in no compiled draft is not compiled', () => {
    expect(alignmentStanding({ application: null }, ctx).key).toBe('pending');
    expect(alignmentStanding({ application: { state: 'PENDING', versionId: null, reason: null } }, ctx).key)
      .toBe('pending');
  });

  it('compiled into the ACTIVE version is live', () => {
    const s = alignmentStanding(applied('v3'), ctx);
    expect(s.key).toBe('live');
    expect(s.detail).toContain('v3');
  });

  it('compiled into the pending draft waits for its promote', () => {
    expect(alignmentStanding(applied('v4'), ctx)).toMatchObject({ key: 'draft', label: 'in draft v4' });
  });

  it('after a promoted incremental add, the baseline\'s alignments are live — their row still names the old version', () => {
    // The server's state after rebuild → promote v1 → accept → "add without re-reading the
    // others" → promote v2 (IncrementalIntegrationServiceTest.full_incremental_cycle): the
    // incremental run sent only the new alignment, so the v1 rows keep versionId v1 while v2,
    // which carried their classes forward, is both the ACTIVE version and the model's baseline.
    const afterIncremental = { activeVersion: { id: 'v2', versionNumber: 2 }, draft: null, baselineVersionId: 'v2' };
    const carried = alignmentStanding(applied('v1'), afterIncremental);
    expect(carried.key).toBe('live');
    expect(carried.detail).toContain('v2');
    expect(alignmentStanding(applied('v2'), afterIncremental).key).toBe('live');
  });

  it('a full rebuild over an ACTIVE baseline never says "live once promoted" — the active schema may already serve it', () => {
    // Review UA5: a rebuild re-marks every APPLIED row with its draft, so a row v3 has served for
    // weeks reads "in draft v4". Its detail must not claim it is not live yet.
    const rebuild = { ...ctx, draft: { ...draft, mode: 'REBUILD' } };
    const s = alignmentStanding(applied('v4'), rebuild);
    expect(s).toMatchObject({ key: 'draft', label: 'in draft v4' });
    expect(s.detail).toContain('may already serve it');
    expect(s.detail).toContain('v3');
    expect(s.detail).not.toContain('live once that draft is promoted');
  });

  it('an incremental draft\'s own alignments are new, so they are live once that draft is promoted', () => {
    const incremental = { ...ctx, draft: { ...draft, mode: 'INCREMENTAL' } };
    expect(alignmentStanding(applied('v4'), incremental).detail).toContain('live once that draft is promoted');
  });

  it('a rebuild with no ACTIVE baseline to be served by is live once promoted', () => {
    const firstRebuild = { activeVersion: null, draft: { ...draft, mode: 'REBUILD' }, baselineVersionId: null };
    expect(alignmentStanding(applied('v4'), firstRebuild).detail).toContain('live once that draft is promoted');
  });

  it('a baseline alignment stays live while an incremental draft waits, and only the draft\'s own are "in draft"', () => {
    expect(alignmentStanding(applied('v1'), ctx).key).toBe('live');
    expect(alignmentStanding(applied('v4'), ctx).key).toBe('draft');
  });

  it('compiled into a version that is no longer active is not live — never shown as live', () => {
    // The model's baseline is not the ACTIVE version: a rollback or another project's promote.
    const replaced = { activeVersion: active, draft: null, baselineVersionId: 'v2' };
    expect(alignmentStanding(applied('v1'), replaced).key).toBe('stale');
    expect(alignmentStanding(applied('v1'), { activeVersion: active, draft: null, baselineVersionId: null }).key)
      .toBe('stale');
    expect(alignmentStanding(applied('v1'), { activeVersion: null, draft: null, baselineVersionId: 'v1' }).key)
      .toBe('stale');
  });

  it('a deferred alignment carries the compiler\'s reason', () => {
    const s = alignmentStanding({ application: { state: 'DEFERRED', versionId: null, reason: 'no join key' } }, ctx);
    expect(s.key).toBe('deferred');
    expect(s.detail).toContain('no join key');
  });

  it('reads its context off the bundle', () => {
    expect(standingContext({
      activeVersion: active,
      latestDraftRun: { runId: 'r', mode: 'INCREMENTAL', draftVersionId: 'v4', draftVersionNumber: 4, compiledAt: '' },
      baselineVersionId: 'v3',
    })).toMatchObject({ activeVersion: active, draft: { draftVersionId: 'v4' }, baselineVersionId: 'v3' });
    expect(standingContext(null)).toEqual({ activeVersion: null, draft: null, baselineVersionId: null });
  });
});

describe('compileBacklog (J4-F5) — what the stepper calls "changes to compile"', () => {
  const live = { activeVersion: { id: 'v2', versionNumber: 2 }, draft: null, baselineVersionId: 'v2' };

  it('nothing to compile after a promoted incremental add, though the baseline rows name v1', () => {
    expect(compileBacklog([applied('v1'), applied('v2')], live, false))
      .toEqual({ changesToCompile: false, deferredCount: 0 });
  });

  it('a deferred alignment is counted, not called a change to compile — recompiling does not clear it', () => {
    const deferred = { application: { state: 'DEFERRED' as const, versionId: null, reason: 'no join key' } };
    expect(compileBacklog([applied('v1'), deferred], live, false))
      .toEqual({ changesToCompile: false, deferredCount: 1 });
  });

  it('a new accepted alignment, a replaced baseline or a required rebuild is a change to compile', () => {
    expect(compileBacklog([applied('v2'), { application: null }], live, false).changesToCompile).toBe(true);
    expect(compileBacklog([applied('v1')], { ...live, baselineVersionId: 'v1' }, false).changesToCompile).toBe(true);
    expect(compileBacklog([applied('v2')], live, true).changesToCompile).toBe(true);
  });
});

describe('joinTier (J4-F17) — the ACTIVE catalog\'s trust tier for an accepted join', () => {
  const edge = (propertyName: string, tier: CubeEvidenceEdge['tier']): CubeEvidenceEdge => ({
    propertyIri: `http://ex/${propertyName}`, propertyName, leftClassIri: 'a', rightClassIri: 'b',
    leftColIri: 'a/x', rightColIri: 'b/y', tier,
  });

  it('names the property the server mints: the relationship, else <label>_join, fragment-safe', () => {
    expect(joinPropertyName('authorKey|hasAuthor|forward')).toBe('hasAuthor');
    expect(joinPropertyName('join_id_id')).toBe('join_id_id_join');
    expect(joinPropertyName('k|has author|forward')).toBe('has_author');
    expect(joinPropertyName(null)).toBe('');
  });

  it('reads the tier off the matching edge', () => {
    const edges = [edge('hasAuthor', 'DECLARED'), edge('inVenue', 'HYPOTHESIS')];
    expect(joinTier({ kind: 'join', label: 'authorKey|hasAuthor|forward' }, edges)).toBe('DECLARED');
    expect(joinTier({ kind: 'join', label: 'v|inVenue|forward' }, edges)).toBe('HYPOTHESIS');
  });

  it('is unknown for a join the active schema does not serve, for a non-join, and without evidence', () => {
    expect(joinTier({ kind: 'join', label: 'k|notLive|forward' }, [edge('hasAuthor', 'DECLARED')])).toBeNull();
    expect(joinTier({ kind: 'class', label: 'Author' }, [edge('Author', 'DECLARED')])).toBeNull();
    expect(joinTier({ kind: 'join', label: 'k|hasAuthor|forward' }, null)).toBeNull();
  });
});
