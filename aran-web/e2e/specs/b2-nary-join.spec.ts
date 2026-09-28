import { test, expect, specUser } from '../fixtures';
import { EXPECTED } from '../data';

test.use(specUser('b2'));

// Five sources and eleven alignments, each alignment two graph clicks against a
// D3 canvas that may still be simulating. Same reason A5 raises its ceiling.
test.describe.configure({ timeout: 10 * 60 * 1000 });

/**
 * B2 — the n-ary join. Three paper sources each contribute their own
 * `author_ref` to ONE join edge via JOIN alignments sharing the label
 * `authorKey`, so the single P_s carries three `nx:joinOnLeft` values. QUERY 2
 * of `MappingCatalogOperator` must collapse those into ONE catalog edge;
 * without it the planner rejects the repeated name as an ambiguous join
 * property. Regression test for b8a1a3e and for its follow-up ba3f921 (F22a:
 * pinning `?ip nx:JoinProperty ?joinProp`, which is what actually made three
 * sources on one edge collapse).
 *
 * WHY THIS IS THE SHARPEST TEST OF THAT COLLAPSE in the whole suite: the
 * corpus is arranged so the three paper sources reach DISJOINT researchers —
 * acm's authors are R-001..R-003 (`researchers.csv`), semantic's are R-004/R-005
 * and dblp's is R-006 (both `wikidata_researchers.json`). So a broken edge does
 * not merely lose a row or two; it loses whole sources, and the row count says
 * which. Ten rows means only acm reached the edge.
 *
 * Two additions beyond the core scenario, both forced by known findings:
 *
 * 1. F12 — `year` and `affiliation_ref` alignments the scenario has no other
 *    use for. `OqlPlanner.localToGlobal` builds a local-name map over EVERY
 *    property of the WHOLE class and throws the moment two distinct IRIs share
 *    a local name, even for a query that never mentions either. `acm.year` and
 *    `dblp.year` collide in Paper; `researchers.affiliation_ref` and
 *    `wikidata.affiliation_ref` collide in Researcher. Without these two
 *    alignments the query below cannot run at all — the same workaround F12
 *    records for A2, and the same one A5 needed for `year`. This is the third
 *    independent scenario to hit F12, which is worth noting: it is not an
 *    artefact of one fixture pair.
 *
 * 2. The `rid ≡ id` datatype alignment is labelled `authorKey` — the JOIN's own
 *    label — not a name of its own. This is load-bearing and is exactly what
 *    "gives the unioned Researcher class a SINGLE key column". `mintIri(label)`
 *    is deterministic in the label, so sharing it makes `wikidata.id` a
 *    sub-property of the very same P_l the join created; labelled anything else
 *    it lands on a second, separate integrated property, wikidata's rows sit in
 *    Researcher with a NULL `authorKey`, and the three papers whose authors live
 *    only in wikidata vanish. Proven here, not inherited: see the falsifiability
 *    note in the task report — relabelling this one alignment (and nothing else)
 *    takes the result from 13 rows to 10. That also settles the question A5's
 *    F22b notes left open, where a two-hop query masked the effect.
 */

/** Stable, comparable form for a whole result row. */
function rowKey(cells: readonly string[]): string {
  return cells.join(' | ');
}

