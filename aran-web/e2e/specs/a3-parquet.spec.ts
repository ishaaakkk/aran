import { readFileSync } from 'node:fs';
import { test, expect, specUser } from '../fixtures';
import { EXPECTED, fixturePath } from '../data';

test.use(specUser('a3'));

/**
 * A3 — a Parquet source. Exercises ParquetBootstrap and the read_parquet
 * branch of the SnapshotResolver, then joins it to the CSV papers on
 * venue_code so the parquet data has to survive into a join.
 *
 * R7: acm_papers.csv has TEN rows, so the join below returns ten rows.
 * EXPECTED does not carry venue data (it's paper/researcher
 * only), so the venue breakdown here is derived directly from the on-disk
 * fixtures instead — acm_papers.csv's venue_code column, counted below, and
 * venues.parquet's fixed code->name mapping (generate-parquet.py, committed
 * alongside the binary: V-01/ISWC, V-02/SIGMOD, V-03/VLDB) — never a number
 * copied from the plan.
 */
const VENUE_NAMES: Record<string, string> = { 'V-01': 'ISWC', 'V-02': 'SIGMOD', 'V-03': 'VLDB' };

function acmVenueCodes(): string[] {
  const lines = readFileSync(fixturePath('acm_papers.csv'), 'utf8').trim().split('\n');
  const headers = lines[0].split(',');
  const venueIdx = headers.indexOf('venue_code');
  return lines.slice(1).map((line) => line.split(',')[venueIdx]);
}

test('parquet source bootstraps and joins to papers', async ({
  journey, alignments, workbench,
}) => {
  await test.step('Load two sources: a CSV of papers and a Parquet file of venues', async () => {
    await journey.bootstrapSources({
      project: 'a3-proj',
      repo: 'a3-files',
      sources: [
        { name: 'acm', file: 'acm_papers.csv' },
        { name: 'venues', file: 'venues.parquet' },
      ],
    });
  });

  await test.step('Put the papers and venues schemas side by side', async () => {
    await alignments.goto();
    await alignments.selectProject('a3-proj');
    await alignments.selectSources('acm', 'venues');
  });

  await test.step('Link each paper to the venue it appeared at', async () => {
    await alignments.addAlignment({
      leftProp: 'venue_code',
      rightProp: 'venue_code',
      label: 'venueKey',
      kind: 'join',
      relationship: 'atVenue',
    });
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

  const { acmClass, venuesClass } = await test.step('Find the new integrated classes in the catalog', async () => {
    // R6: acm and venues are touched ONLY by a JOIN alignment (no CLASS
    // alignment gave either side its own label), so SchemaIntegrator's
    // join-auto-promote path applies and the minted names are
    // `Int_<sourceClassLocalName>`, NOT the dataset names `Acm`/`Venues` the
    // plan assumed. Discovered from the live catalog rather than hardcoded.
    // RELOADS between attempts rather than merely polling. The catalog is fetched
    // once on mount and its failures are swallowed to the console (F15), so a page
    // that missed it stays empty however long you wait — widening the poll window
    // cannot help, which is why an earlier 90s poll still returned zero classes.
    // Under the full suite's four-way concurrency that miss is common; running
    // this spec alone it never happens at all.
    const classNames = await workbench.waitForCatalogClasses();
    const acmClass = classNames.find((n) => /acm/i.test(n));
    const venuesClass = classNames.find((n) => /venues/i.test(n));
    expect(acmClass, `no acm-ish class in catalog: ${classNames.join(', ')}`).toBeDefined();
    expect(venuesClass, `no venues-ish class in catalog: ${classNames.join(', ')}`).toBeDefined();
    // R6's predicted exact names — asserted, not assumed, so this goes red if
    // the auto-promote naming convention ever changes.
    expect(acmClass).toBe('Int_acm');
    expect(venuesClass).toBe('Int_venues');
    return { acmClass, venuesClass };
  });

  await test.step('Query each paper together with the venue it was published at', async () => {
    await workbench.run(
      `SELECT ${acmClass}.title, ${venuesClass}.venue_name FROM ${acmClass} -[atVenue]-> ${venuesClass}`,
    );
  });

  await test.step('Read back every paper beside its venue, and how many landed at each', async () => {
    const venueCodes = acmVenueCodes();
    const rows = await workbench.rows();
    // Every ACM row has a matching venue (the fixtures' venue_code domains are
    // closed over the same three codes — see corpus.spec.ts), so the join drops
    // nothing: one output row per ACM row.
    expect(rows.length).toBe(venueCodes.length);

    const titles = await workbench.column('title');
    expect(titles.sort()).toEqual([...EXPECTED.acmTitles].sort());

    const names = await workbench.column('venue_name');
    const expectedCounts = venueCodes.reduce<Record<string, number>>((acc, code) => {
      const name = VENUE_NAMES[code];
      acc[name] = (acc[name] ?? 0) + 1;
      return acc;
    }, {});
    for (const [name, count] of Object.entries(expectedCounts)) {
      expect(names.filter((n) => n === name), `venue "${name}"`).toHaveLength(count);
    }
    // Closed over exactly the three known venue names — nothing stray.
    expect(new Set(names)).toEqual(new Set(Object.values(VENUE_NAMES)));
  });
});
