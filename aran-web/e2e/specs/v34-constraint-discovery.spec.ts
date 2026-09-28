import { readFileSync } from 'node:fs';
import { test, expect, specUser } from '../fixtures';
import { fixturePath } from '../data';

test.use(specUser('v34'));

// Every test builds on the state the one before it left: test 1 promotes and discovers,
// test 2 enforces a candidate and re-promotes. `fullyParallel: true` would scatter them
// across workers and each would find a tenant that had never been set up. The ceiling is
// raised for the same reason B5 raises it — this spec runs two integrate/promote cycles and
// a real LIMA job.
test.describe.configure({ mode: 'serial', timeout: 10 * 60 * 1000 });

/**
 * V34 — the discover → review → enforce loop (Phase 3B), live.
 *
 * The claim under test is that LIMA now discovers over an opaque wrapper's RESOLVED OUTPUT
 * rather than the raw lake bytes. `curated_papers.csv` is built to make that claim falsifiable
 * from the browser: `journal_code → impact_band` is violated across the whole file and holds
 * over `curated = 'yes'` alone (both re-derived from the file below, never trusted from a
 * comment), so a candidate encoding that dependency cannot have come from the raw snapshot.
 *
 * WHAT THE LOOP ACTUALLY PRODUCES, and why test 1 now asserts an EMPTY review list. LIMA is a
 * *tuple-pair* denial-constraint miner: `Schema.buildColumnPairs` only ever builds SELF pairs,
 * and `CSVDataset.sample` only ever draws pairs of DISTINCT tuples, so every discovered
 * constraint has the shape `!(colA EQ colA & colB NE colB & …)` — read as "no two rows agree on
 * colA while disagreeing on colB". `Predicate.toString` drops the tuple identity (there is no
 * t1/t2 in the string), and `IntegrationModelService.translateLimaExpression` is a literal
 * substitution over that string, so the single-tuple predicate it produces —
 * `NOT (colA = colA AND colB <> colB)` — is decided by its operators alone and cannot filter a
 * row.
 *
 * Instantiating a denial constraint at t1 = t2 is a SOUND weakening, so nothing here produces
 * wrong rows — but it is also a *degenerate* one. On one tuple `colA = colA` is TRUE and
 * `colB <> colB` is FALSE, so a candidate carrying any `<>` / `>` / `<` term is a TAUTOLOGY,
 * and the mirror shape (all `=`/`>=`/`<=`, what a miner states for a KEY) is a CONTRADICTION
 * that would quarantine the whole class. Two candidates of exactly that shape persisted here on
 * 2026-08-26 and one was enforced, with no effect on the rows served. Filed as **V43**.
 *
 * **Resolved by two independent defences**, kept together because they fail differently:
 *
 *  1. **Never persisted.** `IntegrationModelService.rejectSelfComparisonCandidate` refuses such a
 *     candidate at the persist choke point, and the run reports honestly that pair-based
 *     dependencies were found which cannot yet be expressed over a single row. Test 1 pins that
 *     outcome — the job completes, no DISCOVERED row appears, and the note says why.
 *  2. **Never enforceable.** A DISCOVERED candidate is evidence a reviewer reads and nothing more:
 *     the enforce checkbox on such a row is DISABLED, and
 *     `IntegrationModelService.setConstraintEnforced` refuses its PATCH with a 400 (audit
 *     CONSTRAINT_DISCOVERY-1, user decision: display-only). This holds for EVERY candidate,
 *     including shapes screen 1 is deliberately loose about, which is why it is kept even though
 *     screen 1 means test 1's list is empty today. Test 2 pins it against a candidate this spec
 *     seeds directly, since discovery no longer produces one.
 *
 * Both are meant to go red the day inter-tuple enforcement lands — at which point the checkbox
 * comes back and this becomes a real accept path.
 *
 * The refusal is deliberately NOT total: a candidate comparing two DIFFERENT columns is a real
 * row predicate and passes straight through (pinned at the unit level in
 * `IntegrationModelServiceDiscoverConstraintsTest`). So test 1's empty expectation is the thing
 * that should go red the day `buildColumnPairs` learns cross-column pairs — at which point the
 * candidate-and-enforce assertions retired into test 2's doc comment come back.
 */

