import { test, expect, specUser } from '../fixtures';
import { EXPECTED } from '../data';

test.use(specUser('v32'));

// Tests 2 and 3 query the schema test 1 promotes, in the same tenant — `fullyParallel: true`
// would scatter them across workers and they would find a tenant nothing had set up.
// The ceiling is raised for the same reason B5 raises it: a full journey plus two
// integrate/promote cycles plus four wrapper saves, each of which round-trips
// `verify-wrapper` through the orchestrator.
test.describe.configure({ mode: 'serial', timeout: 10 * 60 * 1000 });

/**
 * V32 — the wrapper-expansion loop (Phase 3A), live, end to end.
 *
 * V41: `verify-wrapper` once looked its class up with `cat.classes().get(classIri)`, but
 * `MappingCatalog.classes()` is keyed by the INTEGRATED class IRI while the endpoint (and the
 * Wrapper tab behind it) passes the SOURCE class IRI, so the lookup could never succeed.
 * `QueryEngineOperator.sourceViewFor` now resolves the integrated class that owns the
 * `(datasetId, source class)` membership. The guard that remains is
 * `not.toContain('verify unavailable')` on a real reason: if the lookup failed again, the reason
 * would say exactly that while the badge still looked plausibly OPAQUE.
 *
 * Verify also applies the planner's STRUCTURAL gate (`WrapperExpansion.unrebuildablePart`, one
 * shared definition), so an aggregate, which round-trips its own unparse and used to earn
 * EXPANDED, is refused with the planner's own reason.
 *
 * What the tests watch:
 *
 *  - the save round-trip itself, and a verdict computed on every save (step 1);
 *  - the badge FLIP: a pure projection earns "Optimizable — verified round-trip", a wrapper that
 *    cannot expand earns a specific reason about the wrapper, and an aggregate is refused as
 *    structurally unrebuildable rather than over-promised (step 2);
 *  - the 512-char reason cap and the 80-char chip preview, on a REAL reason string — and on an
 *    *operational-free* one, produced by a wrapper that genuinely cannot expand rather than by
 *    the lookup failing (V32's second "most likely to be wrong", and the half of it V41 kept
 *    unreachable);
 *  - the expanded branch's plan leaf: the raw `src_<dataset>` relation, not the opaque `u_`
 *    member view — with the rows still correct through it (step 3);
 *  - the class-level refusal: one enforced denial constraint sends the same class back to `u_`,
 *    while the badge keeps saying "Optimizable", because neither of the two wired re-verify
 *    triggers (compile, dataset refresh) fires on enforcing a constraint (step 4).
 *
 * Still only checked at plan time (registered residue): the enforced-denial-constraint gate and
 * the DECIMAL/TIMESTAMP cast ladder — a badge can say Optimizable for a class the planner will
 * refuse on those two grounds. Step 4 is that residue, asserted rather than assumed.
 */

/** `EXPECTED.acmTitles` is a readonly tuple under `as const`; copy to use `includes`. */
const ACM_TITLES: string[] = [...EXPECTED.acmTitles];

/** How much of the reason the chip shows inline before eliding (DatasetWrapperTab). */
const REASON_PREVIEW_LENGTH = 80;

/** The badge copy for a verified round-trip (DatasetWrapperTab.wrapperExpansionBadge). */
const OPTIMIZABLE = 'Optimizable — verified round-trip';

/**
 * A wrapper that is valid to SAVE — `WrapperSqlValidator` accepts it (a single SELECT over
 * `${SNAPSHOT}`, and Calcite parses it, which is what the save path requires), it keeps every
 * alias the class reads, and DuckDB binds it — but it calls `list_value`, a DuckDB function
 * Calcite does not know, so it cannot plan over its `src_` relation. This is the "cannot expand"
 * half of the register's step 2: a reason about the WRAPPER, not about the check being
 * unavailable. (It used to name a column the snapshot lacks; a wrapper that does not bind at all
 * is now refused at save, since every query over the class would fail the same way.)
 */
const unplannableOf = (projection: string) =>
  projection.replace(/\s+FROM \$\{SNAPSHOT\}/, (from) => ', list_value(1) AS "unplannable_extra"' + from);

