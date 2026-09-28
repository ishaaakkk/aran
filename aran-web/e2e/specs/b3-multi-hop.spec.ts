import { test, expect, specUser } from '../fixtures';
import { EXPECTED } from '../data';

test.use(specUser('b3'));

// Two file sources plus an M9 Postgres import, then integration and a two-hop
// query. Comfortably inside 10 minutes; the default 5 leaves no headroom for the
// import when four workers share the machine.
test.describe.configure({ timeout: 10 * 60 * 1000 });

/**
 * B3 — two join edges chained across three classes: B1 plus a second hop onto
 * the Postgres-imported `affiliations` table. Deliberately B1's exact source set
 * for the first hop (acm + `researchers.csv`, no wikidata, no class alignments),
 * so anything B3 shows that B1 does not is attributable to the SECOND hop alone.
 *
 * The middle class is joined through but never projected — `Int_researchers`
 * appears only in the FROM path. That is a shape nothing else in the suite (or
 * in `SourcesToOqlEndToEndTest`, whose two- and three-hop tests project every
 * class) currently covers.
 *
 * R6/D2 again: no CLASS alignment touches any of the three sources, so all three
 * integrated classes are auto-promoted `Int_<sourceClassLocalName>` — discovered
 * from the live catalog below and then pinned exactly.
 *
 * No F12 hazard: each integrated class has exactly one source, so no two raw
 * columns can share a local name. (Contrast B2, which needs two alignments purely
 * to dodge it.)
 */

/**
 * `docker/e2e/source-db/init.sql` — the seeded `affiliations` rows, keyed by the
 * institution name so they compose with `EXPECTED.affiliationPairs`, which is
 * (researcher, institution). Written here rather than derived because init.sql is
 * SQL, not a fixture the corpus module parses; A4 grounds its own assertion on the
 * same file the same way.
 */
const COUNTRY_BY_INSTITUTION: Record<string, string> = {
  'Universitat Politecnica de Catalunya': 'Spain',
  'Massachusetts Institute of Technology': 'USA',
};

const INSTITUTION_BY_RESEARCHER = new Map(EXPECTED.affiliationPairs);

// `EXPECTED.acmTitles` is a readonly tuple of literals under `as const`.
const ACM_TITLES: string[] = [...EXPECTED.acmTitles];

/**
 * The whole expected answer, composed from the corpus rather than counted by
 * hand: the ACM slice of (title, researcher) — B3 registers `researchers.csv`
 * alone, exactly as B1 does — then researcher -> institution -> country. Every
 * researcher has exactly one affiliation and every institution one country, so
 * the second hop neither drops nor fans out: the row count is unchanged from B1's.
 */
const expectedRows: string[][] = EXPECTED.authorPairs
  .filter(([title]) => ACM_TITLES.includes(title))
  .map(([title, researcher]) => {
    const institution = INSTITUTION_BY_RESEARCHER.get(researcher);
    if (!institution) {
      throw new Error(`no affiliation fixture for researcher "${researcher}" — EXPECTED has drifted`);
    }
    const country = COUNTRY_BY_INSTITUTION[institution];
    if (!country) {
      throw new Error(`no country for institution "${institution}" — init.sql has drifted`);
    }
    return [title, country];
  });

/** Country -> expected row count, derived from the composition above. */
const expectedCountryCounts = expectedRows.reduce<Record<string, number>>((acc, [, country]) => {
  acc[country] = (acc[country] ?? 0) + 1;
  return acc;
}, {});

/** Stable, comparable form for a whole result row. */
function rowKey(cells: readonly string[]): string {
  return cells.join(' | ');
}