interface Row {
  paper_id: string;
  journal_code: string;
  impact_band: string;
  author_ref: string;
  curated: string;
}

function fixtureRows(): Row[] {
  const lines = readFileSync(fixturePath('curated_papers.csv'), 'utf8').trim().split('\n');
  const headers = lines[0].split(',');
  return lines.slice(1).map((line) => {
    const cells = line.split(',');
    return Object.fromEntries(headers.map((h, i) => [h, cells[i]])) as unknown as Row;
  });
}

/** Does `a → b` hold as a functional dependency over these rows? */
function fdHolds(rows: Row[], a: keyof Row, b: keyof Row): boolean {
  const seen = new Map<string, string>();
  for (const r of rows) {
    const prev = seen.get(r[a]);
    if (prev !== undefined && prev !== r[b]) return false;
    seen.set(r[a], r[b]);
  }
  return true;
}

/*
 * `parseCandidate` / `columnsOf` / `singleTupleTruth` lived here until V43 and are gone with the
 * assertions that used them: they existed to classify a PERSISTED candidate's shape, and since
 * V43 no self-comparison candidate is persisted for them to classify. The classification itself
 * did not disappear — it moved server-side into
 * `IntegrationModelService.rejectSelfComparisonCandidate`, which is where it belongs (it now
 * decides what is stored, not just what a spec says about it) and where it is unit-tested. Bring
 * them back here only if this spec ever needs to reason about a candidate's shape again.
 */

interface Term { left: string; op: string; right: string }

/**
 * Parse a candidate — always `NOT (<term> AND <term> …)` by construction
 * (`translateLimaExpression`) — into its terms, or null if it is not that shape.
 */
function parseCandidate(expression: string): Term[] | null {
  const body = expression.match(/^NOT \((.*)\)$/s)?.[1];
  if (body === undefined) return null;
  const terms: Term[] = [];
  for (const raw of body.split(' AND ')) {
    const m = raw.trim().match(/^(\S+)\s+(=|<>|>=|<=|>|<)\s+(\S+)$/);
    if (!m) return null;
    terms.push({ left: m[1], op: m[2], right: m[3] });
  }
  return terms;
}

/**
 * The truth value this candidate takes on a SINGLE row, or null when it is genuinely
 * contingent (it compares two different columns, so the data decides).
 *
 * Not a stylistic classification: it is the difference between an enforced constraint that
 * filters nothing and one that filters everything, and the enforce path treated them alike —
 * which is why V43 screens on it twice, at persist and at enforce.
 */
function singleTupleTruth(expression: string): boolean | null {
  const terms = parseCandidate(expression);
  if (!terms || terms.length === 0) return null;
  let conjunction = true;
  for (const t of terms) {
    if (t.left !== t.right) return null; // a real comparison — the row decides
    // On one row, a self-comparison is decided by the operator alone.
    const termTruth = t.op === '=' || t.op === '>=' || t.op === '<=';
    conjunction &&= termTruth;
  }
  return !conjunction;
}

/** The wrapper's `WHERE`, appended to the bootstrap-generated projection. */
const CURATED_ONLY = ` WHERE "curated" = 'yes'`;

