import { test, expect, specUser } from '../fixtures';
import {
  backtickTable,
  blockLines,
  fencedBlocks,
  prose,
  section,
  seminarPaperAuthorRows,
  seminarPath,
  seminarResearchers,
  walkthroughSections,
  type PaperAuthorRow,
  type Researcher,
} from '../data/walkthrough';

test.use(specUser('t1'));

test.describe.configure({ timeout: 15 * 60 * 1000 });

/**
 * T1 — the docs walkthrough (`docs-site/docs/getting-started/walkthrough.md`), performed as a
 * reader performs it: one `test.step` per heading, in page order, with the page's own code
 * blocks and alignment tables as the input (`e2e/data/walkthrough.ts` reads them).
 *
 * Nothing executed this page before, and it had rotted three ways at once: S4-2 refused its JSON
 * fixture at bootstrap (two arrays of primitives, `authors` and `keywords`), so Step 3 ended in
 * Failed and nothing after it was reachable; its wrappers renamed the columns the integrated
 * views select, so every query would have failed with a binder error; and its join used one name
 * as both label and relationship, which compiles and then fails every query with "Ambiguous
 * join-property name". Each was found by running the page in-process before this spec existed.
 *
 * The expected rows are derived from the seminar fixture files, never from the page's prose —
 * the prose is checked against them in the second test instead.
 *
 * Class names: `IPaper` comes from the page's own class alignment. The researchers' class is
 * auto-promoted from a join (`Int_<sourceClass>`, R6) and is READ from the product — the
 * Constraints picker, then the workbench catalog — never pinned, because S5-3(b)'s planned
 * dataset component in that name would otherwise break this spec for a rename the page already
 * tells its reader to expect.
 */

const STEPS = [
  'Step 1: Create a project',
  'Step 2: Register a repository',
  'Step 3: Upload the three datasets',
  'Step 4: Enroll the datasets and check their wrappers',
  'Step 5: Align the schemas',
  'Step 6: Let Aran find the join for you',
  'Step 7: Integrate and promote',
  'Step 8: Add denial constraints',
  'Step 9: Query across both databases',
  'Step 10: Publish and connect',
] as const;

/** Step 10's product, as the page names it. */
const PRODUCT = 'papers_by_institution';

const ALIGN_5A = '5a: Union the two paper sources into IPaper';
const ALIGN_5B = '5b: Join the papers to their researchers';

/** The page's literals for Steps 1–3; each is asserted to be on the page before it is used. */
const PROJECT = 'papers';
const REPO = 'uploads';
const DATASETS = [
  { name: 'acm', file: 'acm_papers.csv' },
  { name: 'semantic', file: 'semantic_papers.json' },
  { name: 'researchers', file: 'researchers.json' },
] as const;

/** Written as the page writes the researchers' class; swapped for the catalog's real name. */
const PAGE_RESEARCHER_CLASS = 'Int_researchers';

const NULL_CELL = '∅';

type Cell = string | number;

interface QueryCase {
  /** The page's OQL, whitespace-normalised. Pinned so a changed query cannot keep passing
   *  against the rows of the query it replaced. */
  oql: string;
  columns: string[];
  expected: (rows: PaperAuthorRow[], researcherOf: (r: PaperAuthorRow) => Researcher) => Cell[][];
}

const QUERIES: Record<string, QueryCase> = {
  'Q1: The union': {
    oql: 'SELECT IPaper.*',
    columns: ['pid', 'authorId', 'title', 'year', 'venue', 'citation_count'],
    expected: (rows) => rows.map((r) => [
      r.pid, r.authorId, r.title, r.year, r.venue ?? NULL_CELL, r.citationCount ?? NULL_CELL,
    ]),
  },
  'Q2: Traverse the join': {
    oql: 'SELECT IPaper.title, Int_researchers.full_name, Int_researchers.affiliation '
      + 'FROM IPaper -[hasAuthor]-> Int_researchers',
    columns: ['title', 'full_name', 'affiliation'],
    expected: (rows, of) => rows.map((r) => [r.title, of(r).fullName, of(r).affiliation]),
  },
  'Q3: Filter on the right-hand class': {
    oql: 'SELECT IPaper.title, IPaper.year, Int_researchers.full_name '
      + 'FROM IPaper -[hasAuthor]-> Int_researchers '
      + "WHERE Int_researchers.affiliation = 'UPC'",
    columns: ['title', 'year', 'full_name'],
    expected: (rows, of) => rows
      .filter((r) => of(r).affiliation === 'UPC')
      .map((r) => [r.title, r.year, of(r).fullName]),
  },
  'Q4: Filter on the left-hand class': {
    oql: 'SELECT IPaper.title, Int_researchers.full_name, Int_researchers.affiliation '
      + 'FROM IPaper -[hasAuthor]-> Int_researchers '
      + 'WHERE IPaper.year > 2023',
    columns: ['title', 'full_name', 'affiliation'],
    expected: (rows, of) => rows
      .filter((r) => r.year > 2023)
      .map((r) => [r.title, of(r).fullName, of(r).affiliation]),
  },
  'Q5: An aggregate': {
    oql: 'SELECT Int_researchers.affiliation, COUNT(*) AS paper_count '
      + 'FROM IPaper -[hasAuthor]-> Int_researchers '
      + 'GROUP BY Int_researchers.affiliation '
      + 'HAVING COUNT(*) > 5',
    columns: ['affiliation', 'paper_count'],
    expected: (rows, of) => {
      const counts = new Map<string, number>();
      for (const r of rows) counts.set(of(r).affiliation, (counts.get(of(r).affiliation) ?? 0) + 1);
      return [...counts].filter(([, n]) => n > 5).map(([affiliation, n]) => [affiliation, n]);
    },
  },
};

