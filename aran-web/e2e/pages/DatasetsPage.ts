import path from 'node:path';
import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';
import { fixturePath } from '../data';

/**
 * A rendered result table (dataset preview or wrapper preview), read off the
 * DOM so specs get plain strings rather than locators. `column()` throws —
 * naming the columns that do exist — rather than returning `[]` for a typo'd
 * or missing name, which a spec could otherwise assert against and pass for
 * the wrong reason.
 */
export interface TableResult {
  headers: string[];
  rows: string[][];
  column(name: string): string[];
}

export class DatasetsPage {
  constructor(private readonly page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('/datasets');
    await expect(this.page.getByTestId('datasets-register-button')).toBeVisible();
  }

  private async openWizard(projectName: string, repoName: string): Promise<void> {
    // No retries. This used to reload and re-click (the Register button was live before the
    // project list loaded) and then re-click "next step" (the repositories arrived in a second
    // fetch, and confirming before it found none and no-opped with a toast) — F9. Since UI
    // audit J1-F16 / J2-F31 the button is disabled until projects AND repositories have
    // loaded, and Playwright's click waits for it to be enabled, so both races are gone by
    // construction. A load that fails keeps the button disabled beside `datasets-load-error`;
    // this then times out on a visible failure instead of retrying past it, and a project with
    // no repository says so in the dialog (`datasets-no-repository`) instead of no-opping.
    await this.page.goto('/datasets');
    await this.page.getByTestId('datasets-register-button').click();
    await expect(this.page.getByTestId(`datasets-project-${projectName}`)).toBeVisible();
    await this.page.getByTestId(`datasets-project-${projectName}`).click();
    await this.page.getByTestId('datasets-project-confirm').click();
    await expect(this.page.getByTestId(`datasets-repo-${repoName}`)).toBeVisible();
    await this.page.getByTestId(`datasets-repo-${repoName}`).click();
    await this.page.getByTestId('datasets-repo-confirm').click();
    await expect(this.page.getByTestId('dataset-name-input')).toBeVisible();
  }

  /**
   * `fixtureFile` is a name under `e2e/data/`, or an absolute path — the walkthrough spec
   * uploads the docs' own seminar files from `demo/seminar/`, not copies of them.
   */
  async registerFile(
    projectName: string,
    repoName: string,
    datasetName: string,
    fixtureFile: string,
  ): Promise<void> {
    await this.openWizard(projectName, repoName);
    await this.page.getByTestId('dataset-name-input').fill(datasetName);
    await this.page.getByTestId('dataset-description-input').fill(`e2e ${path.basename(fixtureFile)}`);
    await this.page
      .getByTestId('dataset-file-input')
      .setInputFiles(path.isAbsolute(fixtureFile) ? fixtureFile : fixturePath(fixtureFile));
    await this.page.getByTestId('dataset-submit').click();
    await expect(this.page.getByTestId(`datasets-row-${datasetName}`)).toBeVisible();
  }

  /** M9: snapshot a Postgres table into the lake as parquet, then bootstrap it. */
  async importTable(projectName: string, repoName: string, tableName: string): Promise<void> {
    await this.openWizard(projectName, repoName);
    await this.page.getByTestId('dataset-mode-import').click();
    await this.page.getByTestId('dataset-table-select').selectOption(tableName);
    await this.page.getByTestId('dataset-submit').click();
    await expect(this.page.getByTestId(`datasets-row-${tableName}`)).toBeVisible();
  }

  /**
   * Bootstrapping runs as a job; the row's status badge is driven off the
   * job event stream, so the badge leaving its PENDING text is the honest
   * completion signal — UI-observable, no polling or sleeping from the test.
   *
   * DatasetsListView renders one of three mutually exclusive badges sharing
   * this testid, with distinct literal text: "Bootstrapping…", "Failed",
   * "Ready" (DatasetStatus is PENDING/READY/FAILED for this
   * flow — see DatasetService.runBootstrapPipeline). A naive "not PENDING"
   * check would also pass on FAILED, which is exactly the outcome this must
   * catch — so the completion state is asserted positively.
   *
   * KNOWN GAP (see task report): the job's SSE event stream did not reliably
   * deliver its terminal event through this dev-server stack in repeated
   * manual verification, so this assertion could not be observed green
   * end-to-end. The assertion itself is correct and stays as the honest,
   * UI-observable wait; the flagship spec that exercises it is `test.fixme`d
   * until the delivery gap is fixed (tracked separately, out of this task's
   * scope).
   */
  async waitForBootstrap(datasetName: string): Promise<void> {
    const badge = this.page.getByTestId(`datasets-status-${datasetName}`);
    await expect(badge).not.toHaveText('Bootstrapping…', { timeout: 120_000 });
    await expect(badge).toHaveText('Ready');
  }

