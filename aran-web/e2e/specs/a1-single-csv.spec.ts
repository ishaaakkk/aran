import { test, expect, specUser } from '../fixtures';
import { EXPECTED } from '../data';

test.use(specUser('a1'));

// Test 2 reads the 'acm' dataset test 1 registers, in the same tenant —
// playwright.config.ts sets `fullyParallel: true`, so without this the two
// tests could land in different workers and test 2 would find no such
// dataset yet.
test.describe.configure({ mode: 'serial' });

/**
 * A1 — the golden path. The only spec that spells every step out rather than
 * using the Journey helper, so a break anywhere in the chain surfaces here
 * with the failing step named.
 */
test('one CSV source: register, bootstrap, enrol, and preview it', async ({
  projects, repositories, datasets,
}) => {
  await test.step('Create a project to hold the work', async () => {
    await projects.goto();
    await projects.create('a1-proj', 'single CSV end to end');
  });

  await test.step('Connect a folder of files as a repository', async () => {
    await repositories.goto();
    await repositories.createFileSystem('a1-proj', 'a1-files');
  });

  await test.step('Upload a CSV of papers and register it as a source', async () => {
    await datasets.goto();
    await datasets.registerFile('a1-proj', 'a1-files', 'acm', 'acm_papers.csv');
  });

  await test.step('Enrol the source so it can be integrated', async () => {
    // Not `waitForBootstrap`: F5 is open and
    // unverified — the bootstrap badge may never leave "Bootstrapping…" in this
    // stack. `enrolInModel` does not depend on that badge: it retries its own
    // click for up to 30s, which is what actually waits out server-side
    // bootstrap (already confirmed complete in ~1-2s regardless of what the
    // client hears about it).
    await datasets.enrolInModel('acm');
  });

  await test.step('Preview the source\'s rows', async () => {
    // A1 stops at the SOURCE level, deliberately. Integration is a relationship
    // BETWEEN sources — a lone source has nothing to be integrated with, and
    // SchemaIntegrator only ever mints an integrated class from an alignment. So
    // the thing to prove here is that a bootstrapped source is queryable on its
    // own terms, via the dataset preview, not that it can be forced through a
    // pipeline that does not apply to it. Integration and OQL start at A2, where
    // there are two sources to integrate.
    const rows = await datasets.previewRows('acm');
    expect(rows.headers).toContain('title');

    // PRODUCT FINDING F11: the raw preview
    // endpoint hardcodes a 5-row cap for CSV content
    // (DatasetService.previewRawDataset), with no truncation indicator —
    // unlike its own JSON branch, which shows "... (truncated)" past a
    // (different, 20-line) cap. acm_papers.csv has ten rows
    // (EXPECTED.acmTitles), so this can only ever show the file's first five,
    // never all ten. This is NOT the R7 concern (a plan-era row count) — it's
    // a literal describing genuine app truncation behaviour, asserted honestly
    // so this test goes red, on purpose, the day the cap changes.
    const titles = rows.column('title');
    expect(titles).toHaveLength(5);
    expect(new Set(titles).size).toBe(5); // five distinct rows, not five copies of one
    for (const title of titles) {
      expect(EXPECTED.acmTitles, `"${title}" is not one of the fixture's ACM titles`).toContain(title);
    }
  });
});

test('a wrapper query filters the bootstrapped source', async ({ datasets }) => {
  await test.step('Reopen the list of registered sources', async () => {
    // Each Playwright test gets a fresh page/context — nothing carries over
    // from test 1's navigation — so this must land on /datasets itself before
    // `previewWrapper` (via `enrolInModel` -> `open`) can find the 'acm' row
    // test 1 registered in this tenant. Without this, `open()`'s click on
    // `datasets-row-acm` waits against a blank page and hangs to the test
    // timeout (observed: 5 minutes) rather than failing fast.
    await datasets.goto();
  });

  await test.step('Narrow the source down with a wrapper query', async () => {
    // The wrapper is the source-level query surface: a single SELECT over
    // ${SNAPSHOT}, validated by WrapperSqlValidator. This proves the bootstrapped
    // source can actually be queried, not merely previewed wholesale.
    //
    // `year` is quoted: it collides with the Calcite grammar's YEAR() keyword,
    // which WrapperSqlValidator parses against, so an unquoted reference fails
    // validation.
    const rows = await datasets.previewWrapper(
      'acm',
      'SELECT title, "year" FROM ${SNAPSHOT} WHERE "year" > 2021',
    );

    const titles = rows.column('title');
    expect(titles).toContain('Federated Query Processing');
    expect(titles).toContain('Wrappers and Denial Constraints');
    expect(titles).not.toContain('Schema Integration Revisited');
  });
});

test('a source bootstrapping refuses says why, and keeps the failure where it can be retried', async ({
  datasets, page,
}) => {
  await test.step('Upload a JSON whose records carry two arrays of plain values', async () => {
    // S4-2: Aran refuses this shape (unnesting both would multiply every row). The refusal
    // used to surface as a red "Failed" badge whose tooltip said "check logs" (J2-F5).
    await datasets.goto();
    await datasets.registerFile('a1-proj', 'a1-files', 'two-arrays', 'refused/two_arrays.json');
  });

  await test.step('See the reason and a Retry on the row, not only a red badge', async () => {
    await datasets.expectFailed('two-arrays', /tags.*keywords/);
  });

  await test.step('After a reload the failure is still explained, from the jobs list', async () => {
    await page.reload();
    await datasets.expectFailed('two-arrays', /tags.*keywords/);
  });

  await test.step('The dataset page keeps the failed job, with its reason', async () => {
    await datasets.open('two-arrays');
    await expect(datasets.jobCard()).toHaveAttribute('data-job-status', 'FAILED');
    await expect(datasets.jobCard().getByTestId('job-card-error')).toContainText('keywords');
    await expect(datasets.jobCard().getByTestId('job-card-dismiss')).toBeVisible();
  });
});