const normalise = (s: string) => s.replace(/\s+/g, ' ').trim();

/** A whole row as one comparable string — pairs survive, so a mis-joined row cannot hide. */
const rowKeys = (rows: Cell[][]) => rows.map((cells) => cells.map(String).join(' | ')).sort();

function fixtureTruth() {
  const rows = seminarPaperAuthorRows();
  const researchers = seminarResearchers();
  const researcherOf = (r: PaperAuthorRow): Researcher => {
    const found = researchers.get(r.authorId);
    if (!found) throw new Error(`${r.pid}'s author ${r.authorId} is not in researchers.json`);
    return found;
  };
  return { rows, researchers, researcherOf };
}

function pageOql(heading: string): string {
  const blocks = fencedBlocks(section(heading).body);
  expect(blocks, `${heading} must hold exactly one query`).toHaveLength(1);
  expect(
    normalise(blocks[0]),
    `the page's ${heading} changed — update QUERIES["${heading}"] (its OQL and expected rows) with it`,
  ).toBe(QUERIES[heading].oql);
  return blocks[0];
}

const KINDS = ['class', 'datatype', 'object', 'join'] as const;
type Kind = (typeof KINDS)[number];
function asKind(k: string): Kind {
  expect(KINDS as readonly string[], `unknown alignment kind "${k}" on the page`).toContain(k);
  return k as Kind;
}

test('the walkthrough page states what its fixtures produce', () => {
  const { rows, researchers, researcherOf } = fixtureTruth();

  // One test.step per heading below is only true while the page has exactly these steps.
  expect(walkthroughSections().filter((s) => s.level === 2 && s.heading.startsWith('Step ')).map((s) => s.heading))
    .toEqual([...STEPS]);
  expect(walkthroughSections().filter((s) => s.level === 3).map((s) => s.heading))
    .toEqual([ALIGN_5A, ALIGN_5B, ...Object.keys(QUERIES)]);

  const acm = rows.filter((r) => r.source === 'acm');
  const semantic = rows.filter((r) => r.source === 'semantic');

  // Step 4: the paper the page points at really has the author count it names.
  const numberWords = ['zero', 'one', 'two', 'three', 'four', 'five'];
  const firstDoi = semantic[0].pid;
  const firstAuthors = semantic.filter((r) => r.pid === firstDoi).length;
  expect(prose(section(STEPS[3]).body)).toContain(`\`${firstDoi}\`, has ${numberWords[firstAuthors]} authors`);

  // Step 8: the null-safety warning's row count.
  expect(prose(section(STEPS[7]).body)).toContain(`all ${acm.length} ACM rows`);

  // Step 9: row counts, and every paper id the page names — Semantic Scholar's pid is the full
  // DOI, the claim an earlier version of the page got wrong ("SS002").
  const q1 = prose(section('Q1: The union').body);
  expect(q1).toContain(`${rows.length} rows: ACM's ${acm.length}`);
  expect(q1).toContain(`Semantic Scholar's ${semantic.length}`);
  for (const heading of ['Q2: Traverse the join', 'Q3: Filter on the right-hand class',
    'Q4: Filter on the left-hand class']) {
    const expected = QUERIES[heading].expected(rows, researcherOf);
    expect(prose(section(heading).body), heading).toContain(`${expected.length} rows`);
  }
  const idsNamed = (heading: string) =>
    new Set([...prose(section(heading).body).matchAll(/\b(ACM\d{3}|10\.1145\/SS\d{3})\b/g)].map((m) => m[1]));
  expect(idsNamed('Q3: Filter on the right-hand class'))
    .toEqual(new Set(rows.filter((r) => researcherOf(r).affiliation === 'UPC').map((r) => r.pid)));
  expect(idsNamed('Q4: Filter on the left-hand class'))
    .toEqual(new Set(rows.filter((r) => r.year > 2023).map((r) => r.pid)));

  // Q5: the institutions named are exactly the ones above the HAVING bar.
  const q5 = prose(section('Q5: An aggregate').body);
  const above = new Set(QUERIES['Q5: An aggregate'].expected(rows, researcherOf).map(([a]) => String(a)));
  for (const affiliation of new Set([...researchers.values()].map((r) => r.affiliation))) {
    expect(q5.includes(affiliation), `Q5's prose and ${affiliation}`).toBe(above.has(affiliation));
  }
});

