import { readFileSync } from 'node:fs';
import { test, expect, specUser } from '../fixtures';
import { EXPECTED, fixturePath } from '../data';

test.use(specUser('v37'));

// Test 2 reads the products test 1 created and enforces a constraint on the schema test 1
// promoted. `fullyParallel: true` would scatter them across workers, each finding a tenant
// nothing had set up.
test.describe.configure({ mode: 'serial', timeout: 10 * 60 * 1000 });

/**
 * V37 — virtual data products, the browser half.
 *
 * The claim: a VIRTUAL product is servable the moment it is created (no materialize step, no
 * snapshot, nothing to refresh) and every read of it is a fresh execution against the ACTIVE
 * catalog — so a denial constraint enforced after the product exists changes its very next
 * read, with no product action in between. That last sentence is the whole reason the access
 * mode exists; test 2 is where it can fail.
 *
 * A MATERIALIZED product over the SAME OQL is created alongside it throughout, and every
 * assertion about VIRTUAL is paired with the opposite assertion about it. That pairing is
 * what makes the absences falsifiable: `refreshButton(virtual)` having count 0 proves nothing
 * on its own — a renamed testid would satisfy it too — but it means something next to
 * `refreshButton(materialized)` having count 1 in the same render.
 *
 * Setup is A2's proven two-source union (`acm` + `semantic` → `Paper`) rather than a bespoke
 * one: `SchemaIntegrator` only mints an integrated class from an alignment, so a lone source
 * has no class for a product's OQL to name (a1-single-csv.spec.ts's own note), and A2's
 * fixtures already carry the numeric `year` column the constraint below needs.
 */

/** The floor the constraint test 2 enforces. Every count below is derived against it. */
const YEAR_FLOOR = 2021;

/**
 * Every publication year in the union, read out of the fixtures themselves. A hardcoded count
 * would keep passing if a fixture's years changed underneath it — and the number of rows the
 * constraint removes is the entire signal test 2 reads.
 */
function unionYears(): number[] {
  const csv = readFileSync(fixturePath('acm_papers.csv'), 'utf8').trim().split('\n');
  const yearCol = csv[0].split(',').indexOf('year');
  expect(yearCol, 'acm_papers.csv must carry a `year` column').toBeGreaterThanOrEqual(0);
  const acm = csv.slice(1).map((line) => Number(line.split(',')[yearCol]));

  const json = JSON.parse(readFileSync(fixturePath('semantic_papers.json'), 'utf8')) as
    Array<{ pub_year: number }>;
  return [...acm, ...json.map((r) => r.pub_year)];
}

test('a VIRTUAL product is READY on creation, with no refresh to run', async ({
  journey, alignments, workbench, products,
}) => {
  await journey.bootstrapSources({
    project: 'v37-proj',
    repo: 'v37-files',
    sources: [
      { name: 'acm', file: 'acm_papers.csv' },
      { name: 'semantic', file: 'semantic_papers.json' },
    ],
  });

  await alignments.goto();
  await alignments.selectProject('v37-proj');
  await alignments.selectSources('acm', 'semantic');
  await alignments.addAlignment({
    leftProp: 'acm', rightProp: 'semantic', label: 'Paper', kind: 'class',
  });
  await alignments.addAlignment({
    leftProp: 'title', rightProp: 'name', label: 'title', kind: 'datatype',
  });
  await alignments.addAlignment({
    leftProp: 'year', rightProp: 'pub_year', label: 'year', kind: 'datatype',
  });
  // Same F12 workaround a2 needs — both sources carry a raw `author_ref` and
  // `OqlPlanner.requireProperty` throws on the local-name collision even for a query that
  // never mentions it. See a2-csv-json-union.spec.ts for the full reasoning.
  await alignments.addAlignment({
    leftProp: 'author_ref', rightProp: 'author_ref', label: 'author_ref', kind: 'datatype',
  });
  await alignments.integrate();
  await alignments.promote();

  await workbench.goto();
  await expect(async () => {
    expect(await workbench.catalogClassNames()).toContain('Paper');
  }).toPass({ timeout: 90_000 });

  await workbench.run('SELECT Paper.title, Paper.year FROM Paper');
  expect(await workbench.rows()).toHaveLength(EXPECTED.acmTitles.length + EXPECTED.semanticTitles.length);

  await products.saveCurrentQueryAsProduct({
    project: 'v37-proj', name: 'v37_virtual', accessMode: 'VIRTUAL',
    description: 'V37: expanded at read time',
  });
  // The same OQL, the other way: the control every assertion below is paired against.
  await workbench.goto();
  await workbench.run('SELECT Paper.title, Paper.year FROM Paper');
  await products.saveCurrentQueryAsProduct({
    project: 'v37-proj', name: 'v37_materialized', accessMode: 'MATERIALIZED',
    description: 'V37: control',
  });

  await products.goto('v37-proj');

  // Servable immediately: no materialize job ran, and none was offered.
  expect(await products.accessBadge('v37_virtual')).toBe('Virtual');
  await expect(products.row('v37_virtual')).toContainText('READY');
  // `recomputed on read`, not `never` — a VIRTUAL product has no last-refresh to be missing, and
  // the MATERIALIZED control (created and never materialized) is what `never` looks like. (It
  // said `always fresh` until the A1 truth pass, J6-F2: a virtual read is only as fresh as the
  // sources the active schema points at.)
  await expect(products.row('v37_virtual')).toContainText('recomputed on read');

  expect(await products.accessBadge('v37_materialized')).toBe('Snapshot');
  await expect(products.row('v37_materialized')).toContainText('DRAFT');
  await expect(products.row('v37_materialized')).toContainText('never');

  // The absence, and the presence that makes it mean something.
  await expect(products.refreshButton('v37_virtual')).toHaveCount(0);
  await expect(products.refreshButton('v37_materialized')).toHaveCount(1);

  // It really serves rows, with no snapshot behind it.
  expect(await products.previewRowCount('v37_virtual'))
    .toBe(EXPECTED.acmTitles.length + EXPECTED.semanticTitles.length);
});

