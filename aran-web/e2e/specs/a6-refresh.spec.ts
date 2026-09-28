import { test, expect, specUser } from '../fixtures';

test.use(specUser('a6'));

// A refresh re-runs the copy AND the bootstrap, twice over in this spec.
test.describe.configure({ timeout: 10 * 60 * 1000 });

/**
 * A6 — phase E: a materialized dataset's freshness.
 *
 * The feature's whole claim is that a copy can be remade from its upstream, so this drives the
 * loop the user actually performs: import a table, see that Aran knows where it came from and that
 * it has never been re-copied, put it on a schedule, take it off again, refresh it by hand, and
 * see the age change.
 *
 * The two assertions worth defending:
 *
 * - **"never refreshed" is not "0 seconds old".** The import is not a refresh — nothing has been
 *   remade yet — and a UI that renders a missing staleness as zero would report the freshest
 *   possible state for a dataset that has never been back to its source. Asserted before and after
 *   the manual refresh, because only the pair distinguishes the two.
 * - **An uploaded dataset says why it cannot be refreshed.** Absence of an upstream is the signal,
 *   and a user looking for a refresh button that is not there needs the panel to explain itself
 *   rather than simply omit the control.
 *
 * Uses the same compose `source-postgres` and `affiliations` table as A4.
 */
test('a materialized dataset reports its upstream and can be refreshed', async ({
  projects, repositories, datasets, jobs,
}) => {
  await test.step('Create a project to hold the imported table', async () => {
    await projects.goto();
    await projects.create('a6-proj', 'refresh policy');
  });

  await test.step('Connect the Postgres database holding the affiliations table', async () => {
    await repositories.goto();
    await repositories.createPostgres('a6-proj', 'a6-pg');
  });

  await test.step('Import the affiliations table into Aran', async () => {
    await datasets.goto();
    await datasets.importTable('a6-proj', 'a6-pg', 'affiliations');
    await datasets.waitForBootstrap('affiliations');
  });

  await test.step('See where the copy came from, and that it has never been refreshed', async () => {
    // --- the imported dataset knows where it came from, and has not been back ---
    await datasets.open('affiliations');
    await expect(datasets.freshness()).toBeVisible();
    await expect(datasets.freshness().getByTestId('dataset-freshness-source'))
      .toContainText('affiliations');
    await expect(datasets.freshness().getByTestId('dataset-freshness-age'))
      .toHaveText('never refreshed');
  });

  await test.step('Put the dataset on an hourly refresh schedule', async () => {
    // --- a schedule takes effect, and says when it will next run ---
    await datasets.setRefreshPolicy('affiliations', 3_600);
    await expect(datasets.freshness().getByTestId('dataset-refresh-next')).toBeVisible();
  });

  await test.step('Take it off the schedule and back to manual refresh', async () => {
    // --- and can be taken off again, which must clear the next run ---
    await datasets.setRefreshPolicy('affiliations', 'MANUAL');
    await expect(datasets.freshness().getByTestId('dataset-refresh-next')).toHaveCount(0);
  });

  await test.step('Refresh the copy by hand and watch its age change', async () => {
    // --- refreshing by hand re-copies and re-bootstraps ---
    await datasets.refreshNow('affiliations');
    // The page follows the refresh's job and re-reads its freshness when the job ends (J2-F11):
    // the age changes in place, with no close-and-reopen polling (this used to be a toPass loop).
    await expect(datasets.freshness().getByTestId('dataset-freshness-age'))
      .not.toHaveText('never refreshed', { timeout: 120_000 });
    await expect(datasets.freshness().getByTestId('dataset-refresh-error')).toHaveCount(0);
  });

  await test.step('Find the refresh in the Jobs drawer, from the top bar', async () => {
    // Every job is findable from any page, by kind and the object it ran on (J8-F7).
    await jobs.open();
    await jobs.expectJob('Refresh', 'affiliations', 'SUCCEEDED');
    await jobs.expectJob('Import', 'affiliations', 'SUCCEEDED');
    await jobs.close();
  });

  await test.step('Enrol the refreshed dataset and check its rows survived', async () => {
    // --- and the rows survived the round trip ---
    await datasets.enrolInModel('affiliations');
    const institutions = (await datasets.previewRows('affiliations')).column('institution');
    expect(institutions.sort()).toEqual([
      'Massachusetts Institute of Technology',
      'Universitat Politecnica de Catalunya',
    ]);
  });
});

test('an uploaded dataset explains why it cannot be refreshed', async ({
  projects, repositories, datasets,
}) => {
  await test.step('Create a project for a file-based source', async () => {
    await projects.goto();
    await projects.create('a6-upload-proj', 'no upstream');
  });

  await test.step('Connect a folder of files as a repository', async () => {
    await repositories.goto();
    await repositories.createFileSystem('a6-upload-proj', 'a6-files');
  });

  await test.step('Upload a CSV of papers instead of importing a table', async () => {
    await datasets.goto();
    await datasets.registerFile('a6-upload-proj', 'a6-files', 'papers', 'acm_papers.csv');
  });

  await test.step('See why an uploaded file offers nothing to refresh from', async () => {
    await datasets.open('papers');
    // The panel is present and explains itself; the controls are absent because they could do
    // nothing. Both halves matter — a missing panel would leave the user with no answer at all.
    await expect(datasets.freshness().getByTestId('dataset-freshness-no-upstream')).toBeVisible();
    await expect(datasets.freshness().getByTestId('dataset-refresh-now')).toHaveCount(0);
    await expect(datasets.freshness().getByTestId('dataset-refresh-policy-select')).toHaveCount(0);
  });
});
