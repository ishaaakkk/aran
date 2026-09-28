import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';

export class WorkbenchPage {
  constructor(private readonly page: Page) {}

  async goto(): Promise<void> {
    // The route is /oql, not /workbench. App.tsx's catch-all redirects anything
    // unmatched to /dashboard, so a wrong path here does not 404 — it silently
    // lands on another page and every locator then fails as "not found".
    await this.page.goto('/oql');
    await expect(this.page.getByTestId('oql-editor')).toBeVisible();
  }

  /**
   * Fills `oql` and runs it, waiting for the query to settle — a result grid
   * or a visible error, and never neither. This cannot resolve against STALE
   * content left over from a previous run: `ResultPanel` gates both the
   * result table and the error banner behind `running`, replacing both with
   * a spinner the instant `run` is clicked (OqlPlanView.run sets `running`
   * synchronously before the network call), so the two testids are actually
   * absent from the DOM for the duration of this query, not just hidden.
   *
   * The settle wait counts matches instead of asserting one is visible. It used to be forced:
   * after a SUCCESSFUL query was followed by a FAILING one, both testids were in the DOM at once
   * (the old grid rendered under the new banner), and `results.or(error)` resolving to two
   * elements fails the whole wait on a strict-mode violation. Since the UI audit's rule R4
   * (2026-09-25, J5-F1) a failure replaces the result — `ResultPanel` never renders the grid
   * beside an error — so the count is kept as defence rather than as a workaround.
   */
  async run(oql: string): Promise<void> {
    const editor = this.page.getByTestId('oql-editor');
    await editor.fill(oql);
    // `fill()` dispatches a real `input` event, which `OqlEditor.onInput` treats exactly like
    // typing. Until J5-F3 (2026-09-25) that opened the autocomplete popup for whatever identifier
    // sat at the caret — typically a query's last class name — over the Run button, and
    // Playwright's click retry fought it for the full test timeout. The popup now opens by itself
    // only after `Class.` or `-[`, which no complete query ends with, so this Escape is defence
    // rather than a workaround: it is the editor's own dismissal, and a no-op when nothing is open.
    await editor.press('Escape');
    await this.page.getByTestId('oql-run').click();
    const settled = this.page.getByTestId('oql-results').or(this.page.getByTestId('oql-error'));
    await expect(async () => {
      expect(await settled.count(), 'query has not settled — neither results nor an error').toBeGreaterThan(0);
    }).toPass({ timeout: 120_000 });
  }

  /**
   * Throws, naming the error, if the workbench is currently showing one.
   *
   * Written for a product gap that is now closed: after `goodQuery; badQuery` the grid used to
   * keep rendering the FIRST query's rows under the new error banner. Since rule R4 (J5-F1,
   * 2026-09-25) a failure replaces the result, so the grid is gone whenever the error shows. The
   * check stays: `rows()`/`column()` must never hand back rows as if they belonged to a query
   * that failed, whatever the view does next.
   */
  private async refuseIfErrored(): Promise<void> {
    const err = this.page.getByTestId('oql-error');
    if (await err.isVisible()) {
      throw new Error(`workbench is showing an error, not results: ${(await err.innerText()).trim()}`);
    }
  }

  /** Body rows as text, excluding the header. Throws if the panel shows an error instead. */
  async rows(): Promise<string[][]> {
    await this.refuseIfErrored();
    const table = this.page.getByTestId('oql-results');
    await expect(table).toBeVisible();
    return table.locator('tbody tr').evaluateAll((trs) =>
      trs.map((tr) => Array.from(tr.querySelectorAll('td')).map((td) => td.textContent?.trim() ?? '')),
    );
  }