  /**
   * Waits for the row to read Failed and to say why (UI audit J2-F5): the badge keeps its literal
   * "Failed", and beside it the last job's reason (`datasets-failure-*`) and a Retry.
   */
  async expectFailed(datasetName: string, reason: RegExp): Promise<void> {
    await expect(this.page.getByTestId(`datasets-status-${datasetName}`))
      .toHaveText('Failed', { timeout: 120_000 });
    await expect(this.page.getByTestId(`datasets-failure-${datasetName}`)).toHaveText(reason);
    await expect(this.page.getByTestId(`datasets-retry-${datasetName}`)).toBeVisible();
  }

  /** The open dataset's job card — its latest bootstrap, import or refresh, running or failed. */
  jobCard(): Locator {
    return this.page.getByTestId('dataset-job').getByTestId('job-card');
  }

  /**
   * Waits for the row's badge to settle on Ready, reloading the list between looks.
   *
   * The reload is what the walkthrough itself tells a reader to do when a badge looks stuck
   * (F5: the job event stream that drives the badge is not trusted to deliver), and it makes
   * the answer the server's: after a reload the badge is the dataset row's own status, not a
   * client's memory of an event. Either terminal badge ends the wait, so a Failed bootstrap
   * fails here by name instead of as a two-minute timeout.
   */
  async waitForReadyReloading(datasetName: string): Promise<void> {
    const badge = this.page.getByTestId(`datasets-status-${datasetName}`);
    await expect(async () => {
      await this.goto();
      await expect(badge).toHaveText(/^(Ready|Failed)$/, { timeout: 5_000 });
    }).toPass({ timeout: 120_000 });
    await expect(badge, `${datasetName} did not bootstrap`).toHaveText('Ready');
  }

  /**
   * Idempotent: with the detail route already open on `datasetName` this is a
   * no-op; open on a different dataset, it navigates back first (the detail
   * is a routed page since the redesign — the list is hidden while it is
   * open, so the row to click only exists after closing). Several methods
   * below (`enrolInModel`,
   * `previewRows`, `previewWrapper`, `runProfiling`) call `open()` and are
   * meant to be chainable against the same dataset without the caller
   * managing the panel's lifecycle.
   */
  async close(): Promise<void> {
    const detail = this.page.getByTestId('dataset-detail');
    if (await detail.isVisible()) {
      await this.page.getByTestId('dataset-close').click();
      await expect(detail).not.toBeVisible();
    }
  }

  async open(datasetName: string): Promise<void> {
    const detail = this.page.getByTestId('dataset-detail');
    // The whole branch runs inside toPass because the detail can be MID-TRANSITION when this
    // is called (a close() the routed page is still animating away, or a re-render flashing it
    // back): `isVisible()` does not auto-wait, so it can answer true for a panel that is gone
    // one frame later — and the unbounded `textContent()` that used to follow then auto-waited
    // on a detached parent for the entire test timeout (observed: v32 hanging 10 minutes).
    // Bounding the inner reads and retrying the branch turns that hang into a retry.
    await expect(async () => {
      if (await detail.isVisible()) {
        // Identity is checked against the panel's own name element, never a
        // substring of the whole panel: with `includes()`, opening "papers" while
        // "papers-preview" was already open would count as a match and silently
        // skip the switch, and every method here funnels through this one — so a
        // later spec would drive the wrong dataset with no visible symptom.
        const openName = (await detail.getByTestId('dataset-detail-name')
            .textContent({ timeout: 2_000 }))?.trim();
        if (openName === datasetName) {
          return;
        }
        await this.page.getByTestId('dataset-close').click({ timeout: 2_000 });
        await expect(detail).not.toBeVisible({ timeout: 5_000 });
      }
      // The name cell, not the row's centre: since UI audit J2-F5 a failed row carries its reason
      // and a Retry button mid-row, and a centre click would press Retry instead of opening.
      await this.page.getByTestId(`datasets-row-${datasetName}`).locator('td').first().click({ timeout: 5_000 });
      await expect(detail).toBeVisible({ timeout: 5_000 });
    }).toPass({ timeout: 60_000 });
    await expect(detail.getByTestId('dataset-detail-name')).toHaveText(datasetName);
  }

