import { test, expect, specUser } from '../fixtures';
import { EXPECTED } from '../data';

test.use(specUser('a2'));

// The second test queries the schema the first one promotes.
test.describe.configure({ mode: 'serial' });

/**
 * A2 — a CSV and a JSON source unioned into one integrated class, with the
 * datatype alignments doing real renaming work: title/name and year/pub_year.
 *
 * R7: acm_papers.csv has TEN rows (EXPECTED.acmTitles) and semantic_papers.json
 * has two (EXPECTED.semanticTitles). Every count below is derived from EXPECTED
 * instead of being written in by hand.
 */
test('CSV and JSON union under datatype renames', async ({
  journey, alignments, workbench,
}) => {
  await test.step('Load two sources: a CSV of papers and a JSON feed of papers', async () => {
    await journey.bootstrapSources({
      project: 'a2-proj',
      repo: 'a2-files',
      sources: [
        { name: 'acm', file: 'acm_papers.csv' },
        { name: 'semantic', file: 'semantic_papers.json' },
      ],
    });
  });

  await test.step('Put the two source schemas side by side on the alignments canvas', async () => {
    await alignments.goto();
    await alignments.selectProject('a2-proj');
    await alignments.selectSources('acm', 'semantic');
  });

  await test.step('Declare that both sources describe the same thing: a Paper', async () => {
    await alignments.addAlignment({
      leftProp: 'acm', rightProp: 'semantic', label: 'Paper', kind: 'class',
    });
  });

  await test.step('Match up the properties that mean the same thing under different names', async () => {
    await alignments.addAlignment({
      leftProp: 'title', rightProp: 'name', label: 'title', kind: 'datatype',
    });
    await alignments.addAlignment({
      leftProp: 'year', rightProp: 'pub_year', label: 'year', kind: 'datatype',
    });
    // WORKAROUND for F12, not a scenario
    // requirement: acm and semantic both carry a raw `author_ref` column, and
    // neither side aligns it. `OqlPlanner.requireProperty` builds a local-name
    // map over EVERY property of the whole class before looking up the one
    // that was actually asked for, and throws the instant two entries collide —
    // so without this alignment, `SELECT Paper.title FROM Paper` itself throws
    // "Class 'Paper' has two properties sharing local name 'author_ref'.", even
    // though the query never references author_ref. Confirmed by removing this
    // block and rerunning: that is exactly the error produced. Aligning it is
    // also semantically sound here — both sides' author_ref draw from the same
    // researcher-key domain (corpus.spec.ts) — but it exists in this spec
    // purely to unblock the class, not to test anything about author_ref.
    await alignments.addAlignment({
      leftProp: 'author_ref', rightProp: 'author_ref', label: 'author_ref', kind: 'datatype',
    });
  });

  await test.step('Integrate the two sources into one schema', async () => {
    await alignments.integrate();
  });

  await test.step('Publish the integrated schema', async () => {
    await alignments.promote();
  });

  await test.step('Open the query workbench', async () => {
    await workbench.goto();
  });

  await test.step('Find the new Paper class in the catalog', async () => {
    // R6/D2: a CLASS alignment mints the integrated class from its label, so this
    // one really should be named "Paper" — verified against the live catalog
    // rather than assumed. `OqlPlanView` fetches the catalog once on mount, async,
    // so this polls rather than reading immediately after `goto()` (see
    // `WorkbenchPage.catalogClassNames` — deliberately a single non-retrying
    // read; the retry belongs at the call site per `workbench-contract.spec.ts`).
    // Generous timeout, matching alignments.integrate()/promote()'s own
    // 180s/120s windows: under the full suite's 4-way worker concurrency, the
    // single shared backend can queue this GET behind other workers' heavy
    // operations for well past 30s even though promote() has already returned.
    // 30s was enough running this spec alone but flaked under full-suite load.
    await expect(async () => {
      expect(await workbench.catalogClassNames()).toContain('Paper');
    }).toPass({ timeout: 90_000 });
  });

  await test.step('Query every paper by title, across both sources at once', async () => {
    await workbench.run('SELECT Paper.title FROM Paper');
  });

  await test.step('Read back titles from the CSV and the JSON under one property', async () => {
    const titles = await workbench.column('title');
    // Rows from both sources, under the single renamed property. Length AND
    // set-equality against EXPECTED, not a hardcoded count.
    const expectedTitles = [...EXPECTED.acmTitles, ...EXPECTED.semanticTitles];
    expect(titles).toHaveLength(expectedTitles.length);
    expect(titles.sort()).toEqual(expectedTitles.sort());
  });
});

test('the renamed year property carries values from both sources', async ({ workbench }) => {
  await test.step('Query papers published before 2022 using the renamed year', async () => {
    await workbench.goto();
    await workbench.run('SELECT Paper.title, Paper.year FROM Paper WHERE Paper.year < 2022');
  });

  await test.step('Read back early papers from both sources', async () => {
    const titles = await workbench.column('title');
    // Fixture-grounded, not plan-grounded: acm_papers.csv's `year` column and
    // semantic_papers.json's `pub_year` column give the exact pre-2022 set below
    // (ACM: P-002/2021, P-004/2020, P-005/2019, P-006/2021, P-008/2018, P-010/2020;
    // semantic: SS001/2020, SS002/2021 — both). A rename that only worked one
    // direction (CSV OR JSON, not both) would fail this length check.
    expect(titles).toHaveLength(8);
    expect(titles).toContain('Schema Integration Revisited');   // from the CSV
    expect(titles).toContain('Provenance-Aware Query Rewriting'); // from the CSV
    expect(titles).toContain('Approximate Join Cardinality Estimation'); // from the CSV
    expect(titles).toContain('Incremental View Maintenance at Scale'); // from the CSV
    expect(titles).toContain('Cost Models for Federated Wrappers'); // from the CSV
    expect(titles).toContain('Benchmarking Join Discovery Algorithms'); // from the CSV
    expect(titles).toContain('Ontology-Based Data Integration'); // from the JSON
    expect(titles).toContain('Reasoning over Knowledge Graphs'); // from the JSON
  });
});