  /**
   * One column's values, by its header name (`QueryRunResult.headerNames` —
   * the local-name display form ResultTable renders into `<th>`, not the
   * full property IRI). Throws, naming the columns that DO exist, rather
   * than returning `[]` for a typo'd or missing name — see DatasetsPage's
   * `TableResult.column` for the same contract. Also throws if the panel
   * shows an error instead of results (see `refuseIfErrored`).
   *
   * Reads only each `<th>`'s own first text node, not its full `textContent`:
   * `ResultTable.tsx` renders `{col}<span class="th-sort-icon">⇅</span>` — a
   * REAL sibling text node holding a sort-direction glyph, not CSS-generated
   * content or `aria-hidden`, so `allTextContents()` on `thead th` returns
   * "title⇅", not "title". `.trim()` alone cannot fix this: the glyph is
   * adjacent to the label with no whitespace between them. Discovered here
   * because this is the first spec to read a real `column()` result from a
   * successful workbench query — `workbench-contract.spec.ts` only exercises
   * the error path, and `a1-single-csv.spec.ts` reads a different table
   * component (`DatasetsPage`'s own result table, not `ResultTable`).
   */
  async column(name: string): Promise<string[]> {
    await this.refuseIfErrored();
    const table = this.page.getByTestId('oql-results');
    await expect(table).toBeVisible();
    const headers = await table.locator('thead th').evaluateAll((ths) =>
      ths.map((th) => {
        const label = Array.from(th.childNodes).find((n) => n.nodeType === Node.TEXT_NODE);
        return (label?.textContent ?? '').trim();
      }),
    );
    const idx = headers.indexOf(name);
    if (idx < 0) {
      throw new Error(`column "${name}" not found; available columns: ${headers.join(', ')}`);
    }
    return table.locator('tbody tr').evaluateAll(
      (trs, i) => trs.map((tr) => Array.from(tr.querySelectorAll('td'))[i]?.textContent?.trim() ?? ''),
      idx,
    );
  }

  /**
   * The whole result, one record per row keyed by header, read in ONE pass so a cell can never
   * be paired with another row's (F22b faked a broken join by zipping separately-read columns).
   * Headers are read the way `column()` reads them — the `<th>`'s own text node, without the
   * sort glyph. A NULL cell reads as `∅`, which is what `ResultTable` renders for it.
   */
  async records(): Promise<Array<Record<string, string>>> {
    await this.refuseIfErrored();
    const table = this.page.getByTestId('oql-results');
    await expect(table).toBeVisible();
    return table.evaluate((t) => {
      const headers = Array.from(t.querySelectorAll('thead th')).map((th) => {
        const label = Array.from(th.childNodes).find((n) => n.nodeType === Node.TEXT_NODE);
        return (label?.textContent ?? '').trim();
      });
      return Array.from(t.querySelectorAll('tbody tr')).map((tr) => {
        const cells = Array.from(tr.querySelectorAll('td')).map((td) => td.textContent?.trim() ?? '');
        return Object.fromEntries(headers.map((h, i) => [h, cells[i] ?? '']));
      });
    });
  }

  /**
   * The class names actually present in the catalog, so a spec can assert
   * against the real thing instead of guessing one. Read off the `data-testid`
   * attribute itself (`catalog-class-${name}`), NOT the button's `innerText`
   * — the button also renders a selection chevron, an "in query" badge, a row
   * count and a source count with no separators between them, so its text
   * content is not reliably parseable back into a bare class name.
   *
   * R6: an integrated class's OQL name is minted from the alignment LABEL,
   * not the dataset name — except a class reached only via a JOIN alignment,
   * which is auto-promoted as `Int_<sourceClassLocalName>`. Specs must
   * discover names here rather than hardcode a guess.
   */
  async catalogClassNames(): Promise<string[]> {
    const testIds = await this.page
      .locator('[data-testid^="catalog-class-"]')
      .evaluateAll((els) => els.map((el) => el.getAttribute('data-testid') ?? ''));
    return testIds.map((id) => id.replace(/^catalog-class-/, ''));
  }

