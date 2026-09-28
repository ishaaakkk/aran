import type { Page } from '@playwright/test';
import { expect, test } from '@playwright/test';

/**
 * A Playwright retry (CI runs `retries: 1`) reuses the spec's tenant. The first attempt's project
 * keeps every class it promoted, and the UI-OD2 guard refuses any other project's promote that
 * drops one — even a class the retry's project promoted too. Label-minted IRIs usually repeat, so
 * a retry often passes; when it is refused, the refusal is most likely the first attempt's
 * leftovers, and the real failure is the first attempt's.
 */
function retryHint(): string {
  let retry = 0;
  try {
    retry = test.info().retry;
  } catch {
    return ''; // called outside a running test
  }
  return retry > 0
    ? ` — this is retry ${retry}: the tenant still holds the first attempt's promoted schema,`
      + ' which the UI-OD2 guard will not let another project remove. Read the first attempt\'s failure.'
    : '';
}

/**
 * Alignments — join discovery, mapping CRUD, integration, incremental integration
 * and promotion.
 *
 * DEVIATIONS FROM A NAIVE READING OF THE VIEW: two things below are not what a
 * dropdown-shaped brief would expect. Both are load-bearing, so they're explained
 * here rather than left as unexplained plumbing:
 *
 * 1. There is no "property picker" `<select>`. `AlignmentsView` selects the left/right
 *    correspondence elements by CLICKING NODES in a D3 force-directed schema graph
 *    (`SchemaGraphCanvas` → `lib/schema-graph.js`), which sets `leftSelectedNode` /
 *    `rightSelectedNode` via a click handler on each rendered `<g>`. `addAlignment()`
 *    below drives that graph directly instead of a `selectOption` call. Each node
 *    carries `data-testid="schema-node-<label>"` (a schema-graph.js addition, not
 *    view-scoped since the canvas is shared) scoped by the containing panel's own
 *    `align-left-graph` / `align-right-graph` testid, so same-named nodes on the two
 *    sides can't collide.
 * 2. A promote is confirmed in a dialog (`promote-confirm`, audit J4-F1): it shows the
 *    diff against the ACTIVE schema and says "tenant-wide", and its confirm button stays
 *    disabled until that diff has loaded. The confirmed promote calls
 *    `navigate('/versions')` in the same tick as `setPromoted(true)`, which React 18
 *    batches into one commit — the "promoted" panel on THIS view is built but never
 *    painted before the route swaps to `/versions`. `promote()` below waits for either
 *    that badge (kept as a defensive first choice) or the real signal: the promoted
 *    version's ACTIVE status badge on the versions page it actually lands on.
 *
 * Join discovery runs only when asked — "Auto-Infer Join Alignments" or the modal's own
 * Run (UI-OD7, 2026-09-25, reversing design fact D3 / R3 of ui-resolutions.md). Nothing
 * pops a modal over the controls any more, so no method here dismisses one before it
 * acts; `closeDiscovery()` closes the panel `runDiscovery()` opened.
 */
export class AlignmentsPage {
  constructor(private readonly page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('/alignments');
    await expect(this.page.getByTestId('align-project-select')).toBeVisible();
  }

  async selectProject(name: string): Promise<void> {
    await this.page.getByTestId('align-project-select').selectOption({ label: name });
    // Changing the project kicks off an async repo+dataset listing fetch; the
    // left/right dataset selects hold only the placeholder option until it resolves.
    await expect(async () => {
      const count = await this.page.getByTestId('align-left-select').locator('option').count();
      expect(count).toBeGreaterThan(1);
    }).toPass({ timeout: 30_000 });
  }

  /**
   * The dataset selects render each option as `"{name} ({repoName})"`, so a bare
   * `selectOption({ label: name })` never matches — resolve the real value by the
   * name prefix instead. Retries: the option list is populated by the same async
   * fetch `selectProject` waits on, and this can be called right after it.
   */
  private async selectDatasetOption(testId: string, datasetName: string): Promise<void> {
    const select = this.page.getByTestId(testId);
    await expect(async () => {
      const value = await select.locator('option').evaluateAll((opts, name) => {
        const match = opts.find((o) => (o.textContent ?? '').trim().startsWith(`${name} (`));
        return match ? (match as HTMLOptionElement).value : null;
      }, datasetName);
      if (!value) {
        throw new Error(`no "${datasetName} (…)" option yet in ${testId}`);
      }
      await select.selectOption(value);
    }).toPass({ timeout: 30_000 });
  }