/**
 * The gate: enforcing a denial constraint on the underlying class changes the VIRTUAL
 * product's NEXT read, and leaves the MATERIALIZED one alone.
 *
 * The constraint compares a numeric literal to a numeric column deliberately: a string
 * literal against a typed column crashes the planner outright once any constraint on that
 * class is enforced (V38), which would fail this spec for an unrelated reason.
 */
test('an enforced denial constraint reaches a VIRTUAL product on the very next read', async ({
  datasets, products,
}) => {
  const years = unionYears();
  const total = EXPECTED.acmTitles.length + EXPECTED.semanticTitles.length;
  expect(years).toHaveLength(total);
  const surviving = years.filter((y) => y >= YEAR_FLOOR).length;
  expect(surviving, 'the constraint must remove some rows, or enforcing it proves nothing')
    .toBeGreaterThan(0);
  expect(surviving).toBeLessThan(total);

  // Materialize the control NOW, while the constraint is still unenforced: its snapshot then
  // holds the pre-constraint rows, which is exactly what must NOT change below.
  await products.goto('v37-proj');
  await products.refreshButton('v37_materialized').click();
  await expect(products.row('v37_materialized')).toContainText('READY', { timeout: 180_000 });
  expect(await products.previewRowCount('v37_materialized')).toBe(total);

  await datasets.goto();
  await datasets.openConstraints('acm');
  expect(await datasets.constraintClasses()).toContain('Paper');
  await datasets.addConstraint(`year >= ${YEAR_FLOOR}`);
  await datasets.setConstraintEnforced(`year >= ${YEAR_FLOOR}`, true);

  // No re-materialize, no re-save, no promote — just read it again.
  await products.goto('v37-proj');
  expect(
    await products.previewRowCount('v37_virtual'),
    'a VIRTUAL product must honour a constraint enforced after it was created',
  ).toBe(surviving);
  expect(
    await products.previewRowCount('v37_materialized'),
    'a MATERIALIZED product serves its pinned snapshot and must be unaffected',
  ).toBe(total);

  // And back: the A/B in both directions, so a stuck cache cannot pass as enforcement.
  await datasets.goto();
  await datasets.openConstraints('acm');
  await datasets.setConstraintEnforced(`year >= ${YEAR_FLOOR}`, false);
  await products.goto('v37-proj');
  expect(await products.previewRowCount('v37_virtual')).toBe(total);
});

/**
 * V37: the dialog used to key its "no snapshot yet … Refresh it first" banner off `snapshotId`
 * being null, which is PERMANENTLY true for a VIRTUAL product. That was advice nobody could
 * follow, for a product the real `aran-mcp` sidecar reads fine (verified live with the official
 * MCP SDK client). The copy is now access-mode-aware: a VIRTUAL product gets the serves-live
 * note, never the refresh warning.
 */
test('the agents panel tells a VIRTUAL product the truth: ready now, nothing to refresh', async ({
  products,
}) => {
  await products.goto('v37-proj');
  await products.openUseWithAgents('v37_virtual');

  await expect(products.virtualLiveNote()).toBeVisible();
  await expect(products.virtualLiveNote()).toContainText('nothing to refresh');
  await expect(products.notMaterializedWarning()).toBeHidden();
  // The config a user needs is still offered.
  await expect(products.agentsDialog()).toContainText('ARAN_TOKEN');
});