  /**
   * The relationships the catalog exposes — `MappingCatalog`'s JOIN properties and
   * its OBJECT properties, which `getCatalogData` (lib/workbenchModel.ts) folds into
   * one `edges` list and `CatalogBrowser` renders under "join / object properties".
   *
   * This is the only place an integrated OBJECT property is user-visible at all: it
   * is not a class (so `catalogClassNames()` cannot see it), and `OqlPlanner` indexes
   * JOIN properties only (`OqlPlanner:54`), so no OQL query can name one either. B4's
   * whole claim — two nested-JSON object properties become ONE integrated
   * relationship — is observable here and nowhere else.
   *
   * Read off attributes rather than text for the same reason `catalogClassNames()`
   * does: the rendered row interleaves the name, an arrow and the domain→range path
   * with no separators that survive `innerText`.
   */
  async catalogRelationships(): Promise<Array<{ name: string; from: string; to: string }>> {
    return this.page.locator('[data-testid^="catalog-rel-"]').evaluateAll((els) =>
      els.map((el) => ({
        name: (el.getAttribute('data-testid') ?? '').replace(/^catalog-rel-/, ''),
        from: el.getAttribute('data-from') ?? '',
        to: el.getAttribute('data-to') ?? '',
      })),
    );
  }

  /**
   * Waits until the catalog has classes, RELOADING between attempts.
   *
   * The reload is the point, not impatience. `OqlPlanView` fetches the catalog
   * exactly once on mount and sends any failure to `console.error`
   * (`OqlPlanView.tsx:105-108`) — there is no retry, no refetch and nothing
   * shown to the user. So one transient failure leaves the workbench
   * permanently empty for that page visit, and polling the DOM can never
   * recover because nothing will ever fetch again. Reloading is exactly what a
   * user is forced to do, and it is the only thing that works. See F15.
   *
   * Returns the class names, so callers can discover the real ones (R6) rather
   * than hardcode a guess.
   */
  async waitForCatalogClasses(timeoutMs = 120_000): Promise<string[]> {
    let names: string[] = [];
    await expect(async () => {
      names = await this.catalogClassNames();
      if (names.length === 0) {
        await this.goto();
        names = await this.catalogClassNames();
      }
      expect(names.length, 'catalog still has no classes after reload').toBeGreaterThan(0);
    }).toPass({ timeout: timeoutMs });
    return names;
  }

  /**
   * The optimized plan's structured shape, as the profiler pane renders it — one entry per
   * `PlanShape.Node` in pre-order (parent before its inputs), which is the order
   * `PlanShapeNodeView` emits them in the DOM.
   *
   * The pane is behind the profiler toggle, which starts OFF (`ResultPanel`'s `showProfiler`),
   * so this clicks it if the tree is not already showing rather than assuming a previous call
   * left it open. `kind`/`detail` are read off `data-kind`/`data-detail` because the rendered
   * head interleaves the kind chip, the detail text, an access chip and a toggle glyph with no
   * separators that survive `innerText` — the same reason `catalogClassNames()` reads an
   * attribute. `e2eContract.test.tsx` pins both attributes.
   *
   * Only the OPTIMIZED view is structured; the logical view is plain text by design (see
   * `PlanTree`), so this always reads the optimized plan and never has to choose.
   */
  async planNodes(): Promise<Array<{ kind: string; detail: string }>> {
    await this.refuseIfErrored();
    const tree = this.page.getByTestId('oql-plan-tree');
    if (!(await tree.isVisible())) {
      await this.page.getByTestId('oql-profiler').click();
    }
    await expect(tree).toBeVisible({ timeout: 30_000 });
    return tree.getByTestId('plan-node').evaluateAll((els) =>
      els.map((el) => ({
        kind: el.getAttribute('data-kind') ?? '',
        detail: el.getAttribute('data-detail') ?? '',
      })),
    );
  }

  /**
   * The plan's leaf scans — what the query actually reads. `u_<dataset>_<class>` is a source
   * member view (the opaque wrapper path), `int_<class>` a whole integrated class, and
   * `src_<dataset>` the raw snapshot relation an EXPANDED wrapper would bottom out in
   * (V32). Order follows `planNodes()`.
   */
  async planScans(): Promise<string[]> {
    const nodes = await this.planNodes();
    // `data-detail` is always just the table: `PlanShape` appends ", access=MODE" to a scan's
    // detail, and `PlanTree` splits that back out into its own attribute and chip before
    // rendering (pinned by e2eContract.test.tsx), so nothing here has to know about it.
    return nodes.filter((n) => n.kind === 'SCAN').map((n) => n.detail);
  }

  async errorText(): Promise<string | null> {
    const err = this.page.getByTestId('oql-error');
    return (await err.isVisible()) ? (await err.innerText()).trim() : null;
  }
}
