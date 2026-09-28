import { test, expect, specUser } from '../fixtures';
import { EXPECTED } from '../data';

test.use(specUser('d1'));

// Every test builds on the state the one before it left: test 1 integrates and
// promotes, test 2 drifts the source, test 3 repairs it. `fullyParallel: true`
// would scatter them across workers and each would find a tenant that had never
// been set up.
test.describe.configure({ mode: 'serial' });

/**
 * D1 — schema drift.
 *
 * The defect this guards: re-bootstrapping a dataset used to clear its named
 * graph and write the new one unconditionally (`clearGraph` then `addTriples`,
 * no question asked). Because `BootstrapEngine.sourceClassIri` interpolates the
 * dataset NAME into every class and property IRI, renaming a dataset and
 * re-bootstrapping re-minted the entire source graph while the file underneath
 * was untouched — orphaning every `ModelMapping` that referenced the old IRIs,
 * with no warning. The break surfaced much later, as a compile-time
 * "not an rdf:Property" or a DuckDB binder error at query time.
 *
 * Renaming is therefore not a contrived way to force drift here: it is the one
 * drift case a user can reach entirely through the UI today, and it is exactly
 * the case that was silently destructive.
 *
 * NEVER EXECUTED — written without a Docker daemon, like `s1-query-stress`.
 * Every assertion is reasoned from the code rather than observed. On the first
 * real run, treat a failure as "the test is probably wrong" until proven
 * otherwise, and check the notes on each test below first.
 */

/** `EXPECTED.acmTitles` is a readonly tuple under `as const`; copy to use `includes`. */
const ACM_TITLES: string[] = [...EXPECTED.acmTitles];

/**
 * The join's answer, derived from the corpus rather than pasted — same basis as
 * B1: every ACM row's `author_ref` is a key `researchers.csv` defines, so the
 * join drops nothing and fans nothing out.
 */
const acmPairs = EXPECTED.authorPairs.filter(([title]) => ACM_TITLES.includes(title));

test('an unchanged source re-bootstraps without interrupting anyone', async ({
  journey, alignments, workbench, datasets, drift,
}) => {
  await test.step('Load two sources: papers and researchers', async () => {
    await journey.bootstrapSources({
      project: 'd1-proj',
      repo: 'd1-files',
      sources: [
        { name: 'acm', file: 'acm_papers.csv' },
        { name: 'researchers', file: 'researchers.csv' },
      ],
    });
  });

  await test.step('Link each paper to its author', async () => {
    // A join gives us ACCEPTED mappings that point INTO acm's source graph. Without
    // them the later drift would be real but harmless, and the gate would correctly
    // decline to fire — so this is what makes tests 2 and 3 able to fail at all.
    await alignments.goto();
    await alignments.selectProject('d1-proj');
    await alignments.selectSources('acm', 'researchers');
    await alignments.addAlignment({
      leftProp: 'author_ref', rightProp: 'rid',
      label: 'authorKey', kind: 'join', relationship: 'hasAuthor',
    });
  });
  await test.step('Integrate the two sources into one schema', async () => {
    await alignments.integrate();
  });

  await test.step('Publish the integrated schema', async () => {
    await alignments.promote();
  });

  const { acmClass, researchersClass } = await test.step('Open the query workbench and find the integrated classes', async () => {
    // Baseline the query BEFORE any drift, so tests 2 and 3 have a known-good
    // answer to compare against rather than asserting a shape in isolation.
    await workbench.goto();
    const classNames = await workbench.waitForCatalogClasses();
    const acmClass = classNames.find((n) => /acm/i.test(n));
    const researchersClass = classNames.find((n) => /researcher/i.test(n));
    expect(acmClass, `no acm-ish class in catalog: ${classNames.join(', ')}`).toBe('Int_acm');
    expect(researchersClass, `no researchers-ish class: ${classNames.join(', ')}`).toBe('Int_researchers');
    return { acmClass, researchersClass };
  });

  await test.step('Run a query and note its answer as the baseline', async () => {
    await workbench.run(
      `SELECT ${acmClass}.title, ${researchersClass}.full_name ` +
      `FROM ${acmClass} -[hasAuthor]-> ${researchersClass}`,
    );
    expect(await workbench.rows()).toHaveLength(acmPairs.length);
  });

  await test.step('Re-read the schema of a source nobody has touched — nothing has drifted', async () => {
    // Now the actual claim: re-bootstrapping a source nobody has touched is a
    // no-op decision. The first bootstrap recorded a fingerprint; this one matches
    // it, so the gate never opens and the write proceeds.
    await datasets.goto();
    await drift.openLocalGraph('acm');
    await drift.reBootstrapExpectingApply();
    await drift.closeLocalGraph();

    await drift.waitForStatus('acm', 'Ready');
  });
});