test('three sources feed one join edge and it stays a single edge', async ({
  journey, alignments, workbench,
}) => {
  await test.step('Load five sources: three of papers and two of researchers', async () => {
    await journey.bootstrapSources({
      project: 'b2-proj',
      repo: 'b2-files',
      sources: [
        { name: 'acm', file: 'acm_papers.csv' },
        { name: 'semantic', file: 'semantic_papers.json' },
        { name: 'dblp', file: 'dblp_papers.json' },
        { name: 'researchers', file: 'researchers.csv' },
        { name: 'wikidata', file: 'wikidata_researchers.json' },
      ],
    });
  });

  await test.step('Open the alignments canvas for the project', async () => {
    await alignments.goto();
    await alignments.selectProject('b2-proj');
  });

  await test.step('Union the ACM and semantic paper sources into one Paper class', async () => {
    await alignments.selectSources('acm', 'semantic');
    await alignments.addAlignment({ leftProp: 'acm', rightProp: 'semantic', label: 'Paper', kind: 'class' });
    await alignments.addAlignment({ leftProp: 'title', rightProp: 'name', label: 'title', kind: 'datatype' });
    // F12, part 1 of 2 — see the class doc. `semantic.pub_year` does not itself
    // collide with anything; it is folded into the same integrated `year` so the
    // three-source union is semantically honest rather than merely unblocked.
    await alignments.addAlignment({ leftProp: 'year', rightProp: 'pub_year', label: 'year', kind: 'datatype' });
  });

  await test.step('Fold a third paper source, DBLP, into the same Paper class', async () => {
    await alignments.selectSources('acm', 'dblp');
    await alignments.addAlignment({ leftProp: 'acm', rightProp: 'dblp', label: 'Paper', kind: 'class' });
    await alignments.addAlignment({ leftProp: 'title', rightProp: 'title', label: 'title', kind: 'datatype' });
    // F12, the collision that actually bites: `acm.year` and `dblp.year` are two
    // distinct IRIs sharing the local name `year`, so without this EVERY query
    // against Paper throws — including one that never mentions a year.
    await alignments.addAlignment({ leftProp: 'year', rightProp: 'year', label: 'year', kind: 'datatype' });
  });

  await test.step('Union the two researcher sources into one Researcher class', async () => {
    await alignments.selectSources('researchers', 'wikidata');
    await alignments.addAlignment({ leftProp: 'researchers', rightProp: 'wikidata', label: 'Researcher', kind: 'class' });
    await alignments.addAlignment({ leftProp: 'full_name', rightProp: 'label', label: 'name', kind: 'datatype' });
    // Labelled with the JOIN's own label — deviation 2 in the class doc. This is
    // what unions the two researcher sources onto ONE key column.
    await alignments.addAlignment({ leftProp: 'rid', rightProp: 'id', label: 'authorKey', kind: 'datatype' });
    // F12, part 2 of 2: both researcher sources carry a raw `affiliation_ref`.
    // B2 has no second hop, so — unlike A5 — no join P_l absorbs either of them,
    // and the two raw IRIs collide on local name inside Researcher.
    await alignments.addAlignment({
      leftProp: 'affiliation_ref', rightProp: 'affiliation_ref',
      label: 'affiliation', kind: 'datatype',
    });
  });

  await test.step('Link all three paper sources to the same researchers', async () => {
    // The same label on all three, so they collapse onto one P_l/P_s pair.
    for (const left of ['acm', 'semantic', 'dblp']) {
      await alignments.selectSources(left, 'researchers');
      await alignments.addAlignment({
        leftProp: 'author_ref', rightProp: 'rid',
        label: 'authorKey', kind: 'join', relationship: 'hasAuthor',
      });
    }
  });

  await test.step('Integrate all five sources into one schema', async () => {
    await alignments.integrate();
  });

  await test.step('Publish the integrated schema', async () => {
    await alignments.promote();
  });

  await test.step('Open the query workbench', async () => {
    await workbench.goto();
  });

  const { paperClass, researcherClass } = await test.step('Find the Paper and Researcher classes in the catalog', async () => {
    // Both classes DID get a class alignment here, so unlike B1/B3 the labels
    // `Paper`/`Researcher` are the minted names. Still discovered from the live
    // catalog and then pinned, so the claim is tested rather than assumed.
    const classNames = await workbench.waitForCatalogClasses();
    const paperClass = classNames.find((n) => n === 'Paper');
    const researcherClass = classNames.find((n) => n === 'Researcher');
    expect(paperClass, `no "Paper" class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(researcherClass, `no "Researcher" class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(paperClass).toBe('Paper');
    expect(researcherClass).toBe('Researcher');
    return { paperClass, researcherClass };
  });

  await test.step('Query every paper with its author, across all five sources', async () => {
    await workbench.run(
      `SELECT ${paperClass}.title, ${researcherClass}.name ` +
      `FROM ${paperClass} -[hasAuthor]-> ${researcherClass}`,
    );
  });

  await test.step('Read back every paper and author, with all five sources represented', async () => {
    const rows = await workbench.rows();
    // The full corpus, not a slice: all three paper sources reach the one edge and
    // both researcher sources sit in the unioned class, so every (title, author)
    // pair the fixtures define comes back exactly once.
    expect(rows.length).toBe(EXPECTED.authorPairs.length);

    // One read, so the two columns are guaranteed to belong to the same row (F22b).
    // The exact pair set is what proves the edge threads the RIGHT researcher to
    // the right paper across all three sources — a count alone would survive any
    // permutation of authors.
    expect(rows.map(rowKey).sort()).toEqual(EXPECTED.authorPairs.map(rowKey).sort());

    // Per-source reachability, stated so a failure names the source that dropped
    // out instead of only a row count. Each of these researchers is reachable
    // through exactly one paper source (see the class doc's disjointness note).
    const names = rows.map(([, name]) => name);
    expect(names, 'acm did not reach the edge').toContain('Maria Garcia');
    expect(names, 'semantic did not reach the edge (wikidata author R-004)').toContain('Elena Rossi');
    expect(names, 'dblp did not reach the edge (wikidata author R-006)').toContain('Priya Nair');
  });
});
