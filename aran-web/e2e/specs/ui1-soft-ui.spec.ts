import { expect } from '@playwright/test';
import { test, specUser } from '../fixtures';

/**
 * The soft-skin UI contract (the 2026-08-27 redesign, design-canvas "Chosen system").
 *
 * The rest of the suite proved the redesign broke nothing because it is testid-driven;
 * this spec is the other half: it pins the NEW behaviors, so a regression in any of
 * them goes red instead of silently surviving a green run. Ordered: the zero-data
 * assertions run first, then one Journey bootstrap feeds the routed-page assertions.
 */

test.use(specUser('ui1'));
test.describe.configure({ mode: 'serial' });

test('the app defaults to the light theme for a fresh browser', async ({ page }) => {
  // theme.ts + the index.html first-paint script both default to 'light' now
  // (Field Notes is the system's face; Graphite is the toggle). The storageState
  // this spec runs under carries no aran.ui.mode key, so this is the fresh path.
  await page.goto('/dashboard');
  await expect(page.getByTestId('app-shell')).toBeVisible();
  const classes = await page.evaluate(() => document.documentElement.className);
  expect(classes).toContain('light');
  expect(classes).not.toContain('dark');
});

test('an empty workspace lands on the guided start, and its one CTA creates a project', async ({
  page,
}) => {
  await page.goto('/dashboard');
  // Shown until a schema is promoted, and on an empty tenant — exactly this tenant's state
  // before the bootstrap test below runs — its first step is the one thing such a tenant can
  // do. It used to be "Register a repository", which ended in "Create a project first"
  // (UI audit J1-F2).
  await expect(page.getByTestId('dashboard-getting-started')).toBeVisible();
  await page.getByRole('button', { name: 'Create a project' }).click();
  await page.waitForURL(/\/projects$/);
  // The step lands in the create dialog, not merely on the list.
  await expect(page.getByTestId('projects-name-input')).toBeVisible();
});

test('the command palette opens from the pill and from the keyboard, filters, and navigates', async ({
  page,
}) => {
  await page.goto('/dashboard');
  await expect(page.getByTestId('app-shell')).toBeVisible();

  // The pill was a dead affordance for months (redesign brief, chrome HIGH #2) —
  // this is the assertion that keeps it alive.
  await page.getByTestId('topbar-command-button').click();
  await expect(page.getByTestId('command-palette')).toBeVisible();
  await page.getByTestId('command-palette-input').fill('workbench');
  await page.keyboard.press('Enter');
  await page.waitForURL(/\/oql$/);
  await expect(page.getByTestId('command-palette')).toBeHidden();

  // Keyboard path: ⌘K / Ctrl+K toggles it from anywhere in the shell; Escape closes.
  await page.keyboard.press('ControlOrMeta+KeyK');
  await expect(page.getByTestId('command-palette')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByTestId('command-palette')).toBeHidden();
});

test('the profile dialog is a real dialog: role, Escape to close', async ({ page }) => {
  // The shared Dialog primitive replaced the hand-rolled backdrop pattern; this pins
  // its contract on the one dialog reachable with zero data.
  await page.goto('/dashboard');
  await page.getByTestId('nav-user-menu').click();
  await page.getByRole('button', { name: 'Edit Profile' }).click();
  const dialog = page.getByRole('dialog', { name: 'Edit user profile' });
  await expect(dialog).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
});

test('bootstrapping one source lights up the project breadcrumb and the Alignments stepper', async ({
  page,
  journey,
}) => {
  await journey.bootstrapSources({
    project: 'ui1-proj',
    repo: 'ui1-files',
    sources: [{ name: 'papers', file: 'acm_papers.csv' }],
  });

  // Breadcrumbs carry the REAL active project (never the old hardcoded fiction).
  await page.goto('/alignments');
  await expect(page.getByTestId('topbar-crumbs')).toContainText('ui1-proj');

  // The pipeline stepper is the page's spine once a project is selected.
  await expect(page.getByTestId('align-stepper')).toBeVisible();
  await expect(page.getByTestId('align-stepper')).toContainText('Enroll');
  await expect(page.getByTestId('align-stepper')).toContainText('Promote');
});

test('the dataset detail is a routed page: URL on open, survives reload, back returns, bad id says so', async ({
  page,
  datasets,
}) => {
  await datasets.goto();
  await datasets.open('papers');

  // Opening put the dataset in the URL — the whole point of the routed page.
  await page.waitForURL(/\/datasets\/[0-9a-f-]{36}$/);
  const url = page.url();

  // A deep link (here: reload) reconstructs the same detail from the URL alone.
  await page.reload();
  await expect(page.getByTestId('dataset-detail')).toBeVisible();
  await expect(page.getByTestId('dataset-detail-name')).toHaveText('papers');

  // Back to the list restores the rows (the list hides while a detail is open).
  await page.getByTestId('dataset-close').click();
  await page.waitForURL(/\/datasets$/);
  await expect(page.getByTestId('datasets-row-papers')).toBeVisible();

  // A dataset id this tenant does not have renders an honest not-found, not an
  // empty overlay — and offers the way back.
  await page.goto(url.replace(/[0-9a-f-]{36}$/, '00000000-0000-0000-0000-000000000000'));
  await expect(page.getByText('Dataset not found')).toBeVisible();
  await page.getByRole('button', { name: '← back to datasets' }).click();
  await page.waitForURL(/\/datasets$/);
});