/**
 * An aggregate: valid to save, and it collapses duplicate rows, so it is not a shape-preserving
 * projection of the snapshot. `SELECT DISTINCT` over the seeded projection rather than
 * `count(*)`: it keeps every aliased column the class reads, and a wrapper that drops one is
 * refused at save (the u_ view selects them by name, so every query would fail) — which would
 * test that refusal, not this verdict.
 *
 * The FINGERPRINT alone cannot see that. `RoundTrip.verify` compares the user's text to the
 * OPTIMIZER'S OWN UNPARSE of that same text — "does Calcite's rendering of this wrapper produce
 * the wrapper's rows", not "is this wrapper a projection of the snapshot" — and an aggregate is a
 * faithful expansion of itself, so it round-trips. What refuses it is
 * the STRUCTURAL gate, `WrapperExpansion.unrebuildablePart`, which verify now applies as well as
 * the planner: one shared definition, so the badge and `OqlPlanner.pushExpandedMember` cannot
 * disagree. An earlier revision of this spec asserted the aggregate earned EXPANDED, which was
 * true before that gate reached verify.
 */
const aggregateOf = (projection: string) => projection.replace(/^(SELECT) /, '$1 DISTINCT ');

/** Enforced in test 3. A VARCHAR column against a string literal on purpose: every column of a
 *  CSV-bootstrapped class is `xsd:string`, so this cannot trip V38 (a string literal against a
 *  typed column crashes the planner once any constraint on the class is enforced). */
const DC_EXPRESSION = `title <> 'Federated Query Processing'`;

