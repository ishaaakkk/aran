import { test, expect, specUser } from '../fixtures';

test.use(specUser('c3'));

/**
 * C3 — the negative case. venue_code (V-01..V-03) and the researcher keys
 * (rid, R-001..R-003) share no values and no alphabet, so discovery must not
 * propose a join between them.
 *
 * Telling a correct rejection from broken discovery (the handoff's Task 18
 * correction #5): an "X is absent" check alone passes trivially if discovery
 * returned nothing at all. The fixture carries its own positive control — the
 * same files hold a real join, author_ref (R-001..R-003) ↔ rid (R-001..R-003),
 * which c1 pins in its top 3 — so a healthy run MUST settle with candidates.
 * Since A10 (J3-F5) the run also reports what it compared and whether it
 * degraded, so the spec asserts all three: the run settled as `candidates`
 * (which a degraded, unmeasured or silently zero-scoring run cannot), it
 * compared column pairs, and none of them was venue_code ↔ rid.
 */
test('no join is suggested between disjoint value domains', async ({
  journey, datasets, alignments,
}) => {
  await journey.bootstrapSources({
    project: 'c3-proj',
    repo: 'c3-files',
    sources: [
      { name: 'acm', file: 'acm_papers.csv' },
      { name: 'researchers', file: 'researchers.csv' },
    ],
  });

  await datasets.goto();
  await datasets.runProfiling('acm');
  await datasets.goto();
  await datasets.runProfiling('researchers');

  await alignments.goto();
  await alignments.selectProject('c3-proj');
  await alignments.selectSources('acm', 'researchers');
  const outcome = await alignments.discover();

  // The positive control: author_ref ↔ rid is a real join in these files, so a run that
  // proposes nothing at all is broken (degraded, unmeasured, or scoring every pair 0), not correct.
  expect(
    outcome.state,
    `discovery did not settle with candidates — the fixture's real join (author_ref ↔ rid) should be one; cannot tell a correct rejection from a broken run: ${outcome.message}`,
  ).toBe('candidates');
  expect(
    outcome.pairsScored ?? 0,
    `discovery compared no column pairs — cannot tell a correct rejection from a run that never scored: ${outcome.message}`,
  ).toBeGreaterThan(0);

  const ranked = await alignments.suggestions();
  const venueToRid = ranked.find((s) => s.left === 'venue_code' && s.right === 'rid');
  expect(
    venueToRid,
    `venue_code (V-xx) and rid (R-xxx) share nothing, yet were paired: ${JSON.stringify(ranked)}`,
  ).toBeUndefined();
});
