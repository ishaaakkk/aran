import { test, expect, specUser } from '../fixtures';
import { EXPECTED } from '../data';

test.use(specUser('c1'));

/**
 * C1 — discovery for real, through the live freyja sidecar.
 *
 * UNVERIFIED: written against the live product code (SchemaIntegrator,
 * MappingCatalogOperator, QueryEngineOperator, JoinDiscoveryOperator) but
 * never run against a real stack — this environment's Docker cannot pull
 * images (registry hosts are blocked by egress policy).
 *
 * Class names: no CLASS alignment is declared, only a JOIN, so both sides
 * auto-promote (SchemaIntegrator.integrateJoinProperty's "Auto-promote domA/
 * domB to integrated class" branch) to `Int_<sourceClassLocalName>` —
 * `Int_acm` / `Int_researchers`, not `Acm`/`Researchers`. This is the same
 * convention `b1-simple-join.spec.ts` already asserts live against these
 * same two fixtures (R6/D2), so it is cross-checked, not a fresh guess —
 * still read off `workbench.waitForCatalogClasses()` rather than hardcoded,
 * matching B1's own reasoning for doing so.
 *
 * Relationship name: `handleAcceptCandidates` (AlignmentsView.tsx) packs only
 * `cand.label` — no `|relationship|direction` suffix, unlike a manually typed
 * alignment (see B1's explicit `relationship: 'hasAuthor'`). freyja's
 * candidate label is minted by `JoinDiscoveryOperator` as
 * `"join_" + colA + "_" + colB` = `join_author_ref_rid` (`iriA` always comes
 * from `datasetIdA`/left, `iriB` from `datasetIdB`/right — confirmed by
 * reading the operator, not assumed). With no `|` in the label,
 * `SchemaIntegrator.integrateJoinProperty` falls through to
 * `joinName = joinLabel + "_join"` = `join_author_ref_rid_join`, which is
 * what `MappingCatalogOperator`'s QUERY 2 and `QueryEngineOperator.catalog()`
 * expose as the join property's `name`. Looked up here via
 * `workbench.catalogRelationships()` instead of hardcoded, so the spec fails
 * loudly if this derivation is wrong rather than silently querying a
 * relationship name that doesn't exist.
 *
 * Row count: acm_papers.csv has ten rows, all
 * with an author_ref in R-001..R-003 — total containment against
 * researchers.rid — so the join is exactly ten pairs, one per paper. Derived
 * from EXPECTED, same as B1.
 */

// EXPECTED.acmTitles is a readonly tuple of string literals under `as const`,
// so `.includes(aString)` does not type-check against it directly. Copy it —
// same fix b1-simple-join.spec.ts uses for the identical constraint.
const ACM_TITLES: string[] = [...EXPECTED.acmTitles];
const acmPairs = EXPECTED.authorPairs.filter(([title]) => ACM_TITLES.includes(title));

/** Stable, comparable form for a whole result row. */
function rowKey(cells: readonly string[]): string {
  return cells.join(' | ');
}

test('freyja ranks the true join in the top 3 and it integrates', async ({
  journey, datasets, alignments, workbench,
}) => {
  await test.step('Load two sources: papers and researchers', async () => {
    await journey.bootstrapSources({
      project: 'c1-proj',
      repo: 'c1-files',
      sources: [
        { name: 'acm', file: 'acm_papers.csv' },
        { name: 'researchers', file: 'researchers.csv' },
      ],
    });
  });

  await test.step('Profile both sources so their columns can be compared', async () => {
    // Discovery scores over profiles, so both sides must be profiled first.
    await datasets.goto();
    await datasets.runProfiling('acm');
    await datasets.goto();
    await datasets.runProfiling('researchers');
  });

  await test.step('Put the two source schemas side by side', async () => {
    await alignments.goto();
    await alignments.selectProject('c1-proj');
    await alignments.selectSources('acm', 'researchers');
  });

  await test.step('Ask Aran to discover how the two sources connect', async () => {
    await alignments.runDiscovery();
  });

  await test.step('Review the ranked suggestions and find the real link near the top', async () => {
    const ranked = await alignments.suggestions();
    expect(ranked.length).toBeGreaterThan(0);

    const top3 = ranked.slice(0, 3);
    expect(
      top3.some((s) => s.left === 'author_ref' && s.right === 'rid'),
      `author_ref → rid missing from top 3: ${JSON.stringify(ranked)}`,
    ).toBe(true);
  });

  await test.step('Accept the suggested link between papers and researchers', async () => {
    await alignments.acceptSuggestions([['author_ref', 'rid']]);
  });

  await test.step('Integrate the two sources into one schema', async () => {
    await alignments.integrate();
  });

  await test.step('Publish the integrated schema', async () => {
    await alignments.promote();
  });

  await test.step('Open the query workbench', async () => {
    await workbench.goto();
  });

  const { acmClass, researchersClass } = await test.step('Find the new integrated classes in the catalog', async () => {
    // Discover the real class names, then pin them exactly — same pattern as
    // B1/A3/A5, so this goes red if the auto-promote convention ever changes.
    const classNames = await workbench.waitForCatalogClasses();
    const acmClass = classNames.find((n) => /acm/i.test(n));
    const researchersClass = classNames.find((n) => /researcher/i.test(n));
    expect(acmClass, `no acm-ish class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(researchersClass, `no researchers-ish class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(acmClass).toBe('Int_acm');
    expect(researchersClass).toBe('Int_researchers');
    return { acmClass, researchersClass };
  });

  const rel = await test.step('Look up the relationship the accepted suggestion created', async () => {
    // The discovery-accepted join's relationship name — NOT hardcoded, since
    // the accept path packs no explicit relationship (see header). Looked up
    // by the edge between the two classes just pinned above.
    const relationships = await workbench.catalogRelationships();
    const rel = relationships.find(
      (r) =>
        (r.from === acmClass && r.to === researchersClass) ||
        (r.from === researchersClass && r.to === acmClass),
    );
    expect(
      rel,
      `no relationship between ${acmClass} and ${researchersClass}: ${JSON.stringify(relationships)}`,
    ).toBeDefined();
    return rel;
  });

  await test.step('Query papers and authors across the discovered relationship', async () => {
    const path =
      rel!.from === acmClass
        ? `${acmClass} -[${rel!.name}]-> ${researchersClass}`
        : `${researchersClass} -[${rel!.name}]-> ${acmClass}`;
    await workbench.run(`SELECT ${acmClass}.title, ${researchersClass}.full_name FROM ${path}`);
  });

  await test.step('Read back every paper beside its author', async () => {
    const rows = await workbench.rows();
    expect(rows.length).toBe(acmPairs.length);

    // Read in one pass so row order lines the two columns up — zipping two
    // separate column() reads and sorting one in place is how F22b faked a
    // broken join (each column's multiset was right while every pair was
    // wrong). Same technique as B1.
    expect(rows.map(rowKey).sort()).toEqual(acmPairs.map(rowKey).sort());

    // Names independently, so a regression that drops one researcher entirely
    // says so directly rather than only via the pair check above.
    const names = rows.map(([, name]) => name);
    expect([...new Set(names)].sort()).toEqual([...new Set(acmPairs.map(([, n]) => n))].sort());
  });
});
