import { test, expect, specUser } from '../fixtures';
import { EXPECTED } from '../data';

test.use(specUser('b1'));

/**
 * B1 — the plain case: ONE join edge between two classes, and nothing else. No
 * class alignment, no datatype alignment, no union: the minimal shape in which
 * a join can exist at all, so that when B2 (n-ary) or B3 (multi-hop) fails, the
 * failure can be attributed to the extra shape rather than to joining as such.
 *
 * R6/D2 — the class names are NOT `Acm`/`Researchers`. An integrated class is
 * named after the alignment LABEL that minted it, and neither source here is the
 * target of a CLASS alignment: only the join touches them, so `SchemaIntegrator`
 * auto-promotes each as `Int_<sourceClassLocalName>`. The names are read off the
 * live catalog below (never hardcoded into the OQL) AND asserted exactly, so this
 * goes red if the auto-promote convention changes rather than silently querying
 * whatever happens to be there. Same pattern as A3 and A5.
 *
 * No F12 hazard here, and that is a property of the scenario rather than luck:
 * each integrated class has exactly one source, so no two raw columns can collide
 * on a local name. B2 has to work around F12 explicitly; see its own notes.
 */

// `EXPECTED.acmTitles` is a readonly tuple of string literals under `as const`,
// so `includes(aString)` does not type-check against it directly. Copy it.
const ACM_TITLES: string[] = [...EXPECTED.acmTitles];

/**
 * Derived from the corpus, never pasted. B1 registers `acm_papers.csv` and
 * `researchers.csv` only — no wikidata — so the join's answer is exactly the ACM
 * slice of `EXPECTED.authorPairs`: every ACM row's `author_ref` is an R-001..R-003
 * key that `researchers.csv` defines, so the join drops nothing and fans nothing
 * out, giving one output row per ACM row. `corpus.spec.ts` cross-derives
 * `EXPECTED.authorPairs` from the fixture files themselves, so this stays true if
 * a fixture row is added — the count follows the corpus instead of a literal.
 *
 * (Today: ten pairs over three distinct researchers. Neither number is written
 * down anywhere below.)
 */
const acmPairs = EXPECTED.authorPairs.filter(([title]) => ACM_TITLES.includes(title));

/** Stable, comparable form for a whole result row. */
function rowKey(cells: readonly string[]): string {
  return cells.join(' | ');
}

test('a single join edge across two classes', async ({ journey, alignments, workbench }) => {
  await test.step('Load two sources: papers and researchers', async () => {
    await journey.bootstrapSources({
      project: 'b1-proj',
      repo: 'b1-files',
      sources: [
        { name: 'acm', file: 'acm_papers.csv' },
        { name: 'researchers', file: 'researchers.csv' },
      ],
    });
  });

  await test.step('Open the alignments canvas for the project', async () => {
    await alignments.goto();
    await alignments.selectProject('b1-proj');
  });

  await test.step('Put the two source schemas side by side', async () => {
    await alignments.selectSources('acm', 'researchers');
  });

  await test.step('Link each paper to its author', async () => {
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

  await test.step('Open the query workbench', async () => {
    await workbench.goto();
  });

  const { acmClass, researchersClass } = await test.step('Find the new integrated classes in the catalog', async () => {
    // Discover the real class names, then pin them. `find` alone would leave the
    // spec green against ANY name containing "acm"; the `toBe` is what makes the
    // naming convention itself a tested claim.
    const classNames = await workbench.waitForCatalogClasses();
    const acmClass = classNames.find((n) => /acm/i.test(n));
    const researchersClass = classNames.find((n) => /researcher/i.test(n));
    expect(acmClass, `no acm-ish class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(researchersClass, `no researchers-ish class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(acmClass).toBe('Int_acm');
    expect(researchersClass).toBe('Int_researchers');
    return { acmClass, researchersClass };
  });

  await test.step('Query across the link between papers and researchers', async () => {
    // A join-only integrated class keeps its source's own local property names —
    // nothing renamed them, because nothing aligned them.
    await workbench.run(
      `SELECT ${acmClass}.title, ${researchersClass}.full_name ` +
      `FROM ${acmClass} -[hasAuthor]-> ${researchersClass}`,
    );
  });

  await test.step('Read back every paper beside its author', async () => {
    const rows = await workbench.rows();
    expect(rows.length).toBe(acmPairs.length);

    // The exact (title, researcher) pairs, read in ONE pass so row order is
    // guaranteed to line the two columns up. Zipping two separate `column()` reads
    // and sorting one of them in place is how F22b faked a broken join: each
    // column's multiset was right while every pair was wrong.
    expect(rows.map(rowKey).sort()).toEqual(acmPairs.map(rowKey).sort());

    // Redundant with the pair check above only while the join is correct — and
    // that is the point: it names the researcher set independently, so a
    // regression that drops one researcher entirely says so directly.
    const names = rows.map(([, name]) => name);
    expect([...new Set(names)].sort()).toEqual([...new Set(acmPairs.map(([, n]) => n))].sort());
  });
});
