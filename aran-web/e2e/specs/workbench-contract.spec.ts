import { test, expect, specUser } from '../fixtures';

test.use(specUser('a4'));

/**
 * Contract tests for `WorkbenchPage`'s result reader — deliberately NOT a
 * scenario, and deliberately not deleted alongside the page-object smoke specs
 * Task 13 superseded.
 *
 * Every scenario from A2 onward asserts row counts and set equality against
 * `EXPECTED`. Those assertions are only worth anything if the reader beneath
 * them refuses to invent an answer, so the refusal itself needs a test that
 * does not depend on any particular integrated schema existing.
 */

test('the workbench reports an unknown class as a visible error', async ({ workbench }) => {
  await workbench.goto();
  await workbench.run('SELECT NoSuchClass.nope FROM NoSuchClass');

  expect(
    await workbench.errorText(),
    'an unknown class must surface as a visible error, not an empty grid',
  ).toBeTruthy();
});

test('reading results refuses to return the previous query\'s rows after an error', async ({
  workbench,
}) => {
  // Guards F8: OqlPlanView.run clears
  // queryError and explainResult but never `result`, so a failed query leaves
  // the PREVIOUS query's grid rendered beneath the new error banner. A silent
  // handful of stale rows is exactly the shape of a plausible answer — the kind
  // of wrong that reads as a surprising result rather than a failure — so
  // reading them has to be an error, not a preference.
  await workbench.goto();
  await workbench.run('SELECT NoSuchClass.nope FROM NoSuchClass');

  await expect(workbench.rows()).rejects.toThrow(/showing an error/);
  await expect(workbench.column('title')).rejects.toThrow(/showing an error/);
});

test('catalogClassNames answers rather than hanging when no schema is promoted', async ({
  workbench,
}) => {
  // R6: an integrated class's OQL name comes from the alignment LABEL, and a
  // class reached only through a JOIN alignment is auto-promoted as
  // `Int_<source>`. Tasks 14-18 call this to discover names instead of
  // hardcoding a guess, so it must behave on the empty catalog too — this
  // tenant has no promoted schema, and an empty list is the honest answer.
  await workbench.goto();
  expect(Array.isArray(await workbench.catalogClassNames())).toBe(true);
});