test('discovery over the wrapper output finds pair dependencies and honestly persists none of them (V43)', async ({
  journey,
  alignments,
  datasets,
}) => {
  // ── the premise, re-derived from the fixture rather than trusted ─────────────────────────
  const rows = fixtureRows();
  const curated = rows.filter((r) => r.curated === 'yes');
  expect(curated.length).toBeGreaterThan(0);
  expect(curated.length).toBeLessThan(rows.length);
  expect(
    fdHolds(rows, 'journal_code', 'impact_band'),
    'the whole file must VIOLATE journal_code → impact_band, or discovering it proves nothing',
  ).toBe(false);
  expect(
    fdHolds(curated, 'journal_code', 'impact_band'),
    'the curated subset must SATISFY journal_code → impact_band, or there is nothing to discover',
  ).toBe(true);
  expect(new Set(rows.map((r) => r.paper_id)).size).toBe(rows.length); // paper_id is a key

  await journey.bootstrapSources({
    project: 'v34-proj',
    repo: 'v34-files',
    sources: [
      { name: 'curated', file: 'curated_papers.csv' },
      { name: 'researchers', file: 'researchers.csv' },
    ],
  });

  // ── the opaque wrapper: the bootstrap projection, restricted to the curated rows ─────────
  await datasets.goto();
  const seeded = await datasets.wrapperSql('curated');
  expect(seeded).toContain('FROM ${SNAPSHOT}');
  await datasets.saveWrapper('curated', seeded + CURATED_ONLY);
  // OPAQUE is the precondition wrapper-output discovery requires — an EXPANDED class is skipped
  // by `discoverConstraints` outright — and it is asserted rather than assumed because what
  // makes it hold is subtler than it looks, and got subtler still when V41 was fixed:
  //
  //  - this save happens BEFORE the integrate/promote below, so the active schema has no
  //    IntegratedResource for `curated` yet and `verify-wrapper`'s lookup (which V41 taught to
  //    resolve a source class through its integrated one) has nothing to resolve to. The verdict
  //    is "verify unavailable: no source view found …" — operational, not a statement about the
  //    wrapper;
  //  - and nothing re-verifies afterwards: compile COPIES the stored verdict into the graph
  //    (`applyWrapperOverrides`), it does not re-run the check.
  //
  // A restricting wrapper is NOT inherently opaque post-V41 — `WrapperExpansion.probe` plans a
  // WHERE quite happily and it round-trips — so if either of those two facts changes, this
  // class earns EXPANDED and this spec must stop expecting candidates at all. That is what this
  // assertion is here to catch.
  expect((await datasets.wrapperExpansionBadge()).text).toContain('Opaque');

  // A join alignment is what mints integrated classes at all (Journey/R5), and the constraints
  // tab lists integrated classes only.
  await alignments.goto();
  await alignments.selectProject('v34-proj');
  await alignments.selectSources('curated', 'researchers');
  await alignments.addAlignment({
    leftProp: 'author_ref', rightProp: 'rid',
    label: 'authorKey', kind: 'join', relationship: 'hasAuthor',
  });
  await alignments.integrate();
  await alignments.promote();

  // ── discover ─────────────────────────────────────────────────────────────────────────────
  await datasets.goto();

  // Post-retirement check (register step 4, closed by removal on 2026-08-26): the row-level
  // "Discover Denial Constraints (LIMA)" modal — which wrote `Dataset.denialConstraints`, a
  // field nothing read — is gone from the datasets list, so the Constraints tab below is the
  // only discovery entry point a user can reach.
  await expect(datasets.legacyDiscoveryButton()).toHaveCount(0);

  await datasets.openConstraints('curated');
  expect(await datasets.constraintClasses()).toEqual(['Int_curated']);
  expect(await datasets.constraints(), 'nothing may pre-exist, or "discovered" proves nothing').toEqual([]);

  // `expectEmptyNote` is what makes the reads below deterministic rather than a race against
  // the un-awaited model reload the product fires after re-enabling the button. It has to be
  // the NOTE and not the row count: "no candidate rows appeared" is true before the job has
  // even reported, so waiting on it would wait for nothing (V43).
  await datasets.discoverConstraints({ expectEmptyNote: true });

  // ── V43: the run completes, finds real dependencies, and persists NOTHING ────────────────
  //
  // LIMA is a row-PAIR miner. `Schema.buildColumnPairs` only ever builds SELF pairs
  // (`ColumnPair(col, col)`) and `CSVDataset.sample` only ever draws pairs of DISTINCT tuples,
  // so every candidate has the shape `!(colA EQ colA & colB NE colB)` — "no two rows agree on
  // colA while differing on colB". `Predicate.toString` drops the tuple identity (there is no
  // t1/t2 in the string) and `translateLimaExpression` is a literal substitution over it, so
  // what USED to be persisted was the single-tuple predicate
  // `NOT (journal_code = journal_code AND impact_band <> impact_band)` — constant on every row.
  //
  // Two candidates of exactly that shape were persisted here on 2026-08-26, one was enforced
  // through its checkbox, and the class served the same thirty rows before and after. Worse than
  // vacuous, as it turned out: under SQL three-valued logic `impact_band <> impact_band` is
  // UNKNOWN wherever `impact_band IS NULL` (whatever `journal_code` is), so the whole predicate
  // is UNKNOWN there — and UNKNOWN satisfies NEITHER the `int_` view's `WHERE (c)` NOR the `q_`
  // view's `WHERE NOT ((c))`. The row is not quarantined; it disappears from both views
  // entirely. This corpus has no NULLs in those columns, which is why the 2026-08-26 run saw a
  // clean no-op rather than missing rows.
  //
  // Since V43 the service refuses to persist a candidate that is a self-comparison in every
  // term, and says so instead of reporting an empty success. What this spec now pins is that
  // honest outcome. It is NOT a claim that discovery is useless: the filter is deliberately
  // non-total (a candidate comparing two DIFFERENT columns passes straight through — see
  // `IntegrationModelServiceDiscoverConstraintsTest`), so when cross-column pairs land in LIMA
  // this expectation should go red and be replaced by the candidate assertions retired below.
  const found = await datasets.constraints();
  console.log('[V34] rows after discovery (expected empty since V43):', JSON.stringify(found));
  expect(
    found,
    'a candidate persisted — did LIMA learn cross-column pairs? Then V43 is closed and this ' +
    'spec should assert the candidate set again (see the retired block in test 2)',
  ).toEqual([]);
  await expect(datasets.discoveredBadges()).toHaveCount(0);

  // ...and the user is told WHY the list is empty, in the server's own words
  // (`IntegrationModelService.discoverySummary`), rather than "complete — review candidates
  // below" over an empty list. This sentence IS the deliverable of V43's second half.
  const note = await datasets.discoveryEmptyNote().innerText();
  console.log('[V34] honest-emptiness note:', note);
  expect(note).toContain('PAIRS of rows');
  expect(note).toContain('nothing was added');

  // Discovery DID find dependencies — it just cannot express them over one row. The count in the
  // note is the only remaining browser-visible evidence that the run had something to work with,
  // so it is asserted non-zero: a run that silently found nothing would otherwise be
  // indistinguishable from this one, and "discovery is quietly broken" is exactly the failure a
  // spec asserting emptiness could stop catching.
  const pairCount = Number(note.match(/found (\d+) depend/)?.[1] ?? '0');
  console.log('[V34] pair dependencies found:', pairCount);
  expect(pairCount, 'discovery persisted nothing AND found nothing — that is not V43, that is broken').toBeGreaterThan(0);

  // ── an honest note about what this spec can no longer prove ─────────────────────────────
  // V34's headline claim is that LIMA reads the wrapper's RESOLVED OUTPUT, not the raw lake
  // bytes, and the way this spec proved it was to read the persisted candidate and show it
  // encoded `journal_code → impact_band` — a dependency FALSE on the raw file (asserted from the
  // fixture at the top) and TRUE on the curated subset the wrapper selects. Since V43 nothing is
  // persisted, so that inference is no longer available from the browser: the count above shows
  // dependencies were found, not WHICH. V34 was closed on the 2026-08-26 run that did observe
  // them, and it stays closed on that evidence; this spec no longer re-proves it, and the
  // register should say so rather than let a green run imply otherwise.
  expect(await datasets.constraintClasses()).toEqual(['Int_curated']);
});