test('a three-class chain resolves both hops', async ({
  journey, repositories, datasets, alignments, workbench,
}) => {
  await test.step('Load two file sources: papers and researchers', async () => {
    // The file half of the setup is the shared journey (project + FS repo +
    // register/enrol). Like Journey/A5 it does NOT call `waitForBootstrap` for
    // uploaded files — F5.
    await journey.bootstrapSources({
      project: 'b3-proj',
      repo: 'b3-files',
      sources: [
        { name: 'acm', file: 'acm_papers.csv' },
        { name: 'researchers', file: 'researchers.csv' },
      ],
    });
  });

  await test.step('Import the affiliations table from Postgres as a third source', async () => {
    // The third class arrives by M9 import instead of upload, so the second hop
    // crosses a source that was never a file. Here `waitForBootstrap` IS used, as
    // in A4/A5: F5 is specific to the file-registration path.
    await repositories.goto();
    await repositories.createPostgres('b3-proj', 'b3-pg');
    await datasets.goto();
    await datasets.importTable('b3-proj', 'b3-pg', 'affiliations');
    await datasets.waitForBootstrap('affiliations');
    await datasets.enrolInModel('affiliations');
  });

  await test.step('Open the alignments canvas for the project', async () => {
    await alignments.goto();
    await alignments.selectProject('b3-proj');
  });

  await test.step('Link each paper to its author', async () => {
    await alignments.selectSources('acm', 'researchers');
    await alignments.addAlignment({
      leftProp: 'author_ref', rightProp: 'rid',
      label: 'authorKey', kind: 'join', relationship: 'hasAuthor',
    });
  });

  await test.step('Link each researcher to their affiliation', async () => {
    await alignments.selectSources('researchers', 'affiliations');
    await alignments.addAlignment({
      leftProp: 'affiliation_ref', rightProp: 'aff_id',
      label: 'affiliationKey', kind: 'join', relationship: 'hasAffiliation',
    });
  });

  await test.step('Integrate the three sources into one schema', async () => {
    await alignments.integrate();
  });

  await test.step('Publish the integrated schema', async () => {
    await alignments.promote();
  });

  await test.step('Open the query workbench', async () => {
    await workbench.goto();
  });

  const { acmClass, researchersClass, affiliationsClass } = await test.step('Find the three integrated classes in the catalog', async () => {
    const classNames = await workbench.waitForCatalogClasses();
    const acmClass = classNames.find((n) => /acm/i.test(n));
    const researchersClass = classNames.find((n) => /researcher/i.test(n));
    const affiliationsClass = classNames.find((n) => /affiliation/i.test(n));
    expect(acmClass, `no acm-ish class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(researchersClass, `no researchers-ish class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(affiliationsClass, `no affiliations-ish class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(acmClass).toBe('Int_acm');
    expect(researchersClass).toBe('Int_researchers');
    expect(affiliationsClass).toBe('Int_affiliations');
    return { acmClass, researchersClass, affiliationsClass };
  });

  await test.step('Query each paper\'s country by hopping through its author', async () => {
    await workbench.run(
      `SELECT ${acmClass}.title, ${affiliationsClass}.country ` +
      `FROM ${acmClass} -[hasAuthor]-> ${researchersClass} -[hasAffiliation]-> ${affiliationsClass}`,
    );
  });

  await test.step('Read back every paper beside the country it traces to', async () => {
    const rows = await workbench.rows();
    expect(rows.length).toBe(expectedRows.length);

    // Exact (title, country) pairs from one read — a broken second hop that still
    // returned the right number of rows (e.g. every paper attributed to the wrong
    // institution) would pass a count check and fail this one.
    expect(rows.map(rowKey).sort()).toEqual(expectedRows.map(rowKey).sort());

    // The country split, stated separately so the failure message names the
    // country that came out wrong. This is the assertion a silently-broken second
    // hop trips first: the hop is the only thing that can turn a researcher into a
    // country at all.
    const countries = rows.map(([, country]) => country);
    for (const [country, count] of Object.entries(expectedCountryCounts)) {
      expect(countries.filter((c) => c === country), `country "${country}"`).toHaveLength(count);
    }
    // Closed over exactly the countries init.sql seeds — nothing stray, and no
    // NULL/empty cell passing as a country.
    expect(new Set(countries)).toEqual(new Set(Object.keys(expectedCountryCounts)));
  });
});
