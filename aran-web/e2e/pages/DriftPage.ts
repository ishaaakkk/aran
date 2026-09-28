import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';

/**
 * The schema-drift surfaces: the dataset-list badge, the local-graph modal's
 * re-bootstrap, and the review panel that a breaking candidate opens.
 *
 * Re-bootstrapping used to clear the source graph unconditionally. It now runs a
 * drift check first, so `reBootstrap()` has two possible outcomes and the caller
 * has to say which one it expects — `reBootstrapExpectingReview()` for the gated
 * path, `reBootstrapExpectingApply()` for the clean one. Neither ever guesses:
 * asserting the outcome is the point of the spec that calls it.
 */
export class DriftPage {
  constructor(private readonly page: Page) {}

  /** Open the local source-graph modal from a dataset row. */
  async openLocalGraph(datasetName: string): Promise<void> {
    // The row's own detail panel overlays the action buttons when open, so
    // close it first rather than clicking into a backdrop.
    const detail = this.page.getByTestId('dataset-detail');
    if (await detail.isVisible()) {
      await this.page.getByTestId('dataset-close').click();
      await expect(detail).not.toBeVisible();
    }
    await this.page.getByTestId(`datasets-graph-${datasetName}`).click();
    await expect(this.page.getByTestId('local-graph-rebootstrap')).toBeVisible();
  }

  /**
   * Re-bootstrap and expect the drift gate to withhold the write.
   *
   * The check costs a snapshot download and an introspection before the panel can
   * appear, so the wait is generous — but it is a wait for the panel, not a sleep.
   */
  async reBootstrapExpectingReview(): Promise<void> {
    await this.page.getByTestId('local-graph-rebootstrap').click();
    await expect(this.page.getByTestId('drift-panel')).toBeVisible({ timeout: 120_000 });
  }

  /**
   * Re-bootstrap and expect it to apply without interruption.
   *
   * The button is disabled while the drift CHECK runs and again while the bootstrap
   * job runs, and re-enables under both outcomes — so the sequence matters. Waiting
   * only for "disabled" would assert the panel's absence while the check was still
   * in flight, which passes before the decision has been made and would go green
   * even if the gate then opened. Waiting only for "enabled" is worse: it is
   * already true the instant after the click, before React has re-rendered at all.
   *
   * So: disabled (the check has started) → enabled (the decision has landed,
   * whichever way) → and only then, the panel is not there.
   */
  async reBootstrapExpectingApply(): Promise<void> {
    const button = this.page.getByTestId('local-graph-rebootstrap');
    await button.click();
    await expect(button).toBeDisabled({ timeout: 30_000 });
    await expect(button).toBeEnabled({ timeout: 120_000 });
    await expect(this.page.getByTestId('drift-panel')).not.toBeVisible();
  }

  /** The verdict line, e.g. "SCHEMA DRIFT — BREAKING". */
  async verdict(): Promise<string> {
    const panel = this.page.getByTestId('drift-panel');
    await expect(panel).toBeVisible();
    return (await panel.textContent())?.trim() ?? '';
  }

  /** The reason shown under the verdict — what the gate says it is protecting. */
  async verdictReason(): Promise<string> {
    return (await this.page.getByTestId('drift-verdict-reason').textContent())?.trim() ?? '';
  }

  /** The change kinds the report lists, read off the impact rows' testids. */
  async impactKinds(): Promise<string[]> {
    const rows = this.page.getByTestId('drift-panel').locator('[data-testid^="drift-impact-"]');
    const count = await rows.count();
    const kinds: string[] = [];
    for (let i = 0; i < count; i++) {
      const id = await rows.nth(i).getAttribute('data-testid');
      if (id) kinds.push(id.replace('drift-impact-', ''));
    }
    return kinds;
  }

  /** Leave the current schema in place. The dataset should end up DRIFTED. */
  async keepCurrentSchema(): Promise<void> {
    await this.page.getByTestId('drift-cancel').click();
    await expect(this.page.getByTestId('drift-panel')).not.toBeVisible();
  }

  /** Apply the withheld schema as-is, leaving the affected mappings dangling. */
  async applyAnyway(): Promise<void> {
    await this.page.getByTestId('drift-force').click();
    await expect(this.page.getByTestId('drift-panel')).not.toBeVisible();
  }

  /**
   * Repoint the proposed renames and apply.
   *
   * The panel pre-ticks every repairable rename, so this asserts the button is
   * enabled (it is disabled with nothing selected) rather than ticking anything —
   * a spec that had to tick a box first would be asserting the pre-tick away.
   */
  async repairAndApply(): Promise<void> {
    const repair = this.page.getByTestId('drift-repair');
    await expect(repair).toBeVisible();
    await expect(repair).toBeEnabled();
    await repair.click();
    await expect(this.page.getByTestId('drift-panel')).not.toBeVisible();
  }

  /** Whether the panel offers a repair at all — false when nothing is repointable. */
  async offersRepair(): Promise<boolean> {
    return (await this.page.getByTestId('drift-repair').count()) > 0;
  }

  async closeLocalGraph(): Promise<void> {
    await this.page.getByTestId('local-graph-close').click();
    await expect(this.page.getByTestId('local-graph-rebootstrap')).not.toBeVisible();
  }

  /**
   * Rename a dataset through the edit dialog.
   *
   * This is the one drift case reachable entirely through the UI, and it is not a
   * contrived one: `BootstrapEngine.sourceClassIri` interpolates the dataset NAME
   * into every class and property IRI, so renaming a dataset and re-bootstrapping
   * re-mints the whole source graph while the file underneath is untouched. Every
   * mapping that referenced the old IRIs is orphaned by it.
   */
  async rename(from: string, to: string): Promise<void> {
    const detail = this.page.getByTestId('dataset-detail');
    if (await detail.isVisible()) {
      await this.page.getByTestId('dataset-close').click();
      await expect(detail).not.toBeVisible();
    }
    await this.page.getByTestId(`datasets-edit-${from}`).click();
    const nameInput = this.page.getByTestId('dataset-name-input');
    await expect(nameInput).toBeVisible();
    // The dialog seeds its fields from the row asynchronously; filling before the
    // seed lands gets silently overwritten back to the old name.
    await expect(nameInput).toHaveValue(from);
    await nameInput.fill(to);
    await expect(nameInput).toHaveValue(to);
    await this.page.getByTestId('dataset-submit').click();
    await expect(this.page.getByTestId(`datasets-row-${to}`)).toBeVisible();
  }

  /** The status badge's text for a dataset row. */
  async statusBadge(datasetName: string): Promise<string> {
    const badge = this.page.getByTestId(`datasets-status-${datasetName}`);
    await expect(badge).toBeVisible({ timeout: 120_000 });
    return (await badge.textContent())?.trim() ?? '';
  }

  /** Wait for a row to settle on a specific badge, e.g. after a bootstrap job. */
  async waitForStatus(datasetName: string, text: string): Promise<void> {
    await expect(this.page.getByTestId(`datasets-status-${datasetName}`))
      .toHaveText(text, { timeout: 120_000 });
  }
}
