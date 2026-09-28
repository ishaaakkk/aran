import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';

/**
 * The Data Products screen (`/products`), and the `save as data product` dialog the
 * workbench opens over the result panel.
 *
 * Created for `v37-virtual-products.spec.ts` — the products screen had no page object
 * before it, because no spec had ever driven the UI half of a data product (`p1-mcp-live-
 * client.spec.ts` creates and materializes one over REST).
 */
export class ProductsPage {
  constructor(private readonly page: Page) {}

  /**
   * Save the workbench's CURRENT result as a data product. Must be called with a settled,
   * non-errored result showing: `WorkbenchPanels` only renders `oql-save-product` when the
   * result tab holds a result, so a missing button means the query never succeeded rather
   * than that the dialog moved.
   */
  async saveCurrentQueryAsProduct(opts: {
    project: string;
    name: string;
    description?: string;
    accessMode: 'MATERIALIZED' | 'VIRTUAL';
  }): Promise<void> {
    await this.page.getByTestId('oql-save-product').click();
    const dialog = this.page.getByTestId('product-access-mode-choice');
    await expect(dialog).toBeVisible();

    // Scoped to the dialog (the Dialog primitive's panel, `save-product-dialog`) rather than the
    // page, so the products screen's own project <select> behind the backdrop cannot be hit.
    // The project is selected explicitly even though the dialog now preselects the active one
    // (J6-F5): a spec names the project it means rather than relying on what happens to be active.
    const modal = this.page.getByTestId('save-product-dialog').filter({ has: dialog });
    await modal.locator('select').first().selectOption({ label: opts.project });
    const inputs = modal.locator('input:not([type="radio"])');
    await inputs.nth(0).fill(opts.name);
    if (opts.description) await inputs.nth(1).fill(opts.description);
    await this.page.getByTestId(`product-access-mode-${opts.accessMode.toLowerCase()}`).check();
    await modal.getByRole('button', { name: /^save$/ }).click();

    // The dialog swaps its whole form for a confirmation on success; asserting on that rather
    // than on the dialog closing (it does not close on its own) is what makes a rejected
    // create — which leaves the form up with an error banner — a failure here instead of at
    // some later, more confusing assertion.
    //
    // The success signal is read off the PAGE, not off `modal`, and that is forced rather than
    // stylistic: `modal` is scoped by `has: product-access-mode-choice`, and the form the
    // confirmation replaces is where those radios live. The filter therefore stops matching at
    // exactly the moment the awaited text appears, so `expect(modal).toContainText(…)` can only
    // ever time out with "element(s) not found" while reporting the last form snapshot it saw
    // ("…cancelsaving…") — which reads as a hung save rather than as a self-defeating locator.
    // Cost two full e2e runs to find.
    //
    // The timeout is well past `expect.timeout` (15s) for an independent reason: creating a
    // VIRTUAL product plans its OQL server-side (`DataProductService.create` → `rewrite-oql`),
    // which builds the whole graph-tier view context — downloading each underlying snapshot
    // into a cold DuckDB — before it can answer with a row type.
    //
    // Deliberately not dismissed afterwards: the confirmation has no close control (only an
    // "open data products →" link), and every caller navigates next, which unmounts it.
    await expect(
      this.page.getByRole('link', { name: /open data products/ }),
      'the create never confirmed — the dialog is still showing its form or an error',
    ).toBeVisible({ timeout: 180_000 });
  }

  async goto(project: string): Promise<void> {
    await this.page.goto('/products');
    await this.page.locator('#tour-products-project').selectOption({ label: project });
  }

  row(name: string) {
    return this.page.getByTestId(`product-row-${name}`);
  }

  /** The access-mode badge text on one product's row (`Snapshot` / `Virtual` — user words; the wire enum stays MATERIALIZED/VIRTUAL). */
  async accessBadge(name: string): Promise<string> {
    const badge = this.row(name).getByTestId('product-access-badge');
    await expect(badge).toBeVisible({ timeout: 60_000 });
    return (await badge.innerText()).trim();
  }

  /** The row's whole rendered text — status, defined-on version, rows, data-as-of cell. */
  async rowText(name: string): Promise<string> {
    return (await this.row(name).innerText()).trim();
  }

  /**
   * How many refresh (re-materialize) buttons this product offers: 1 for MATERIALIZED,
   * 0 for VIRTUAL, which has nothing to refresh. A count, not a visibility assertion,
   * because the claim under test is an ABSENCE.
   */
  refreshButton(name: string) {
    return this.page.getByTestId(`materialize-${name}`);
  }

  /**
   * Open the product's detail panel and return its preview's body-row count.
   *
   * For a VIRTUAL product the preview is a live re-execution of its OQL against the ACTIVE
   * catalog (`DataProductService.preview` branches on `accessMode`), so this is the number
   * that must move when an underlying denial constraint is enforced. Since A12 (J6-F19) a
   * VIRTUAL preview runs only on the explicit "run live preview" button — opening the panel
   * no longer queries its sources — so this clicks it when it is offered; a MATERIALIZED
   * product still loads its snapshot preview on open. The panel is re-opened from scratch
   * on every call, so a caller asking twice really does get two live reads. The preview
   * asks for the first 100 rows (`PREVIEW_ROWS`), which a VIRTUAL read now honours.
   */
  async previewRowCount(name: string): Promise<number> {
    await this.row(name).click();
    const table = this.page.getByTestId('product-preview');
    const runLive = this.page.getByTestId('product-preview-run');
    await expect(runLive.or(table)).toBeVisible({ timeout: 120_000 });
    if (await runLive.isVisible()) await runLive.click();
    await expect(table).toBeVisible({ timeout: 120_000 });
    return table.locator('tbody tr').count();
  }

  /** Opens the "Use with agents" (MCP) dialog for one product. */
  async openUseWithAgents(name: string): Promise<void> {
    await this.page.getByTestId(`use-with-agents-${name}`).click();
    await expect(this.page.getByTestId('use-with-agents-dialog')).toBeVisible();
  }

  agentsDialog() {
    return this.page.getByTestId('use-with-agents-dialog');
  }

  /** The dialog's "no snapshot yet — refresh first" banner (V37). */
  notMaterializedWarning() {
    return this.page.getByTestId('use-with-agents-not-materialized');
  }

  /** The dialog's VIRTUAL note: recomputed on every call, ready for agents now (V37 gap 3, fixed). */
  virtualLiveNote() {
    return this.page.getByTestId('use-with-agents-virtual-live');
  }
}