  /**
   * The "Enroll in model" button lives on the wrapper tab, inside a branch
   * rendered only while the dataset has zero model sources. A successful
   * enrol repopulates model sources and that branch is replaced by the
   * wrapper editor — the button is removed from the DOM, not disabled, so
   * completion is asserted by the editor's appearance instead.
   *
   * Idempotent: if the dataset is already enrolled (the wrapper editor is
   * already showing) this is a no-op, rather than hanging on an enrol button
   * that no longer exists. `previewWrapper` relies on this to call it
   * unconditionally instead of duplicating the enrol flow.
   *
   * Enrolling requires the dataset's bootstrapped source graph to already
   * exist server-side (IntegrationModelService.addSource 404s otherwise via
   * `POST .../model/sources`) — nothing in this flow proves bootstrapping has
   * actually finished (see `waitForBootstrap`'s known gap), and it usually
   * takes a couple of seconds. The enrol button stays in the DOM on a failed
   * attempt (the handler only toasts), so retrying the click is safe and
   * self-heals once bootstrapping catches up.
   */
  async enrolInModel(datasetName: string): Promise<void> {
    await this.open(datasetName);
    await this.page.getByTestId('dataset-tab-wrapper').click();
    const editor = this.page.getByTestId('dataset-wrapper-editor');
    const enrol = this.page.getByTestId('dataset-enrol');

    // The wrapper tab renders THREE mutually exclusive things: a spinner while
    // the model loads, the enrol button once it has loaded with no sources, or
    // the editor once it has sources. Choosing between them ONCE, before the
    // retry loop, is a trap — while the model is still loading neither control
    // exists, so an already-enrolled dataset falls through to clicking an enrol
    // button that will never appear, and goes on clicking it for the full
    // timeout even after the editor arrives. Re-evaluate on every attempt.
    await expect(async () => {
      if (await editor.isVisible()) {
        return;
      }
      // An enrol in flight disables the button (J4-F11): a click on it would wait for an enabled
      // button that the landing enrol removes instead, so only an enabled one is clicked — the
      // editor's appearance below is still what says the enrol landed.
      if (await enrol.isVisible() && await enrol.isEnabled()) {
        await enrol.click();
      }
      // Still loading, or the click has just landed: fail this attempt so the
      // loop re-evaluates from the top rather than committing to one branch.
      await expect(editor).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 30_000 });
  }

  /**
   * Reads a rendered result table into plain strings. Shared by
   * `previewRows` and `previewWrapper` so both return the same shape.
   */
  private async readTable(testId: string): Promise<TableResult> {
    const table = this.page.getByTestId(testId);
    await expect(table).toBeVisible();

    const headers = (await table.locator('thead th').allTextContents()).map((h) => h.trim());

    const rowLocators = table.locator('tbody tr');
    const rowCount = await rowLocators.count();
    const rows: string[][] = [];
    for (let i = 0; i < rowCount; i++) {
      const cells = await rowLocators.nth(i).locator('td').allTextContents();
      rows.push(cells.map((c) => c.trim()));
    }

    return {
      headers,
      rows,
      column(name: string): string[] {
        const idx = headers.indexOf(name);
        if (idx === -1) {
          throw new Error(`column "${name}" not found; available columns: ${headers.join(', ')}`);
        }
        return rows.map((r) => r[idx]);
      },
    };
  }