test('a wrapper save earns a real verdict: the projection is Optimizable, the unplannable and the aggregate each say why', async ({
  journey,
  alignments,
  datasets,
}) => {
  await journey.bootstrapSources({
    project: 'v32-proj',
    repo: 'v32-files',
    sources: [
      { name: 'acm', file: 'acm_papers.csv' },
      { name: 'researchers', file: 'researchers.csv' },
    ],
  });

  // A join alignment is what mints integrated classes at all — a lone source is never
  // promoted into one (see Journey/R5) — and the resolution V41 added is specifically from a
  // source class to the IntegratedResource it was promoted into, so the model must be compiled
  // and promoted before the wrapper is saved or there is nothing to resolve to.
  await alignments.goto();
  await alignments.selectProject('v32-proj');
  await alignments.selectSources('acm', 'researchers');
  await alignments.addAlignment({
    leftProp: 'author_ref', rightProp: 'rid',
    label: 'authorKey', kind: 'join', relationship: 'hasAuthor',
  });
  await alignments.integrate();
  await alignments.promote();

  // ── the wrapper the bootstrap generated: a pure, fully-mapped projection ────────────────
  await datasets.goto();
  const seeded = await datasets.wrapperSql('acm');
  // The exact shape Phase 3A's EXPANDED verdict is meant for: one SELECT, one alias per
  // property, straight off ${SNAPSHOT}, no DECIMAL/TIMESTAMP cast anywhere (acm_papers.csv is
  // all strings and integers, and CSV bootstrap types every column as string by design).
  expect(seeded).toMatch(/^SELECT /);
  expect(seeded).toContain('FROM ${SNAPSHOT}');
  expect(seeded.toLowerCase()).not.toContain('decimal');
  expect(seeded.toLowerCase()).not.toContain('timestamp');

  // ── save it unchanged: the verdict is computed on save, not on preview ───────────────────
  await datasets.saveWrapper('acm', seeded);
  const verdict = await datasets.wrapperExpansionBadge();
  console.log('[V32] badge:', verdict.text, '\n[V32] reason:', verdict.title);

  // Step 2's positive half, and the whole point of the V41 fix: the round-trip really ran —
  // COUNT(*) plus a row-hash, twice, against the real snapshot — and it matched.
  expect(
    verdict.text,
    'the pure projection must earn the verified-round-trip badge (V41)',
  ).toBe(OPTIMIZABLE);
  // EXPANDED carries no reason; there is nothing to explain about a match.
  expect(verdict.title).toBeNull();

  // The catalog the (now successful) lookup ran against exposes this dataset under the
  // integrated class the join minted — the other half of V41's mismatch, from the same browser
  // session, kept because it is what makes the fix's *mechanism* visible rather than only its
  // outcome.
  await datasets.openConstraints('acm');
  expect(await datasets.constraintClasses()).toEqual(['Int_acm']);

  // ── a wrapper that genuinely cannot expand: a DIFFERENT, specific reason ─────────────────
  await datasets.saveWrapper('acm', unplannableOf(seeded));
  const unplannable = await datasets.wrapperExpansionBadge();
  console.log('[V32] unplannable badge:', unplannable.text, '\n[V32] reason:', unplannable.title);

  expect(unplannable.text).not.toBe(OPTIMIZABLE);
  expect(unplannable.text).toContain('Opaque — ');
  expect(unplannable.text).not.toBe('Opaque — not yet verified');
  const reason = unplannable.title;
  expect(reason, 'an OPAQUE verdict must carry a reason a person can act on').not.toBeNull();
  // The badge now discriminates: this reason is about the WRAPPER, and the two saves no longer
  // produce the same string (the projection produces none at all).
  expect(reason!).toContain('does not plan cleanly over its src_ relation');
  // V41's own signature. If the catalog lookup ever misses again, this is what it would say —
  // and it would say it while the badge still looked like a plausible verdict.
  expect(
    reason!,
    'verify-wrapper could not find the source view again — V41 has regressed',
  ).not.toContain('verify unavailable');

  // ── the reason-text contract: 512 server-side, 80 in the chip, all of it in the tooltip ──
  // V32's second "most likely to be wrong", now exercised on the reason that was out of reach
  // while V41 was open: a genuine "this wrapper cannot expand", not the lookup failing.
  expect(reason!.length).toBeLessThanOrEqual(512);
  expect(reason!.length).toBeGreaterThan(REASON_PREVIEW_LENGTH); // or the elision below is vacuous
  expect(
    unplannable.text.endsWith('…'),
    `chip did not elide a ${reason!.length}-char reason`,
  ).toBe(true);
  expect(unplannable.text).toBe(`Opaque — ${reason!.slice(0, REASON_PREVIEW_LENGTH)}…`);

  // ── an aggregate: the fingerprint says yes, the STRUCTURAL gate says no ──────────────────
  // DISTINCT round-trips its own unparse (same rows either way), so the fingerprint alone would
  // say EXPANDED — WrapperExpansion.unrebuildablePart is what refuses it, with the planner's own
  // reason. This is verify predicting the planner rather than over-promising a badge.
  await datasets.saveWrapper('acm', aggregateOf(seeded));
  const aggregateVerdict = await datasets.wrapperExpansionBadge();
  console.log('[V32] aggregate badge:', aggregateVerdict.text, '\n[V32] reason:', aggregateVerdict.title);
  const aggregateReason = aggregateVerdict.title!;
  expect(aggregateVerdict.text).not.toBe(OPTIMIZABLE);
  expect(aggregateReason, 'the verdict predicts the planner, not just the fingerprint')
    .toContain('not a shape-preserving projection');
  expect(aggregateReason).toContain('Aggregate');

  // ── restore, and prove the save round-trip is real rather than a rendered guess ──────────
  await datasets.saveWrapper('acm', seeded);
  expect(
    (await datasets.wrapperExpansionBadge()).text,
    'the verdict follows the text — restoring the projection restores EXPANDED',
  ).toBe(OPTIMIZABLE);
  await datasets.close();
  expect(await datasets.wrapperSql('acm')).toBe(seeded);

  // ── carry the verdict into the schema graph, which is the only place the planner reads it ─
  // The verdict persists on `model_source` at save; compile COPIES it into the graph as
  // `nx:wrapperExpansion` (`SchemaIntegrationOperator.applyWrapperOverrides` — a copy, never a
  // re-verify). Test 1 promoted BEFORE these saves, so without this second cycle the active
  // graph still carries the pre-save OPAQUE and test 2 would read a `u_` leaf for reasons that
  // have nothing to do with expansion.
  await alignments.goto();
  await alignments.selectProject('v32-proj');
  await alignments.integrate();
  await alignments.promote();
});