/**
 * RETIRED BY V43's persist screen, and kept as a named test.
 *
 * What this used to do: read the discovered `journal_code → impact_band` candidate out of the
 * review list, enforce it through its checkbox (or assert that the checkbox is disabled and the
 * PATCH refused), recompile, re-promote, and watch the class serve exactly the same thirty rows.
 * Every step depended on a candidate having been PERSISTED, and none is now: a candidate that is a
 * self-comparison in every term never reaches the review list.
 *
 * **Both V43 defences ship; only one of them is reachable from a browser.** The composition is
 * `never-persist` (screen 1) AND `refuse-enforce on DISCOVERED` (screen 2). Screen 1 wins first,
 * so live discovery can no longer produce a DISCOVERED row for screen 2 to refuse — which makes
 * the enforce refusal unobservable end to end, not unpinned. It is pinned at the three levels
 * where a DISCOVERED row CAN be fabricated:
 *
 *  - `DatasetConstraintsTab.test.tsx` — the checkbox is disabled and the row renders
 *    `constraint-enforce-blocked` / "evidence only";
 *  - `IntegrationModelServiceDiscoverConstraintsTest` — `setConstraintEnforced` throws for a
 *    DISCOVERED row and not for a MANUAL one;
 *  - `DiscoveredConstraintRoundTripIT` — the same refusal through the real persistence adapter.
 *
 * This test is not deleted because the enforce path itself is untouched and still exercised by the
 * MANUAL constraints specs, and because it should come BACK, largely as it was, the day LIMA gains
 * cross-column pairs: a candidate would persist again, and
 * the disabled checkbox would become observable here. The assertions worth resurrecting with it
 * are the disabled control, the row count before/after, and the single leaf scan.
 *
 * What survives here is the half that never depended on a persisted candidate at all: OQL cannot
 * express a column-to-column comparison, so V34's step 3 (and the V30 cross-slice it promised)
 * is unearnable from a discovered constraint whatever happens to the translation. That is a
 * property of the grammar, not of V43, and it is why closing V43 alone would not close step 3.
 */