  /**
   * A lone source is queried at the source level, not through integration —
   * integration is a relationship BETWEEN sources, and SchemaIntegrator only
   * ever mints an integrated class from an alignment (see task report / R5).
   * This opens the dataset, switches to the preview tab, and reads the
   * rendered preview table (`GET .../datasets/{id}/preview`).
   *
   * KNOWN PRODUCT GAP (F11): this endpoint
   * hardcodes a 5-row cap for CSV/TSV content server-side, with no
   * truncation indicator — callers with a fixture wider than 5 rows will
   * only ever see the first 5, never a sample and never the true count.
   */
  async previewRows(datasetName: string): Promise<TableResult> {
    await this.open(datasetName);
    await this.page.getByTestId('dataset-tab-preview').click();
    return this.readTable('dataset-preview-table');
  }

  /**
   * Runs `sql` (a single SELECT over `${SNAPSHOT}`, validated server-side by
   * WrapperSqlValidator) through the wrapper's "Validate & Preview" and reads
   * the result. The wrapper editor only renders once the dataset is enrolled
   * in the model, so this ensures enrolment first — enrolling is not
   * integrating, it just adds the source to the project's model, and needs
   * no second source.
   */
  async previewWrapper(datasetName: string, sql: string): Promise<TableResult> {
    await this.enrolInModel(datasetName);
    const editor = this.page.getByTestId('dataset-wrapper-editor');
    // The editor's value is bound to React state that the bootstrapped
    // class's seeded `nx:wrapperSql` populates asynchronously, right after
    // the editor itself appears (a separate effect, keyed off the model
    // sources that enrolling just fetched) — filling before that settles
    // gets silently overwritten back to the seed once it lands. Wait for the
    // seed to actually show up before replacing it.
    await expect(editor).not.toHaveValue('');
    await editor.fill(sql);
    // Confirm the fill actually stuck (see above) before submitting it —
    // otherwise a re-fired seed effect could silently send the seed instead
    // of `sql`, and the caller would never know which query it actually ran.
    await expect(editor).toHaveValue(sql);
    await this.page.getByTestId('dataset-wrapper-preview').click();
    return this.readTable('dataset-wrapper-preview-table');
  }

  /**
   * The wrapper SQL the model currently holds for the selected class, as the editor shows it.
   *
   * Waits for the seed/reload effect to populate the textarea before reading (see
   * `previewWrapper` for why an empty read is a real hazard, not a theoretical one): the value
   * is bound to React state a separate effect fills from the fetched model sources, so a read
   * taken the instant the editor appears returns `''`.
   */
  async wrapperSql(datasetName: string): Promise<string> {
    await this.enrolInModel(datasetName);
    const editor = this.page.getByTestId('dataset-wrapper-editor');
    await expect(editor).not.toHaveValue('');
    return editor.inputValue();
  }

  /**
   * Replace the selected class's wrapper SQL and click Save Wrapper.
   *
   * Save is what computes and persists the Phase 3A expansion verdict
   * (`IntegrationModelService.setWrapper` → `verify-wrapper`), so the badge only becomes
   * meaningful after this — not after a preview.
   *
   * TWO races here, both observed failing rather than reasoned about:
   *
   * 1. **The success toast is not a per-save signal.** Every save shows the identical string and
   *    it lives 4s (`DatasetsListView.showToast`), so on a second save the wait is satisfied
   *    instantly by the FIRST save's toast and this returns before the second PUT has been sent.
   *    Waiting for the toast to be GONE first is what makes the wait a statement about THIS save
   *    — the same trap `AlignmentsPage.promote` documents for a second promotion's ACTIVE badge.
   * 2. **`handleSaveWrapper` shows the toast before awaiting its model reload**, so the badge and
   *    the editor can still be rendering the PREVIOUS model when this returns. Closing and
   *    reopening the panel forces a fresh `loadModelForDataset`, so everything a caller reads
   *    afterwards comes from the server rather than from pre-save state. The saved value itself
   *    is deliberately NOT asserted here: proving the save round-tripped is the caller's claim,
   *    and asserting it here would make the caller's own assertion vacuous.
   */
  async saveWrapper(datasetName: string, sql: string): Promise<void> {
    await this.enrolInModel(datasetName);
    const editor = this.page.getByTestId('dataset-wrapper-editor');
    await expect(editor).not.toHaveValue('');
    await editor.fill(sql);
    // Confirm the fill stuck before submitting — a re-fired seed effect would otherwise save
    // the seed text and the caller would never know which SQL it actually stored.
    await expect(editor).toHaveValue(sql);

    const toast = this.page.getByTestId('datasets-toast');
    // Only a success toast hides itself; an error one stays until dismissed (UI audit J8-F8), so
    // an earlier failure's toast is dismissed rather than waited out.
    if ((await toast.count()) > 0 && (await toast.getAttribute('data-type')) === 'error') {
      await this.page.getByTestId('toast-dismiss').click();
    }
    await expect(toast).toBeHidden({ timeout: 15_000 });
    await this.page.getByTestId('dataset-wrapper-save').click();
    await expect(toast).toContainText('Wrapper SQL saved to the integration model.', {
      timeout: 60_000,
    });

    await this.close();
    await this.enrolInModel(datasetName);
  }