// Test 1 recompiles and promotes after the wrapper saves, so the graph carries the EXPANDED stamp,
// and the compile tail re-verifies before stamping (IntegrationModelService.refreshWrapperVerdicts).
// `src_` is therefore what this catalog must plan, not the opaque `u_` view.
test('the promoted class plans through its wrapper\'s own src_ relation, and the rows survive it', async ({
  workbench,
}) => {
  await workbench.goto();
  const classNames = await workbench.waitForCatalogClasses();
  expect(classNames).toContain('Int_acm');

  await workbench.run('SELECT Int_acm.title FROM Int_acm');

  // Correctness first: an expanded branch that returns different rows is worse than no
  // expansion at all, and the plan shape below is only interesting if this holds.
  const titles = await workbench.column('title');
  expect([...titles].sort()).toEqual([...ACM_TITLES].sort());

  // The register's step 3, read off the live plan shape: an EXPANDED wrapper bottoms out in the
  // raw `src_<datasetId>` relation (its own plan, spliced in), where an OPAQUE one scans the
  // `u_` member view whose body is the wrapper SQL. The verdict lives on the model_source ROW;
  // the planner reads the GRAPH's stamp, which only a compile writes — which is why test 1 ends
  // with a second integrate+promote. Without it this leaf would be `u_` for a reason that has
  // nothing to do with expansion.
  const scans = await workbench.planScans();
  console.log('[V32] plan:', JSON.stringify(await workbench.planNodes()));
  expect(scans, `expected exactly one leaf scan, got ${JSON.stringify(scans)}`).toHaveLength(1);
  expect(
    scans[0],
    'the expanded branch is not planning — the leaf is still the opaque u_ member view',
  ).toMatch(/^src_/);
  expect(scans[0]).not.toMatch(/^u_/);
});


test('one enforced denial constraint sends the same class back to its opaque u_ member view', async ({
  datasets,
  workbench,
}) => {
  // The class-level refusal, which is correctness rather than fidelity: a class's constraints
  // are enforced INSIDE each `u_` member's body, and an expansion over `src_` reads straight
  // past that filter — the quarantined rows would simply reappear. `pushExpandedMember` refuses
  // outright rather than approximating, and this is that refusal reaching the live planner.
  await datasets.goto();
  await datasets.openConstraints('acm');
  expect(await datasets.constraintClasses()).toEqual(['Int_acm']);
  await datasets.addConstraint(DC_EXPRESSION);
  await datasets.setConstraintEnforced(DC_EXPRESSION, true);

  await workbench.goto();
  await workbench.waitForCatalogClasses();
  await workbench.run('SELECT Int_acm.title FROM Int_acm');

  const scans = await workbench.planScans();
  console.log('[V32] plan under an enforced DC:', JSON.stringify(await workbench.planNodes()));
  expect(scans, `expected exactly one leaf scan, got ${JSON.stringify(scans)}`).toHaveLength(1);
  expect(
    scans[0],
    'the expanded branch planned under an enforced denial constraint — the filter lives in the'
      + ' u_ view body an expansion reads straight past',
  ).toMatch(/^u_/);
  expect(scans[0]).toContain('Int_acm');

  // ...and the constraint really is filtering, so the fallback is not being credited for a
  // no-op: the quarantined row is gone, and every other row is still there.
  const titles = await workbench.column('title');
  expect([...titles].sort())
    .toEqual(ACM_TITLES.filter((t) => t !== 'Federated Query Processing').sort());

  // The badge does NOT change. Two re-verify triggers are wired (a compile's tail, and a dataset
  // refresh's downgrade); enforcing a constraint is neither, and the verdict is about the WRAPPER
  // while this refusal is about the CLASS. So "Optimizable" here means "this wrapper
  // round-tripped", never "this class is being expanded right now". Since the A1 truth pass the
  // tab says so in a line beside the badge (`dataset-wrapper-expansion-scope`, pinned in
  // DatasetWrapperTab.test.tsx); the badge text itself is unchanged, which is what this pins.
  await datasets.goto();
  await datasets.wrapperSql('acm'); // opens the dataset on its Wrapper tab, where the badge lives
  expect((await datasets.wrapperExpansionBadge()).text).toBe(OPTIMIZABLE);
});
