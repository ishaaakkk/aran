import { test, expect, specUser } from '../fixtures';
import { EXPECTED } from '../data';

test.use(specUser('b5'));

// Two full integrate/promote cycles, a mid-spec dataset registration and four
// workbench queries. Same reason A5/B2/B3 raise their ceiling.
test.describe.configure({ timeout: 10 * 60 * 1000 });

/**
 * B5 — incremental integration on top of a promoted baseline, and the picker
 * that guards it.
 *
 * Three regressions are guarded here: promotion must go through the model-aware
 * endpoint (the generic graph promote leaves the run in DRAFT so no baseline ever
 * forms, so `incrementalAdd` would fail NO_BASELINE); the picker must hide
 * sources the baseline already integrated; and the incremental add must
 * not disturb the join the baseline established.
 *
 * `wikidata` is registered and enrolled AFTER the baseline is promoted. It has to be:
 * `IntegrationModelService.rebuildToDraft` sets `sourceDatasetIds` to EVERY
 * enrolled dataset, and `SchemaIntegrationOperator.runIntegration` unions every
 * one of those source graphs into the draft while stamping `nx:provenance` on
 * each source class. `integratedDatasetIds` then reads those very literals back
 * out of the active graph and `AlignmentsView`'s `addableDatasetIds` subtracts
 * them from the picker. So an enrolled-but-unaligned source is ALREADY in the
 * promoted baseline (as an isolated class) and is correctly never offered again:
 * enrolled up front, the picker would be empty and the incremental add
 * impossible. Enrolling late is also the real incremental case: a source that
 * arrives after the baseline is live. See D5.
 *
 * F12 recurrence: `researchers.csv` and `wikidata_researchers.json` both carry a
 * raw `affiliation_ref`, so unioning them into one class collides on local name
 * and makes EVERY query against that class throw. The `affiliation` alignment
 * below exists only to dodge that, exactly as B2 needed one for the same pair.
 * (Fourth independent scenario to hit F12 — not a new finding, a recurrence.)
 *
 * R6/D2 — the class names. The baseline declares ONLY a join alignment, so both
 * sources are auto-promoted `Int_<sourceClassLocalName>`. The incremental step
 * then declares a CLASS alignment labelled `Researchers` between `researchers`
 * and `wikidata`, and that label is DISCARDED: `SchemaIntegrator.integrateClass`
 * sees `researchers` already has an effective IntegratedResource (`Int_researchers`,
 * from the join auto-promotion in the baseline) and `wikidata` has none, which is
 * invariant I3 — `m.add(rB, RDFS.subClassOf, effA)`, reusing the existing IR and
 * never reading `a.label()`. So `wikidata` folds into `Int_researchers`, no
 * `Researchers` class is ever minted, and no second researcher class appears.
 * Verified against the live catalog below and pinned there; see D7.
 */

/** Stable, comparable form for a whole result row. */
function rowKey(cells: readonly string[]): string {
  return cells.join(' | ');
}

// `EXPECTED.acmTitles` is a readonly tuple of literals under `as const`.
const ACM_TITLES: string[] = [...EXPECTED.acmTitles];

/**
 * The baseline join's whole expected answer, derived exactly as B1 derives it:
 * every ACM row's `author_ref` is an R-001..R-003 key that `researchers.csv`
 * defines, so the join drops nothing and fans nothing out. Ten pairs over three
 * distinct researchers today; neither number is written down.
 */
const acmPairs = EXPECTED.authorPairs.filter(([title]) => ACM_TITLES.includes(title));

/** The three researchers `researchers.csv` defines — the ACM papers' authors. */
const csvResearchers = [...new Set(acmPairs.map(([, name]) => name))].sort();

/**
 * The three `wikidata_researchers.json` defines (R-004..R-006) — reached through
 * the semantic and dblp papers, which B5 never registers. Derived as the
 * complement of the ACM slice so the two sets cannot drift apart.
 */
const jsonResearchers = [
  ...new Set(
    EXPECTED.authorPairs.filter(([title]) => !ACM_TITLES.includes(title)).map(([, name]) => name),
  ),
].sort();

/** Both sources' researchers — what the class must hold once wikidata is folded in. */
const allResearchers = [...new Set([...csvResearchers, ...jsonResearchers])].sort();

/**
 * Cross-check against a SECOND, independently-written corpus table. If a fixture
 * row is ever added to one researcher source and not to `affiliationPairs`, this
 * throws at load rather than letting B5 assert a stale researcher set — the same
 * guard B3 puts on its affiliation composition.
 */
{
  const fromAffiliations = [...new Set(EXPECTED.affiliationPairs.map(([name]) => name))].sort();
  if (rowKey(allResearchers) !== rowKey(fromAffiliations)) {
    throw new Error(
      `EXPECTED has drifted: authorPairs names [${allResearchers.join(', ')}] ` +
      `!= affiliationPairs names [${fromAffiliations.join(', ')}]`,
    );
  }
  if (csvResearchers.length === allResearchers.length) {
    throw new Error(
      'B5 is inert: the CSV researchers already are every researcher, so folding ' +
      'wikidata in cannot change the answer. Fix the corpus, not this spec.',
    );
  }
}