  /**
   * The Phase 3A expansion badge: its rendered (possibly truncated) text, and the full reason
   * the `title` tooltip carries. Both, because the two differing is the point — the reason is
   * capped at 512 chars server-side and previewed at 80 in the chip.
   */
  async wrapperExpansionBadge(): Promise<{ text: string; title: string | null }> {
    const badge = this.page.getByTestId('dataset-wrapper-expansion-badge');
    await expect(badge).toBeVisible();
    return {
      text: (await badge.innerText()).trim(),
      title: await badge.getAttribute('title'),
    };
  }

  /** Opens the dataset's Denial Constraints tab. */
  async openConstraints(datasetName: string): Promise<void> {
    await this.open(datasetName);
    await this.page.getByTestId('dataset-tab-constraints').click();
    // The tab renders a spinner, an empty state, or the class picker; the picker is the only
    // one a caller can do anything with, and it needs the catalog fetch to have landed.
    await expect(this.page.getByTestId('dataset-constraint-class-select')).toBeVisible({
      timeout: 60_000,
    });
  }

  /** The integrated class names the Constraints tab offers for this dataset. */
  async constraintClasses(): Promise<string[]> {
    return this.page.getByTestId('dataset-constraint-class-select').locator('option').allInnerTexts();
  }

  /**
   * The constraint rows on the selected class, read off attributes rather than the rendered
   * line (which interleaves the expression, an optional provenance chip, a checkbox label and a
   * delete button with no separators that survive `innerText`).
   */
  async constraints(): Promise<Array<{ expression: string; origin: string; enforced: boolean }>> {
    return this.page.getByTestId('constraint-row').evaluateAll((els) =>
      els.map((el) => ({
        expression: el.getAttribute('data-expression') ?? '',
        origin: el.getAttribute('data-origin') ?? '',
        enforced: el.getAttribute('data-enforced') === 'true',
      })),
    );
  }