  async selectSources(left: string, right: string): Promise<void> {
    // Reset both sides to the placeholder FIRST. Each select disables whatever
    // dataset is currently chosen on the OTHER side (by design — R5 in
    // ui-resolutions.md — a source cannot be aligned with itself). That is a
    // real deadlock hazard across consecutive calls, not just a theoretical
    // one: A5's join loop calls selectSources('dblp','researchers') then
    // immediately selectSources('researchers','affiliations') — the second
    // call's new LEFT target ('researchers') is exactly the first call's RIGHT
    // value, still live on the page. A naive "set left, then right" order
    // retries selecting the now-disabled 'researchers' option on the left for
    // the full 30s and times out, never reaching the right side at all — this
    // looked exactly like the imported `affiliations` dataset being silently
    // absent from the picker (F20) until
    // the DOM snapshot at failure showed `affiliations` present and enabled
    // the whole time, with the LEFT select still stuck on the PREVIOUS call's
    // dataset. Clearing both to the always-enabled placeholder option first
    // removes any such collision before either side is set to its real target
    // — the fix belongs here, not in the product.
    await this.page.getByTestId('align-left-select').selectOption('');
    await this.page.getByTestId('align-right-select').selectOption('');
    await this.selectDatasetOption('align-left-select', left);
    await this.selectDatasetOption('align-right-select', right);
  }

  /**
   * Clicks the node labelled `label` in the left or right schema-graph canvas, retrying a
   * short click while the graph mounts and its layout settles.
   *
   * PRODUCT FINDING (F18): `schema-graph.js`
   * derives BOTH the rendered node text AND the `data-testid` purely from
   * `localName(iri)` (the property IRI's last path segment), with no tooltip or
   * other pre-click way to tell nodes apart. A JSON source whose nested object
   * happens to reuse a key already used elsewhere in the same source (A5's
   * `dblp_papers.json`: top-level `title` and `conference.title`) renders TWO
   * visually and textually IDENTICAL nodes for that label, and a bare
   * `getByTestId` resolves to both — Playwright correctly refuses to click an
   * ambiguous locator. A sighted user faces the exact same ambiguity (nothing
   * distinguishes the two circles before clicking); their only real recourse is
   * to click one and read the IRI the "selected node" side panel then reveals.
   * Reproduced here without a click-and-backtrack: D3 already keeps each node's
   * real datum — including its IRI — on the DOM element itself (`__data__`, the
   * same object `onSelect` receives), so it can be read directly. Among same-
   * labelled candidates, prefer the one with the SHORTEST IRI: a nested-object
   * property's IRI always carries an extra `.<parentKey>` path segment versus
   * the same-named property declared directly on the class, so it is always
   * the longer string (see `BootstrapEngine.sourcePropertyIri` /
   * `JsonBootstrap.processObject`'s `childPath` construction). This is a
   * resilience fix for a confirmed product defect, not a scenario weakening —
   * every existing single-match caller takes the untouched fast path below.
   */
  private async clickGraphNode(side: 'left' | 'right', label: string): Promise<void> {
    const candidates = this.page.getByTestId(`align-${side}-graph`).getByTestId(`schema-node-${label}`);
    // No re-fit before a retry: the canvas fits itself when its layout settles (schema-graph.js
    // autoFit), so a node left outside the clipped pane is a product regression this should
    // catch, as it did on 2026-09-24 (b2 4 of 6 runs) when the fit ran on a 700 ms timer.
    await expect(async () => {
      // Always resolve through nth(), never click the bare locator even when only
      // one node currently matches. The graph is still mounting and simulating
      // while this retries, so a `count() === 1` fast path is a race: the second
      // same-labelled node (A5's dblp `conference.title` alongside its top-level
      // `title`) can appear between the count and the click, and Playwright then
      // fails the whole attempt on a strict-mode violation. Observed exactly that.
      const withIris = await candidates.evaluateAll((els) =>
        els.map((el, i) => ({
          i,
          iri: (el as unknown as { __data__?: { iri?: string } }).__data__?.iri ?? '',
        })),
      );
      if (withIris.length === 0) {
        throw new Error(`no node labelled "${label}" in the ${side} graph yet`);
      }
      const shallowest = withIris.reduce((a, b) => (b.iri.length < a.iri.length ? b : a));
      await candidates.nth(shallowest.i).click({ timeout: 5_000 });
    }).toPass({ timeout: 200_000 });
  }

