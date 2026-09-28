import { test, expect, specUser } from '../fixtures';

test.use(specUser('a4'));

/**
 * A4 — M9 import.from_postgres. Registers a relational repository against the
 * compose `source-postgres`, imports `affiliations` as a durable parquet
 * snapshot in the lake, and queries it.
 *
 * Like A1, this stays at the source level (R5/D1): one imported table has
 * nothing to integrate with. What A4 proves is that the M9 import produced a
 * real, queryable snapshot — the table left Postgres, landed in the lake as
 * parquet, bootstrapped, and its rows come back through the preview — not
 * that it can be forced through the integration/OQL pipeline, which does not
 * apply to a lone source.
 */
test('import a Postgres table and query the snapshot', async ({
  projects, repositories, datasets,
}) => {
  await test.step('Create a project to hold the imported table', async () => {
    await projects.goto();
    await projects.create('a4-proj', 'postgres import');
  });

  await test.step('Connect the Postgres database holding the affiliations table', async () => {
    await repositories.goto();
    await repositories.createPostgres('a4-proj', 'a4-pg');
  });

  await test.step('The saved, password-protected repository reads Connected (J2-F1)', async () => {
    // The save runs one test of the stored repository. Before J2-F1 the list probed every row on
    // load by posting its GET body — password '********' — back to be dialed, so this row read
    // UNREACHABLE for a source that was fine.
    await repositories.expectConnectionStatus('a4-pg', 'Connected');

    // A fresh visit dials nothing and shows the recorded result…
    await repositories.goto();
    await repositories.expectConnectionStatus('a4-pg', 'Connected');

    // …and the row's own test uses the stored password, not the mask: its own answer is a
    // successful connection, not merely the label the save's test already left on the row.
    await repositories.testConnection('a4-pg', 'Connected to POSTGRESQL');
    await repositories.expectConnectionStatus('a4-pg', 'Connected');
  });

  await test.step('Import the affiliations table into Aran', async () => {
    await datasets.goto();
    await datasets.importTable('a4-proj', 'a4-pg', 'affiliations');
    await datasets.waitForBootstrap('affiliations');
  });

  await test.step('Enrol the imported table so it can be integrated', async () => {
    await datasets.enrolInModel('affiliations');
  });

  await test.step('Preview the imported rows', async () => {
    const rows = await datasets.previewRows('affiliations');

    // Fixture-grounded (docker/e2e/source-db/init.sql), not a plan number: the
    // seeded `affiliations` table has exactly these two rows, in this schema.
    const institutions = rows.column('institution');
    expect(institutions.sort()).toEqual([
      'Massachusetts Institute of Technology',
      'Universitat Politecnica de Catalunya',
    ]);
  });
});
