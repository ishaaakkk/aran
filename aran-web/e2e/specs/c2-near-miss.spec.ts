import { test, expect, specUser } from '../fixtures';
import { EXPECTED } from '../data';

test.use(specUser('c2'));

/**
 * C2 — the deliberate near-miss. reviewer_ref shares exactly R-001 and R-002
 * of ten distinct values with rid (~20% containment; see acm_papers.csv),
 * so freyja may well offer it. What must hold is that it ranks below the
 * true pair, and that rejecting it still leaves a correct integration.
 *
 * UNVERIFIED — see c1-discovery.spec.ts's header for the full derivation of
 * the class names and relationship name, and why: not run against a live
 * stack in this environment (Docker image pulls are blocked here). Same
 * approach here: read live via workbench.*, never hardcoded.
 *
 * The `if (nearIdx >= 0)` guard below is deliberate, not a weakened
 * assertion: freyja may or may not surface the near-miss at all (it is a
 * real but weak signal), but if it does, it must never outrank the true
 * pair. An unconditional `toBeGreaterThanOrEqual(0)` on nearIdx would make
 * this spec depend on freyja's exact scoring threshold, which is not the
 * property C2 exists to test.
 */

const ACM_TITLES: string[] = [...EXPECTED.acmTitles];
const acmPairs = EXPECTED.authorPairs.filter(([title]) => ACM_TITLES.includes(title));

/** Stable, comparable form for a whole result row. */
function rowKey(cells: readonly string[]): string {
  return cells.join(' | ');
}

test('a near-miss candidate ranks below the true join and can be rejected', async ({
  journey, datasets, alignments, workbench,
}) => {
  await journey.bootstrapSources({
    project: 'c2-proj',
    repo: 'c2-files',
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
  await alignments.selectProject('c2-proj');
  await alignments.selectSources('acm', 'researchers');
  await alignments.runDiscovery();

  const ranked = await alignments.suggestions();
  const trueIdx = ranked.findIndex((s) => s.left === 'author_ref' && s.right === 'rid');
  const nearIdx = ranked.findIndex((s) => s.left === 'reviewer_ref' && s.right === 'rid');

  expect(trueIdx, 'the true join must be suggested').toBeGreaterThanOrEqual(0);
  if (nearIdx >= 0) {
    expect(trueIdx, 'the near-miss must not outrank the true join').toBeLessThan(nearIdx);
  }

  // Accept only the true pair — the near-miss is simply left unchecked.
  await alignments.acceptSuggestions([['author_ref', 'rid']]);
  await alignments.integrate();
  await alignments.promote();

  await workbench.goto();

  const classNames = await workbench.waitForCatalogClasses();
  const acmClass = classNames.find((n) => /acm/i.test(n));
  const researchersClass = classNames.find((n) => /researcher/i.test(n));
  expect(acmClass, `no acm-ish class in catalog: ${classNames.join(', ')}`).toBeDefined();
  expect(researchersClass, `no researchers-ish class in catalog: ${classNames.join(', ')}`).toBeDefined();
  expect(acmClass).toBe('Int_acm');
  expect(researchersClass).toBe('Int_researchers');

  const relationships = await workbench.catalogRelationships();
  const rel = relationships.find(
    (r) =>
      (r.from === acmClass && r.to === researchersClass) ||
      (r.from === researchersClass && r.to === acmClass),
  );
  expect(
    rel,
    `no relationship between ${acmClass} and ${researchersClass}: ${JSON.stringify(relationships)}`,
  ).toBeDefined();

  const path =
    rel!.from === acmClass
      ? `${acmClass} -[${rel!.name}]-> ${researchersClass}`
      : `${researchersClass} -[${rel!.name}]-> ${acmClass}`;
  await workbench.run(`SELECT ${acmClass}.title, ${researchersClass}.full_name FROM ${path}`);

  // Must equal the C1 baseline exactly: had reviewer_ref been folded in, the
  // extra edge would multiply or otherwise change the row set.
  const rows = await workbench.rows();
  expect(rows.length).toBe(acmPairs.length);
  expect(rows.map(rowKey).sort()).toEqual(acmPairs.map(rowKey).sort());

  const names = rows.map(([, name]) => name);
  expect([...new Set(names)].sort()).toEqual([...new Set(acmPairs.map(([, n]) => n))].sort());
});