  async addAlignment(opts: {
    leftProp: string;
    rightProp: string;
    label: string;
    kind: 'class' | 'datatype' | 'object' | 'join';
    relationship?: string;
  }): Promise<void> {
    await this.clickGraphNode('left', opts.leftProp);
    await this.clickGraphNode('right', opts.rightProp);
    await this.page.getByTestId('align-kind-select').selectOption(opts.kind);
    await this.page.getByTestId('align-label-input').fill(opts.label);
    if (opts.kind === 'join') {
      // Join labels are packed "label|relationship|direction" by the operator.
      await this.page
        .getByTestId('align-relationship-input')
        .fill(opts.relationship ?? opts.label);
    }
    const mappingRows = this.page.locator('[data-testid^="align-mapping-"]');
    const before = await mappingRows.count();
    await this.page.getByTestId('align-add').click();
    // A row-count increase (not a bare visibility check) — handleAddAlignment
    // silently no-ops on a duplicate or malformed alignment, and a text-content
    // assertion against a row that was already there for a different reason
    // would pass for the wrong reason.
    await expect(mappingRows).toHaveCount(before + 1, { timeout: 15_000 });
  }

  /**
   * Runs join discovery and waits for its candidates. Discovery runs only when asked
   * (UI-OD7): "Auto-Infer Join Alignments" opens the panel (and starts a run when the
   * pair has no candidates yet), and the panel's own Run re-runs it — so this clicks Run
   * only when the open did not already start one.
   */
  async runDiscovery(): Promise<void> {
    const outcome = await this.discover();
    expect(
      outcome.state,
      `join discovery settled as '${outcome.state}' (${outcome.pairsScored ?? '?'} pairs compared), not with candidates: ${outcome.message}`,
    ).toBe('candidates');
  }

  /**
   * Runs join discovery and returns how it settled — the modal's
   * `align-discovery-outcome` (`data-state`: candidates | partial | degraded |
   * no-profile | unmeasured | none | error). Since A10 (J3-F5) a run that could not score says so
   * instead of looking like two sources that share nothing, so a spec asserting an
   * ABSENCE (c3) can tell a correct rejection from a broken run.
   *
   * Per R3, discovery may already be running (or have already popped its modal)
   * before this is called — this does not assume it starts closed, and waits for an
   * outcome NEWER than the one on screen (`data-run`) rather than trusting that its
   * own click is what triggered the run.
   */
  async discover(): Promise<{ state: string; pairsScored: number | null; message: string }> {
    const panel = this.page.getByTestId('align-discovery-panel');

    // The modal's own run control lives INSIDE the modal, so it cannot open one that is
    // closed (F10): open from the outside first whenever it is not already showing.
    if (!(await panel.isVisible())) {
      await expect(async () => {
        if (!(await panel.isVisible())) {
          await this.page.getByTestId('align-discovery-open').click({ timeout: 5_000 });
        }
      }).toPass({ timeout: 60_000 });
    }
    await expect(panel).toBeVisible();

    const outcome = this.page.getByTestId('align-discovery-outcome');
    // evaluateAll, not getAttribute: it does not wait for an element that a run in
    // flight has just unmounted.
    const settledRun = async (): Promise<number> =>
      outcome.evaluateAll((els) => {
        const raw = els[0]?.getAttribute('data-run');
        return raw ? Number(raw) : -1;
      });
    // Opening the panel on a pair with no candidates starts a run in the same click (UI-OD7:
    // discovery runs only from an explicit action, never from pair selection), and Run stays
    // disabled until it finishes. Run is clicked only when nothing is in flight; either way what
    // is awaited below is a run that settled after this call read its baseline.
    const runButton = this.page.getByTestId('align-discovery-run');
    const before = await settledRun();
    if (await runButton.isEnabled()) {
      await runButton.click();
    }
    // freyja scores every column pair of both sides; give it room.
    await expect
      .poll(settledRun, { timeout: 180_000, message: 'join discovery never settled' })
      .toBeGreaterThan(before);

    const state = (await outcome.getAttribute('data-state')) ?? '';
    const pairs = await outcome.getAttribute('data-pairs-scored');
    return {
      state,
      pairsScored: pairs ? Number(pairs) : null,
      message: (await outcome.innerText()).trim(),
    };
  }

  /** Closes the discovery panel without accepting anything — how the walkthrough's Step 6 ends. */
  async closeDiscovery(): Promise<void> {
    const closeButton = this.page.getByTestId('align-discovery-close');
    if (await closeButton.isVisible()) {
      await closeButton.click();
      await expect(this.page.getByTestId('align-discovery-panel')).toBeHidden();
    }
  }

