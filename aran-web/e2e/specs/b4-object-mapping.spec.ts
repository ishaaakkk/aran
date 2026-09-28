import { test, expect, specUser } from '../fixtures';
import { EXPECTED } from '../data';

test.use(specUser('b4'));

// Five alignments, each two clicks against a D3 canvas that may still be
// simulating, plus a full integrate/promote cycle and two queries. Same reason
// A5/B2/B3 raise their ceiling.
test.describe.configure({ timeout: 10 * 60 * 1000 });

/**
 * B4 — the OBJECT alignment. Nested JSON is what makes this reachable at all:
 * `JsonBootstrap.processObject` emits a child `rdfs:Class` per nested object and
 * gives the parent's property an `rdfs:range` pointing at it, which is the shape
 * `SchemaIntegrator.validateMCompliance` demands of an OBJECT alignment (domain
 * AND range). `semantic_papers.json`'s `venue` and `dblp_papers.json`'s
 * `conference` are exactly that shape, and nothing else in the corpus is.
 *
 * A FOURTH alignment is required, not optional. `SchemaIntegrator.integrateObjectProperty` requires both
 * RANGES to resolve to the SAME integrated class:
 *
 *     Resource rngA = integratedClassOf(m, rA, RDFS.range);
 *     Resource rngB = integratedClassOf(m, rB, RDFS.range);
 *     if (rngA == null || !rngA.equals(rngB)) return false;
 *
 * `integratedClassOf` returns null for a class with no IntegratedResource above
 * it, and nothing integrates the two child classes on its own. With only three
 * alignments the OBJECT alignment is therefore DEFERRED: it lands in the draft's
 * `unusedAlignments` report and no integrated object property is minted, while
 * every other assertion still passes. So B4 also aligns `semantic.venue` ≡ `dblp.conference` (CLASS, labelled `Venue`).
 * This is Algorithm 4's ordering, not a defect; recorded as D6.
 *
 * F12 recurrence (twice). Two datatype
 * alignments below exist ONLY to dodge it, exactly as B2 needed two of its own:
 *   - `Paper` unions raw `author_ref` from BOTH paper sources;
 *   - `Venue` unions raw `code` from BOTH nested classes.
 * Either collision makes every query against that class throw, including one
 * that never mentions the colliding name. Third and fourth independent
 * scenarios to hit F12.
 *
 * R6/D2 — `Paper` and `Venue` ARE class-alignment labels here, so unlike B1/B3
 * those are the minted names. Still discovered from the live catalog and then
 * pinned, so the naming claim is tested rather than assumed.
 */

/**
 * The whole expected answer, composed from the corpus rather than pasted. B4
 * registers the two nested-JSON sources and nothing else, so `Paper` is exactly
 * their union: two semantic papers and one dblp paper.
 */
const expectedTitles: string[] = [...EXPECTED.semanticTitles, ...EXPECTED.dblpTitles];

/**
 * The venue codes those same papers carry, in the same corpus-derived spirit.
 * Written here rather than derived from `EXPECTED` because `EXPECTED` has no
 * venue table — `corpus.spec.ts` does not cross-derive these, so they are
 * grounded on the fixture files directly (`semantic_papers.json`: V-01, V-03;
 * `dblp_papers.json`: V-02), the same way B3 grounds its country map on
 * `init.sql`.
 */
const expectedVenueCodes: string[] = ['V-01', 'V-02', 'V-03'];