/**
 * This is the suite's regression guard for F24, and the only
 * scenario that promotes TWICE in one tenant — i.e. the only one that promotes over a non-empty
 * `urn:aran:schema`, which was the case that silently corrupted it.
 *
 * Before the fix, measured directly against the tenant's Fuseki dataset:
 *   after baseline promote     urn:aran:schema = 84    catalog QUERY 1 -> 2 classes
 *   after incremental draft    version graph   = 123   (the draft was always complete)
 *   after incremental promote  urn:aran:schema = 62    catalog QUERY 1 -> 0 classes
 * The 61 lost triples included EVERY subject in the `nx:integration/` namespace, so
 * `?intClass a nx:IntegratedResource ; a rdfs:Class` matched nothing and the catalog emptied,
 * while the UI reported `schema v2 · active` beside `0 cls · 0 rel` and raised no error.
 *
 * `FusekiGraphStoreAdapter.promote` now reads the draft into a Model and PUTs it, instead of a
 * same-dataset `CLEAR; COPY`, and verifies the promoted size against the draft's. The final
 * assertions below are what proves the graph survives a second promote — if promotion ever
 * truncates again, this goes red here rather than in production.
 */
test('add a source incrementally onto a promoted baseline', async ({
  journey, datasets, alignments, workbench,
}) => {
  await test.step('Load two sources: papers and researchers', async () => {
    await journey.bootstrapSources({
      project: 'b5-proj',
      repo: 'b5-files',
      sources: [
        { name: 'acm', file: 'acm_papers.csv' },
        { name: 'researchers', file: 'researchers.csv' },
      ],
    });
  });

  await test.step('Link each paper to its author', async () => {
    // ── baseline: papers joined to the CSV researchers only ────────────────────
    await alignments.goto();
    await alignments.selectProject('b5-proj');
    await alignments.selectSources('acm', 'researchers');
    await alignments.addAlignment({
      leftProp: 'author_ref', rightProp: 'rid',
      label: 'authorKey', kind: 'join', relationship: 'hasAuthor',
    });
  });
  await test.step('Integrate the two sources into a baseline schema', async () => {
    await alignments.integrate();
  });

  await test.step('Publish the baseline schema', async () => {
    await alignments.promote();
  });

  await test.step('Open the query workbench', async () => {
    await workbench.goto();
  });

  const { baselineClasses, acmClass, researcherClass } = await test.step('Find the baseline classes in the catalog', async () => {
    const baselineClasses = await workbench.waitForCatalogClasses();
    const acmClass = baselineClasses.find((n) => /acm/i.test(n));
    const researcherClass = baselineClasses.find((n) => /researcher/i.test(n));
    expect(acmClass, `no acm-ish class in catalog: ${baselineClasses.join(', ')}`).toBeDefined();
    expect(researcherClass, `no researchers-ish class in catalog: ${baselineClasses.join(', ')}`).toBeDefined();
    expect(acmClass).toBe('Int_acm');
    expect(researcherClass).toBe('Int_researchers');
    return { baselineClasses, acmClass, researcherClass };
  });

  const joinQuery =
    `SELECT ${acmClass}.title, ${researcherClass}.full_name ` +
    `FROM ${acmClass} -[hasAuthor]-> ${researcherClass}`;
  // Identical text before and after the incremental add: `full_name` is the raw
  // CSV column now and the integrated property's local name afterwards, so the
  // query is unchanged and only the DATA may differ. That is what makes the
  // before/after comparison meaningful rather than a comparison of two queries.
  const researcherQuery = `SELECT ${researcherClass}.full_name FROM ${researcherClass}`;

  await test.step('List the researchers the baseline knows about', async () => {
    // THE DISCRIMINATOR, first half. The ACM join alone cannot detect this
    // scenario: wikidata contributes R-004..R-006, who
    // author no ACM paper, so an ACM-only query returns byte-identical results
    // whether the incremental add worked perfectly or did nothing. Querying the
    // researcher class directly is the assertion that moves — three now, six later.
    await workbench.run(researcherQuery);
    const baselineNames = (await workbench.rows()).map(([name]) => name);
    expect([...baselineNames].sort()).toEqual(csvResearchers);
  });

  await test.step('Query papers with their authors, as the baseline answers it', async () => {
    await workbench.run(joinQuery);
    const baselineRows = await workbench.rows();
    expect(baselineRows.length).toBe(acmPairs.length);
    expect(baselineRows.map(rowKey).sort()).toEqual(acmPairs.map(rowKey).sort());
  });

  await test.step('See which sources are still available to add', async () => {
    // ── the picker must not re-offer what the baseline already integrated ──────
    await alignments.goto();
    await alignments.selectProject('b5-proj');
    const beforeEnrolment = await alignments.incrementalOptions();
    // da739f5's regression guard. Both baseline sources are in the active graph, so
    // neither may be offered; nothing else is enrolled yet, so the picker holds only
    // its placeholder.
    expect(beforeEnrolment.join(' '), 'acm is already in the baseline').not.toContain('acm');
    expect(beforeEnrolment.join(' '), 'researchers is already in the baseline').not.toContain('researchers');
  });

  await test.step('Register a new researcher source after the baseline is live', async () => {
    // ── a new source arrives after the baseline is live ────────────────────────
    await datasets.goto();
    await datasets.registerFile('b5-proj', 'b5-files', 'wikidata', 'wikidata_researchers.json');
    await datasets.enrolInModel('wikidata');
  });

  await test.step('See the new source offered as available to add', async () => {
    await alignments.goto();
    await alignments.selectProject('b5-proj');
    const afterEnrolment = await alignments.incrementalOptions();
    expect(afterEnrolment.join(' '), 'the newly enrolled source is not addable').toContain('wikidata');
    expect(afterEnrolment.join(' '), 'acm must still be filtered out').not.toContain('acm');
    expect(afterEnrolment.join(' '), 'researchers must still be filtered out').not.toContain('researchers');
  });

  await test.step('Put the existing researchers beside the new source', async () => {
    // ── incremental: fold the JSON researchers into the same class ─────────────
    await alignments.selectSources('researchers', 'wikidata');
  });

  await test.step('Match the new source\'s properties onto the ones already integrated', async () => {
    await alignments.addAlignment({
      leftProp: 'researchers', rightProp: 'wikidata', label: 'Researchers', kind: 'class',
    });
    await alignments.addAlignment({
      leftProp: 'full_name', rightProp: 'label', label: 'full_name', kind: 'datatype',
    });
    // F12 — see the class doc. Nothing in the scenario wants this alignment.
    await alignments.addAlignment({
      leftProp: 'affiliation_ref', rightProp: 'affiliation_ref',
      label: 'affiliation', kind: 'datatype',
    });
    // The new source must feed the SAME key column the join reads, or its rows sit
    // in the class with a NULL key. Labelled with the join's own label, matching
    // B2 — though here the label is inert rather than load-bearing: `rid` already
    // has `authorKey` as its integrated parent from the baseline, so
    // `applyPropertyInvariants` takes the I3 branch (`m.add(rB, subPropertyOf,
    // effA)`) and never reads the label. Kept aligned with B2 so the two scenarios
    // do not appear to disagree about what unions a key column.
    await alignments.addAlignment({
      leftProp: 'rid', rightProp: 'id', label: 'authorKey', kind: 'datatype',
    });
  });

  await test.step('Add the new source to the live schema', async () => {
    await alignments.incrementalAdd('wikidata');
  });

  await test.step('Publish the updated schema', async () => {
    await alignments.promote();
  });

  await test.step('Return to the query workbench', async () => {
    await workbench.goto();
  });

  await test.step('Confirm no second researcher class appeared alongside the first', async () => {
    // I3, asserted rather than assumed — see the class doc. The `Researchers` label
    // is discarded and the auto-promoted baseline class absorbs wikidata, so the
    // catalog must hold the SAME two classes it held before, and in particular must
    // NOT have grown a second researcher class alongside the first.
    const finalClasses = await workbench.waitForCatalogClasses();
    expect([...finalClasses].sort()).toEqual([...baselineClasses].sort());
    expect(finalClasses.filter((n) => /researcher/i.test(n))).toEqual([researcherClass]);
    expect(finalClasses).not.toContain('Researchers');
  });

  await test.step('List the researchers again — now both sources are represented', async () => {
    // THE DISCRIMINATOR, second half. Same query text as before the add; three
    // researchers becomes six. This is the assertion that goes red if the
    // incremental step silently does nothing.
    await workbench.run(researcherQuery);
    const finalNames = (await workbench.rows()).map(([name]) => name);
    expect([...finalNames].sort()).toEqual(allResearchers);
  });

  await test.step('Confirm the original paper-to-author answer is unchanged', async () => {
    // …and the baseline's join is untouched by the add — a real regression risk,
    // since the union changes what the join's right-hand class contains. Still ten
    // rows and still the SAME ten pairs: wikidata's researchers now sit in the
    // class but key on R-004..R-006, which no ACM paper references, so nothing new
    // matches and nothing existing may be lost or duplicated.
    await workbench.run(joinQuery);
    const finalRows = await workbench.rows();
    expect(finalRows.length).toBe(acmPairs.length);
    expect(finalRows.map(rowKey).sort()).toEqual(acmPairs.map(rowKey).sort());
    // Stated separately so a failure names it: the join answer must contain only
    // CSV researchers even though the class now holds six.
    expect([...new Set(finalRows.map(([, name]) => name))].sort()).toEqual(csvResearchers);
  });
});