  /** Candidate pairs in the order the UI ranked them. */
  async suggestions(): Promise<Array<{ left: string; right: string }>> {
    const rows = this.page.locator('[data-testid^="align-candidate-"]:not([data-testid*="check"])');
    return rows.evaluateAll((els) =>
      els
        .map((el) => ({
          left: el.getAttribute('data-left') ?? '',
          right: el.getAttribute('data-right') ?? '',
          rank: Number(el.getAttribute('data-rank') ?? 0),
        }))
        .sort((a, b) => a.rank - b.rank)
        .map(({ left, right }) => ({ left, right })),
    );
  }

  async acceptSuggestions(pairs: Array<[string, string]>): Promise<void> {
    const mappingsBefore = await this.page.locator('[data-testid^="align-mapping-"]').count();

    for (const [left, right] of pairs) {
      await this.page.getByTestId(`align-candidate-check-${left}-${right}`).check();
    }
    await this.page.getByTestId('align-candidates-accept').click();
    // The panel closes only when every candidate sent was added (J3-F4); a refused one keeps
    // it open with the server's reason on its row, which the assertion message carries.
    await expect(
      this.page.getByTestId('align-discovery-panel'),
      'a selected candidate was not added — see its row status in the discovery panel',
    ).toBeHidden({ timeout: 30_000 });

    // The mapping count going up is the evidence a suggestion was actually persisted, and
    // C1/C2 rest entirely on that having happened.
    await expect(this.page.locator('[data-testid^="align-mapping-"]')).toHaveCount(
      mappingsBefore + pairs.length,
      { timeout: 30_000 },
    );
  }

  /** Full rebuild of every enrolled source into a DRAFT. */
  async integrate(): Promise<void> {
    await this.page.getByTestId('align-integrate').click();
    await expect(this.page.getByTestId('align-promote')).toBeEnabled({ timeout: 180_000 });
  }

  /** The sources the incremental picker offers — already-integrated ones must not appear. */
  async incrementalOptions(): Promise<string[]> {
    const select = this.page.getByTestId('align-incremental-select');
    return select.locator('option').allInnerTexts();
  }

  async incrementalAdd(datasetName: string): Promise<void> {
    await this.page
      .getByTestId('align-incremental-select')
      .selectOption({ label: datasetName });
    await this.page.getByTestId('align-incremental-add').click();
    await expect(this.page.getByTestId('align-promote')).toBeEnabled({ timeout: 180_000 });
  }

  /**
   * Model-aware promotion, confirmed. The view calls the model-aware endpoint (via
   * `actions.promoteRun`; see AlignmentsView.confirmPromote), so the run becomes the
   * model's baseline.
   *
   * `align-promote` opens the confirmation (J4-F1); its confirm button waits for the diff
   * against ACTIVE, which the click below waits for too. A refusal — the promote would
   * remove another project's classes (UI-OD2), or the draft was superseded — stays in the
   * dialog as `promote-confirm-error`, and this fails at once with its text.
   *
   * A successful promote navigates away to `/versions` in the same render as the
   * local "promoted" flag, so `align-version-badge` on THIS view is a defensive
   * first choice that likely never paints (see class doc) — the real signal is the
   * ACTIVE status badge on the versions page this lands on.
   */
  async promote(): Promise<void> {
    // Count the ACTIVE badges BEFORE promoting. Waiting for "an ACTIVE badge
    // exists" is not a completion signal at all on the second promotion in a
    // spec: the badge left by the FIRST promotion already satisfies it, so the
    // wait returns instantly and the spec goes on to assert against a schema
    // that was never promoted. B5 does exactly two cycles. What actually marks
    // this promotion is the count going UP.
    const activeBadges = this.page.locator('[data-testid="version-status-badge"]', {
      hasText: 'ACTIVE',
    });
    const before = await activeBadges.count();

    await this.page.getByTestId('align-promote').click();
    const dialog = this.page.getByTestId('promote-confirm');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('tenant-wide');
    await this.page.getByTestId('promote-confirm-confirm').click({ timeout: 60_000 });

    // A refusal ends the wait at once — thrown inside toPass it would only be retried.
    const refusal = this.page.getByTestId('promote-confirm-error');
    let refused: string | null = null;
    await expect(async () => {
      if (await refusal.isVisible()) {
        refused = (await refusal.textContent()) ?? 'no reason given';
        return;
      }
      // `align-version-badge` almost certainly never paints — the view
      // navigates away in the same batched render that sets it (F7) — so it is
      // only a defensive first choice, kept in case that is ever fixed.
      if (await this.page.getByTestId('align-version-badge').isVisible()) {
        return;
      }
      expect(await activeBadges.count()).toBeGreaterThan(before);
    }).toPass({ timeout: 120_000 });
    if (refused !== null) {
      throw new Error(`promote refused: ${refused}${retryHint()}`);
    }
  }
}