test('object properties from two nested JSON sources align', async ({
  journey, alignments, workbench,
}) => {
  await test.step('Load two nested JSON sources of papers', async () => {
    await journey.bootstrapSources({
      project: 'b4-proj',
      repo: 'b4-files',
      sources: [
        { name: 'semantic', file: 'semantic_papers.json' },
        { name: 'dblp', file: 'dblp_papers.json' },
      ],
    });
  });

  await test.step('Open the alignments canvas for the project', async () => {
    await alignments.goto();
    await alignments.selectProject('b4-proj');
  });

  await test.step('Put the two source schemas side by side', async () => {
    await alignments.selectSources('semantic', 'dblp');
  });

  await test.step('Declare that both sources describe the same thing: a Paper', async () => {
    // ── the parent classes ────────────────────────────────────────────────────
    await alignments.addAlignment({
      leftProp: 'semantic', rightProp: 'dblp', label: 'Paper', kind: 'class',
    });
  });

  await test.step('Match up the paper properties that mean the same thing', async () => {
    // `dblp` renders TWO nodes labelled `title` — the top-level one and
    // `conference.title` (F18). `clickGraphNode` resolves the ambiguity by IRI
    // length and the nested one is always longer (it carries an extra `.conference`
    // path segment), so this reliably picks the top-level property. That is also
    // why the nested venue label is NOT aligned below: the page object cannot
    // address the longer of two same-labelled nodes, and B4 does not need it.
    await alignments.addAlignment({
      leftProp: 'name', rightProp: 'title', label: 'title', kind: 'datatype',
    });
    // F12, part 1 of 2 — both paper sources carry a raw `author_ref`. B4 has no
    // join, so nothing else absorbs either of them into a shared integrated
    // property and the two raw IRIs collide on local name inside `Paper`.
    await alignments.addAlignment({
      leftProp: 'author_ref', rightProp: 'author_ref', label: 'authorRef', kind: 'datatype',
    });
  });

  await test.step('Declare that the nested venue and conference are the same thing: a Venue', async () => {
    // ── the nested classes ────────────────────────────────────────────────────
    // Node labels are `localName(iri)` and a child class's IRI is
    // `…/<datasetId>/<parentPath>.<key>` (BootstrapEngine.sourceClassIri), so the
    // child class is labelled `semantic.venue`, NOT `venue` — which is precisely
    // what keeps it distinguishable from the object PROPERTY node `venue`
    // (`…/<datasetId>/semantic/venue`) clicked further down.
    await alignments.addAlignment({
      leftProp: 'semantic.venue', rightProp: 'dblp.conference', label: 'Venue', kind: 'class',
    });
  });

  await test.step('Match up the venue code each nested record carries', async () => {
    // F12, part 2 of 2 — both nested classes carry a raw `code`.
    await alignments.addAlignment({
      leftProp: 'code', rightProp: 'code', label: 'venueCode', kind: 'datatype',
    });
  });

  await test.step('Link a paper to the venue it was published at', async () => {
    // ── the object property itself ────────────────────────────────────────────
    // Domain `Paper` on both sides, range `Venue` on both sides: the only
    // configuration in which `integrateObjectProperty` applies rather than defers.
    await alignments.addAlignment({
      leftProp: 'venue', rightProp: 'conference', label: 'publishedAt', kind: 'object',
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

  const { paperClass, venueClass } = await test.step('Find the Paper and Venue classes in the catalog', async () => {
    const classNames = await workbench.waitForCatalogClasses();
    const paperClass = classNames.find((n) => n === 'Paper');
    const venueClass = classNames.find((n) => n === 'Venue');
    expect(paperClass, `no "Paper" class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(venueClass, `no "Venue" class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(paperClass).toBe('Paper');
    expect(venueClass).toBe('Venue');
    return { paperClass, venueClass };
  });

  await test.step('Query the papers from both sources', async () => {
    // The parent union, so a failure of the nested half below can be attributed to
    // the nesting rather than to unioning two JSON sources as such.
    await workbench.run(`SELECT ${paperClass}.title FROM ${paperClass}`);
    const paperRows = await workbench.rows();
    expect(paperRows.map(([title]) => title).sort()).toEqual([...expectedTitles].sort());
  });

  await test.step('Query the venues, drawn from two differently shaped JSON trees', async () => {
    // The nested classes really are one integrated class over two sources — three
    // venue codes from two differently-shaped JSON trees (`$[*].venue` and
    // `$[*].conference`), which only holds if both child wrappers execute and both
    // `code` columns resolve to the one integrated property.
    await workbench.run(`SELECT ${venueClass}.venueCode FROM ${venueClass}`);
    const venueRows = await workbench.rows();
    expect(venueRows.map(([code]) => code).sort()).toEqual([...expectedVenueCodes].sort());
  });

  await test.step('Confirm the catalog now shows papers published at venues', async () => {
    // THE POINT OF B4. Everything above would still pass with the OBJECT alignment
    // deferred — `Paper` and `Venue` both come from CLASS alignments and neither
    // needs `publishedAt` to exist. This is the only assertion that fails when the
    // object alignment does not integrate: `MappingCatalogOperator`'s QUERY 3 only
    // emits a relationship for an `nx:IntegratedResource` property whose domain and
    // range are both classes, which is exactly what `applyPropertyInvariants` mints
    // for an APPLIED object alignment and nothing else does.
    //
    // Asserted as the COMPLETE list, not a `toContain`: B4 declares no join, so one
    // relationship — this one — is the whole truth. A second entry would mean the
    // integrator minted something extra (an object property double-counted as a
    // join is exactly the bug QUERY 3's `FILTER NOT EXISTS` guards against).
    const relationships = await workbench.catalogRelationships();
    expect(relationships).toEqual([{ name: 'publishedAt', from: 'Paper', to: 'Venue' }]);
  });
});