test('renaming a dataset breaks its mappings, and the gate refuses to write', async ({
  datasets, drift, workbench,
}) => {
  await test.step('Rename the dataset behind the source', async () => {
    await datasets.goto();

    // Re-mints every IRI in acm's source graph. Nothing about the FILE changes.
    await drift.rename('acm', 'acm-renamed');
  });

  await test.step('Re-read the source\'s schema and land in the review panel', async () => {
    await drift.openLocalGraph('acm-renamed');
    await drift.reBootstrapExpectingReview();
  });

  await test.step('Read the verdict: the rename would break what depends on it', async () => {
    // The verdict has to name the severity, not merely report that something moved:
    // the whole point of the impact analysis is that a change nobody mapped is INFO
    // and this one is not.
    expect(await drift.verdict()).toContain('BREAKING');
    expect(await drift.verdictReason()).toContain('break');

    // A dataset rename is ONE finding, not one per column. The diff keys classes on
    // their iterator precisely so this does not read as "every column dropped" —
    // if CLASS_IRI_CHANGED is missing and COLUMN_REMOVED rows appear instead, the
    // iterator-keying has regressed.
    const kinds = await drift.impactKinds();
    expect(kinds, `impact kinds were: ${kinds.join(', ')}`).toContain('CLASS_IRI_CHANGED');
    expect(kinds).not.toContain('COLUMN_REMOVED');
  });

  await test.step('Decline the change and keep the current schema', async () => {
    // Decline. This is the regression guard for the original defect.
    await drift.keepCurrentSchema();
    await drift.closeLocalGraph();
  });

  await test.step('See the source flagged as drifted rather than failed', async () => {
    // DRIFTED, not FAILED: nothing went wrong and there is nothing to retry.
    expect(await drift.statusBadge('acm-renamed')).toBe('SCHEMA DRIFT');
  });

  const { acmClass, researchersClass } = await test.step('Confirm the published schema is still intact', async () => {
    // And the part that actually mattered — the previous graph is still live, so
    // the query that worked in test 1 still works. Before this change the graph
    // would have been cleared and rewritten under new IRIs, and this query would
    // fail. An assertion that only checked the badge would pass even if the write
    // had gone ahead.
    await workbench.goto();
    const classNames = await workbench.waitForCatalogClasses();
    const acmClass = classNames.find((n) => /acm/i.test(n));
    const researchersClass = classNames.find((n) => /researcher/i.test(n));
    expect(acmClass, `catalog lost its acm class: ${classNames.join(', ')}`).toBeDefined();
    return { acmClass, researchersClass };
  });

  await test.step('Run the same query again — it still answers', async () => {
    await workbench.run(
      `SELECT ${acmClass}.title, ${researchersClass}.full_name ` +
      `FROM ${acmClass} -[hasAuthor]-> ${researchersClass}`,
    );
    expect(await workbench.rows()).toHaveLength(acmPairs.length);
  });
});

test('repairing the rename repoints the mappings and lets the write through', async ({
  datasets, drift,
}) => {
  await test.step('Re-read the renamed source\'s schema', async () => {
    await datasets.goto();
    await drift.openLocalGraph('acm-renamed');
    await drift.reBootstrapExpectingReview();
  });

  await test.step('See that the panel offers to repoint what the rename broke', async () => {
    // A re-minted class IRI carries a replacement, so the panel must offer to
    // repoint rather than only reporting the damage. If this is false the repair
    // path is unreachable and the only remaining option is "apply anyway", which
    // leaves the mappings dangling.
    expect(await drift.offersRepair()).toBe(true);
  });

  await test.step('Repair the mappings and let the new schema through', async () => {
    await drift.repairAndApply();
    await drift.closeLocalGraph();
  });

  await test.step('Wait for the source to come back to a ready graph', async () => {
    // The candidate is now live, so the row leaves DRIFTED. It goes back through
    // PENDING while the bootstrap job runs, which is why this waits rather than
    // reading the badge once.
    await drift.waitForStatus('acm-renamed', 'Ready');
  });

  await test.step('Re-read the schema once more: nothing is left to repair', async () => {
    // A second check now finds nothing: the repair advanced the baseline to the
    // shape that is actually live. If the fingerprint had been recorded at check
    // time rather than after applying, this would report drift against a schema
    // that was never installed.
    await drift.openLocalGraph('acm-renamed');
    await drift.reBootstrapExpectingApply();
    await drift.closeLocalGraph();
  });

  // NOTE, deliberately not asserted here: the OQL query from tests 1 and 2 does
  // NOT work at this point, and should not. Repair repoints the MAPPINGS, and
  // flags the model `rebuildRequired`, but the promoted integrated graph still
  // holds material derived from the old IRIs until it is recompiled. Asserting
  // the query works here would be asserting a bug. The re-integrate/promote loop
  // is B5's territory; what this test owns is that the repair happened at all.
});