test('OQL cannot express a column-to-column WHERE, so V34 step 3 is unearnable from a discovered constraint', async ({
  workbench,
}) => {
  const curatedRowCount = fixtureRows().filter((r) => r.curated === 'yes').length;

  // The rows the promoted, wrapper-restricted class serves. Nothing below changes them —
  // that is the point: a discovered candidate can reach neither the review list nor the
  // int_/q_ views (V43's two screens, in that order).
  await workbench.goto();
  const classNames = await workbench.waitForCatalogClasses();
  expect(classNames).toContain('Int_curated');
  await workbench.run('SELECT Int_curated.journal_code, Int_curated.impact_band FROM Int_curated');
  expect(
    (await workbench.rows()).length,
    'the wrapper must still be restricting the class to the curated rows',
  ).toBe(curatedRowCount);

  // The candidate whose enforce control used to be asserted dead here is no
  // longer persisted at all (see this test's doc comment), so the disabled-checkbox assertions
  // moved to DatasetConstraintsTab.test.tsx, where a DISCOVERED row can be constructed. What is
  // still worth re-deriving in the browser is WHY that row would have been dead: the expression
  // is a tautology on a single tuple.
  expect(singleTupleTruth('NOT (journal_code = journal_code AND impact_band <> impact_band)'))
    .toBe(true);

  // ── why the register's step 3 cannot close, watched rather than argued ───────────────────
  // Step 3 asks for "a query whose WHERE the constraint implies". There is no such OQL query,
  // for a second reason independent of V43's tautology: OQL's grammar is
  // `Class.prop OP literal` (`OqlParser.parseFilter` → `parseLiteral`), so a column-to-column
  // comparison — the only shape a LIMA candidate ever has — is not expressible at all. The
  // parser rejects it, which is what makes the V30 cross-slice unearnable from a DISCOVERED
  // constraint no matter how the translation is fixed.
  // (The page is on /datasets from the constraint assertions above — navigate back first, or
  // `run` waits on an oql-editor that is not in the DOM until the test times out.)
  await workbench.goto();
  await workbench.waitForCatalogClasses();
  await workbench.run(
    `SELECT Int_curated.journal_code FROM Int_curated ` +
    `WHERE Int_curated.journal_code <> Int_curated.impact_band`,
  );
  const parseError = await workbench.errorText();
  console.log('[V34] OQL refusing a column-to-column WHERE:', parseError);
  expect(parseError, 'OQL accepted a column-to-column WHERE — the grammar grew').toContain(
    'Expected literal',
  );
});