test('a reader following the walkthrough gets the rows it promises', async ({
  projects, repositories, datasets, alignments, workbench, products,
}) => {
  const { rows, researcherOf } = fixtureTruth();

  await test.step(STEPS[0], async () => {
    expect(prose(section(STEPS[0]).body)).toContain(`Name it \`${PROJECT}\``);
    await projects.goto();
    await projects.create(PROJECT, 'The docs walkthrough, performed by t1');
  });

  await test.step(STEPS[1], async () => {
    const body = prose(section(STEPS[1]).body);
    expect(body).toContain(`**Name:** \`${REPO}\``);
    // The dialog has had no root-path field since D1: a page still asking for one strands its reader.
    expect(body).not.toContain('Root Directory Path');
    await repositories.goto();
    await repositories.createFileSystem(PROJECT, REPO);
  });

  await test.step(STEPS[2], async () => {
    expect(prose(section(STEPS[2]).body)).toContain('use `acm`, `semantic` and `researchers`');
    for (const { name, file } of DATASETS) {
      await datasets.goto();
      await datasets.registerFile(PROJECT, REPO, name, seminarPath(file));
    }
    // Ready, not merely registered: Failed here is exactly how S4-2 broke this page.
    for (const { name } of DATASETS) {
      await datasets.waitForReadyReloading(name);
    }
  });

  await test.step(STEPS[3], async () => {
    for (const { name } of DATASETS) {
      await datasets.enrolInModel(name);
    }

    // The page quotes the seeded Semantic Scholar wrapper's UNNEST; the seed must still say it.
    const [unnestClause] = fencedBlocks(section(STEPS[3]).body);
    const seed = await datasets.wrapperSql('semantic');
    expect(seed).toContain(unnestClause);

    // Validate & Preview with the seed untouched — the page tells the reader not to edit it.
    const preview = await datasets.previewWrapper('semantic', seed);
    const header = (field: string) => {
      const h = preview.headers.find((x) => x.endsWith(`/semantic/${field}`));
      expect(h, `no column aliased to the ${field} property IRI: ${preview.headers.join(', ')}`).toBeDefined();
      return h!;
    };
    // The preview is capped at 20 rows; the first paper's rows come first, so all of its authors
    // are inside the cap — one row each, which is the UNNEST the page is pointing at.
    const firstDoi = rows.find((r) => r.source === 'semantic')!.pid;
    const dois = preview.column(header('doi'));
    const previewAuthors = preview.column(header('authors')).filter((_, i) => dois[i] === firstDoi);
    expect(previewAuthors.sort())
      .toEqual(rows.filter((r) => r.pid === firstDoi).map((r) => r.authorId).sort());

    for (const name of ['acm', 'researchers']) {
      const table = await datasets.previewWrapper(name, await datasets.wrapperSql(name));
      expect(table.rows.length, `${name}'s seeded wrapper previews rows`).toBeGreaterThan(0);
      expect(table.headers.every((h) => h.includes(`/${name}/`)), table.headers.join(', ')).toBe(true);
    }
  });

  const join = backtickTable(section(ALIGN_5B).body);

  await test.step(STEPS[4], async () => {
    await alignments.goto();
    await alignments.selectProject(PROJECT);

    await test.step(ALIGN_5A, async () => {
      const union = backtickTable(section(ALIGN_5A).body);
      await alignments.selectSources(union.header[0], union.header[1]);
      for (const [leftProp, rightProp, kind, label] of union.rows) {
        await alignments.addAlignment({ leftProp, rightProp, kind: asKind(kind), label });
      }
    });

    await test.step(ALIGN_5B, async () => {
      expect(join.rows).toHaveLength(1);
      const [leftProp, rightProp, kind, label, relationship] = join.rows[0];
      await alignments.selectSources(join.header[0], join.header[1]);
      await alignments.addAlignment({ leftProp, rightProp, kind: asKind(kind), label, relationship });
    });
  });

  await test.step(STEPS[5], async () => {
    // The two sides of 5b's join. The page used to send its reader to each Profile tab to click
    // "Run Profiling Operator" — which re-profiled text columns only and so narrowed the evidence
    // bootstrap had stored (UI audit J3-F2/J3-F20). It now says bootstrap already profiled both,
    // and a reader following it runs nothing here, so neither does this step.
    expect(prose(section(STEPS[5]).body))
      .toContain(`\`${join.header[0]}\` and \`${join.header[1]}\` were both profiled then, so there is nothing to run first`);
    await alignments.goto();
    await alignments.selectProject(PROJECT);
    await alignments.selectSources(join.header[0], join.header[1]);
    await alignments.runDiscovery();

    const ranked = await alignments.suggestions();
    const [leftProp, rightProp] = join.rows[0];
    expect(ranked[0], `ranking: ${JSON.stringify(ranked)}`).toEqual({ left: leftProp, right: rightProp });

    await alignments.closeDiscovery();
  });

  await test.step(STEPS[6], async () => {
    await alignments.integrate();
    await alignments.promote();
  });

  const researcherClass = await test.step(STEPS[7], async () => {
    const body = section(STEPS[7]).body;
    const blocks = fencedBlocks(body);
    expect(blocks, 'Step 8 holds one constraint block per class').toHaveLength(2);
    const [paperConstraints, researcherConstraints] = blocks.map(blockLines);
    // The page adds the first block from `acm`'s tab and the second from `researchers`'.
    expect(prose(body)).toContain(`click \`${join.header[0]}\``);
    expect(prose(body)).toContain(`open \`${join.header[1]}\``);
    const union = backtickTable(section(ALIGN_5A).body);
    const paperClass = union.rows[0][3];

    let researcherClass = '';
    for (const [dataset, constraints] of [
      [join.header[0], paperConstraints],
      [join.header[1], researcherConstraints],
    ] as const) {
      await datasets.goto();
      await datasets.openConstraints(dataset);
      const classes = await datasets.constraintClasses();
      expect(classes, `${dataset}'s constraint picker`).toHaveLength(1);
      if (dataset === join.header[0]) {
        expect(classes[0]).toBe(paperClass);
      } else {
        expect(classes[0]).toMatch(/researcher/i);
        researcherClass = classes[0];
      }
      for (const expression of constraints) {
        await datasets.addConstraint(expression);
        await datasets.setConstraintEnforced(expression, true);
      }
      const byExpression = (a: { expression: string }, b: { expression: string }) =>
        a.expression.localeCompare(b.expression);
      expect((await datasets.constraints()).sort(byExpression)).toEqual(
        constraints.map((expression) => ({ expression, origin: 'MANUAL', enforced: true })).sort(byExpression),
      );
    }
    return researcherClass;
  });

  await test.step(STEPS[8], async () => {
    const union = backtickTable(section(ALIGN_5A).body);
    const paperClass = union.rows[0][3];
    const relationship = join.rows[0][4];

    await workbench.goto();
    const classNames = await workbench.waitForCatalogClasses();
    expect(classNames).toContain(paperClass);
    expect(classNames).toContain(researcherClass);
    expect(await workbench.catalogRelationships())
      .toContainEqual({ name: relationship, from: paperClass, to: researcherClass });

    for (const [heading, query] of Object.entries(QUERIES)) {
      await test.step(heading, async () => {
        const oql = pageOql(heading).replace(new RegExp(`\\b${PAGE_RESEARCHER_CLASS}\\b`, 'g'), researcherClass);
        await workbench.run(oql);
        const records = await workbench.records();
        const actual = records.map((rec) => query.columns.map((c) => {
          expect(rec, `${heading} has no "${c}" column: ${Object.keys(rec).join(', ')}`).toHaveProperty(c);
          return rec[c];
        }));
        expect(rowKeys(actual)).toEqual(rowKeys(query.expected(rows, researcherOf)));
      });
    }
  });

  // UI audit J6-F22: the walkthrough used to end at a query, never saying how anyone else reads
  // what was built. Step 10 saves Q5 — still on screen from the loop above — as a product,
  // materializes it, and opens the agents configuration the page points at.
  await test.step(STEPS[9], async () => {
    const body = prose(section(STEPS[9]).body);
    expect(body).toContain(`name it \`${PRODUCT}\``);
    expect(body).toContain(`\`products.${PRODUCT}\``);

    await products.saveCurrentQueryAsProduct({ project: PROJECT, name: PRODUCT, accessMode: 'MATERIALIZED' });
    await products.goto(PROJECT);
    await expect(products.row(PRODUCT)).toContainText('DRAFT');
    await products.refreshButton(PRODUCT).click();
    // The row re-reads when its materialize job ends (ProductsView's JobCard onDone).
    await expect(products.row(PRODUCT)).toContainText('READY', { timeout: 180_000 });

    await products.openUseWithAgents(PRODUCT);
    await expect(products.agentsDialog()).toContainText('ARAN_TOKEN');
    await expect(products.notMaterializedWarning()).toBeHidden();
  });
});