  /**
   * Add a MANUAL constraint on the class the Constraints tab currently has selected.
   *
   * The expression field has no testid of its own; its placeholder is the only stable handle
   * (`DatasetConstraintsTab`). The wait is on the persisted ROW appearing, not on the Add
   * button re-enabling: the server validates the expression against the class's columns
   * (DuckDB EXPLAIN) and answers a bad one with a toast, leaving the field filled — so
   * waiting on the row is what turns a rejected expression into a named failure here rather
   * than an empty list two assertions later.
   */
  async addConstraint(expression: string): Promise<void> {
    // Scoped to the detail panel out of habit and cheap safety: the old modal's
    // backdrop (a role="button" div) used to make a page-level getByRole('Add')
    // ambiguous. The backdrop is gone — the detail is a routed page now — but the
    // scoped locator stays correct and survives future siblings.
    const panel = this.page.getByTestId('dataset-detail');
    await panel.getByPlaceholder('h_index >= 0').fill(expression);
    await panel.getByRole('button', { name: 'Add' }).click();
    const escaped = expression.replace(/"/g, '\\"');
    await expect(
      this.page.locator(`[data-testid="constraint-row"][data-expression="${escaped}"]`),
      `the constraint "${expression}" was not persisted`,
    ).toHaveCount(1, { timeout: 60_000 });
  }

  /** The violet "discovered" provenance chips on the currently listed constraints. */
  discoveredBadges(): Locator {
    return this.page.getByTestId('constraint-discovered-badge');
  }

  /**
   * The retired row-level "Discover Denial Constraints (LIMA)" button (the modal that wrote
   * `Dataset.denialConstraints`, which nothing read). Removed on 2026-08-26 along with the
   * column itself (Flyway V35). Kept as a locator so a spec can assert the seam stayed closed
   * — two "discover constraints" entry points, only one wired to the query engine, is exactly
   * the confusion the retirement removed.
   */
  legacyDiscoveryButton(): Locator {
    return this.page.locator('[title="Discover Denial Constraints (LIMA)"]');
  }

  /**
   * Run LIMA discovery for the selected class and wait for the job to finish.
   *
   * The honest completion signal is the button's own disabled → enabled transition:
   * `handleDiscoverConstraints` disables it synchronously on click and
   * `handleDiscoverJobDone` — the JobCard's terminal-state callback — is the only thing that
   * clears it. Waiting only for "enabled" is true the instant before React re-renders, so the
   * disabled edge is waited for first (the same trap `DriftPage.reBootstrapExpectingApply`
   * documents). The toast is then asserted by the caller when it wants the SUCCEEDED/FAILED
   * distinction, since the button re-enables under both.
   *
   * **The enabled edge is not enough to read the results.** `handleDiscoverJobDone` clears the
   * disabled flag and only THEN fires `void loadModelForDataset(…)` — un-awaited. React batches
   * both into one commit today, so the tab briefly renders its "Loading integration model…"
   * branch (the button and the class picker both leave the DOM), which means the enabled edge
   * does imply the reload STARTED — but nothing about it having finished, and
   * `constraints()` has no retry of its own: a slow reload hands the caller `[]` and the
   * headline assertion flakes red. So this waits for the tab body to be back, and — when the
   * caller says candidates are expected — for at least one row to have rendered, which turns a
   * genuinely empty discovery into a named failure rather than an opaque timeout.
   *
   * @param opts.expectCandidates the caller asserts discovery will persist at least one row
   */
  async discoverConstraints(
    opts: { expectCandidates?: boolean; expectEmptyNote?: boolean } = {},
  ): Promise<void> {
    const button = this.page.getByTestId('dataset-discover-constraints');
    await button.click();
    await expect(button).toBeDisabled({ timeout: 30_000 });
    await expect(button).toBeEnabled({ timeout: 300_000 });

    await expect(this.page.getByTestId('dataset-constraint-class-select')).toBeVisible({
      timeout: 60_000,
    });
    if (opts.expectCandidates) {
      await expect(
        this.page.getByTestId('constraint-row'),
        'the discovery job finished but persisted no candidates',
      ).not.toHaveCount(0, { timeout: 60_000 });
    }
    // V43: the settle signal for a run that is EXPECTED to persist nothing. Waiting on "no rows
    // appeared" is not a wait at all — it passes instantly, before the job has even reported —
    // so the honest-emptiness note is what the spec synchronises on instead.
    if (opts.expectEmptyNote) {
      await expect(
        this.page.getByTestId('discovery-empty-note'),
        'the discovery job finished without saying why it persisted nothing',
      ).toBeVisible({ timeout: 60_000 });
    }
  }

  /** V43: the standing note explaining a discovery run that persisted nothing. */
  discoveryEmptyNote(): Locator {
    return this.page.getByTestId('discovery-empty-note');
  }

  /**
   * One constraint row, by its exact expression. Selected by the row's OWN `data-expression`
   * attribute rather than `filter({ has: … })`, which matches a DESCENDANT — the attribute is
   * on the row element, so a `has` filter silently matches nothing.
   */
  constraintRow(expression: string): Locator {
    const escaped = expression.replace(/"/g, '\\"');
    return this.page.locator(`[data-testid="constraint-row"][data-expression="${escaped}"]`);
  }

  /**
   * Toggle one constraint's `enforced` checkbox, by its exact expression.
   *
   * Only a MANUAL constraint can be enforced: the checkbox on a DISCOVERED row is disabled and
   * the server refuses its PATCH (V43), so this helper would hang on such a row rather
   * than fail usefully — assert `constraintRow(expr).locator('input[type=checkbox]')` is
   * disabled instead.
   */
  async setConstraintEnforced(expression: string, enforced: boolean): Promise<void> {
    const row = this.constraintRow(expression);
    await expect(row, `no constraint row with expression ${expression}`).toHaveCount(1);
    const box = row.locator('input[type="checkbox"]');

    // `check()`/`uncheck()` cannot be used here, and that is a property of the product rather
    // than a quirk of this helper: the checkbox is a CONTROLLED input bound to `c.enforced`
    // straight off the loaded model, so React re-renders it back to its old state the instant
    // the click lands and only flips it after the PATCH and the model reload that follows.
    // Playwright's `check()` asserts the new state immediately and fails with "Clicking the
    // checkbox did not change its state" — observed exactly that. `click()` plus the ROW's own
    // `data-enforced` attribute waits for the server to have agreed, which is the honest
    // signal anyway. The outer retry re-reads the attribute FIRST, so a click that did land
    // while the list was re-rendering is never sent twice (which would toggle it back).
    await expect(async () => {
      if ((await row.getAttribute('data-enforced')) === String(enforced)) return;
      await box.click({ timeout: 5_000 });
      await expect(row).toHaveAttribute('data-enforced', String(enforced), { timeout: 15_000 });
    }).toPass({ timeout: 90_000 });
  }

  /**
   * Make sure the dataset has a stored column profile, and leave its metrics on screen.
   *
   * Bootstrap persists a profile, and since J3-F1 (2026-09-25) the Profile tab reads it on open,
   * so the usual path clicks nothing: the tab shows the stored metrics. Only a dataset with no
   * stored profile offers `dataset-run-profiling`, and then this runs it. That button used to be
   * offered on every open (the tab could not read what was stored, FRONTEND-7), so every call
   * re-profiled — with varcharOnly=true until J3-F2, which dropped the numeric columns the
   * discovery specs then scored without. Candidates can therefore differ from runs before that
   * date: numeric columns are profiled now.
   *
   * Waits for whichever the tab settles on — the metrics or the run button — rather than for the
   * button with a short timeout: the tab first shows a loading state while it reads the store.
   */
  async runProfiling(datasetName: string): Promise<void> {
    await this.open(datasetName);
    await this.page.getByTestId('dataset-tab-profile').click();
    const metrics = this.page.getByTestId('dataset-profile-metrics');
    const runButton = this.page.getByTestId('dataset-run-profiling');
    await expect(metrics.or(runButton).first()).toBeVisible({ timeout: 30_000 });
    if (!(await metrics.isVisible())) {
      // One click: the run is a job, with no client deadline to drop its result (FRONTEND-2).
      await runButton.click();
    }
    // The per-column metrics only render once a stored profile is on screen; the empty state's
    // copy never contains this label.
    await expect(this.page.getByTestId('dataset-detail')).toContainText('Cardinality count', {
      timeout: 120_000,
    });
  }

  /**
   * The freshness block on the metadata tab. Present for every dataset — for an uploaded one it
   * explains why there is nothing to refresh, which is the state a user hunting for a missing
   * button needs to see.
   */
  freshness(): Locator {
    return this.page.getByTestId('dataset-freshness');
  }

  /**
   * Set the refresh schedule.
   *
   * `intervalSeconds` selects by option VALUE rather than label, because the labels are prose
   * ("every 6 hours") that a copy edit would change while the contract — the number of seconds
   * the backend is sent — would not.
   */
  async setRefreshPolicy(datasetName: string, intervalSeconds: number | 'MANUAL'): Promise<void> {
    await this.open(datasetName);
    await this.page
      .getByTestId('dataset-refresh-policy-select')
      .selectOption(intervalSeconds === 'MANUAL' ? 'MANUAL' : String(intervalSeconds));
  }

  /**
   * Start a refresh by hand and stay on the dataset's page. The refresh runs as a job whose card
   * (`dataset-job`) shows its stages there, and when the job ends the page re-reads the row and
   * its freshness in place (UI audit J2-F11) — so a caller waits on the freshness block, with no
   * close-and-reopen loop. A refresh that fails leaves its card with the reason on the page.
   */
  async refreshNow(datasetName: string): Promise<void> {
    await this.open(datasetName);
    await this.page.getByTestId('dataset-refresh-now').click();
  }
}
