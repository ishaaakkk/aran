import { test, expect } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { fixturePath, EXPECTED } from '../data';

/**
 * These tests read fixture files directly off disk — no server, no browser —
 * so they exist to guard the *corpus itself*, not the app. Every scenario
 * spec that runs join discovery leans on the fixture value domains described
 * in `../data/index.ts` being exactly what they claim to be; a drifted CSV
 * value here fails silently everywhere else.
 */

type Row = Record<string, string>;

function parseCsv(raw: string): Row[] {
  const lines = raw.trim().split('\n');
  const headers = lines[0].split(',');
  return lines.slice(1).map((line) => {
    const cells = line.split(',');
    const row: Row = {};
    headers.forEach((h, i) => {
      row[h] = cells[i];
    });
    return row;
  });
}

function readCsv(file: string): Row[] {
  return parseCsv(readFileSync(fixturePath(file), 'utf8'));
}

function readJson<T>(file: string): T {
  return JSON.parse(readFileSync(fixturePath(file), 'utf8')) as T;
}

function researcherKeys(): Set<string> {
  const rids = readCsv('researchers.csv').map((r) => r.rid);
  const wikidata = readJson<Array<{ id: string }>>('wikidata_researchers.json');
  return new Set([...rids, ...wikidata.map((r) => r.id)]);
}

test('every fixture file exists', () => {
  for (const f of [
    'acm_papers.csv',
    'semantic_papers.json',
    'dblp_papers.json',
    'researchers.csv',
    'wikidata_researchers.json',
    'venues.parquet',
  ]) {
    expect(existsSync(fixturePath(f)), `${f} is missing`).toBe(true);
  }
});

test('author_ref covers the researcher keys exactly, across every paper source', () => {
  const acm = readCsv('acm_papers.csv');
  const semantic = readJson<Array<{ author_ref: string }>>('semantic_papers.json');
  const dblp = readJson<Array<{ author_ref: string }>>('dblp_papers.json');
  const allAuthors = new Set([
    ...acm.map((r) => r.author_ref),
    ...semantic.map((r) => r.author_ref),
    ...dblp.map((r) => r.author_ref),
  ]);

  const allKeys = researcherKeys();

  // Set equality, not "some overlap" — this is the total-containment signal
  // join discovery is supposed to rank #1.
  expect(allAuthors).toEqual(allKeys);
  expect(allKeys.size).toBe(6);
});

test('reviewer_ref is a genuine near-miss: exactly two matches, ~20% containment, strictly below author_ref', () => {
  const acm = readCsv('acm_papers.csv');
  const allKeys = researcherKeys();

  const reviewers = acm.map((r) => r.reviewer_ref);
  const matches = reviewers.filter((v) => allKeys.has(v));
  const containment = matches.length / reviewers.length;

  // Load-bearing exact values: a later join-discovery spec asserts this
  // pairing ranks *below* the true author_ref <-> rid pairing. If either
  // number drifts, that ranking assertion stops meaning anything.
  expect(new Set(matches).size).toBe(2); // two distinct researchers matched...
  expect(matches.length).toBe(2); //        ...and each appears exactly once
  expect(containment).toBeCloseTo(0.2, 5);

  const authorRefs = [
    ...acm.map((r) => r.author_ref),
    ...readJson<Array<{ author_ref: string }>>('semantic_papers.json').map((r) => r.author_ref),
    ...readJson<Array<{ author_ref: string }>>('dblp_papers.json').map((r) => r.author_ref),
  ];
  const authorContainment = authorRefs.filter((v) => allKeys.has(v)).length / authorRefs.length;

  expect(authorContainment).toBe(1);
  expect(containment).toBeLessThan(authorContainment);
});

test('venue_code is disjoint from every researcher-key alphabet, across every source', () => {
  const acmVenues = readCsv('acm_papers.csv').map((r) => r.venue_code);
  const semanticVenues = readJson<Array<{ venue: { code: string } }>>('semantic_papers.json').map(
    (r) => r.venue.code,
  );
  const dblpVenues = readJson<Array<{ conference: { code: string } }>>('dblp_papers.json').map(
    (r) => r.conference.code,
  );
  const allVenues = new Set([...acmVenues, ...semanticVenues, ...dblpVenues]);

  for (const v of allVenues) expect(v).toMatch(/^V-\d{2}$/);
  // Domain closure: exactly the three declared venues, nothing stray.
  expect(allVenues).toEqual(new Set(['V-01', 'V-02', 'V-03']));

  const allKeys = researcherKeys();
  const reviewerRefs = new Set(readCsv('acm_papers.csv').map((r) => r.reviewer_ref));
  for (const v of allVenues) {
    expect(allKeys.has(v)).toBe(false);
    expect(reviewerRefs.has(v)).toBe(false);
  }
});

test('EXPECTED is grounded in the fixture files, not hand-typed drift', () => {
  const acm = readCsv('acm_papers.csv');
  const semantic = readJson<Array<{ name: string; author_ref: string }>>('semantic_papers.json');
  const dblp = readJson<Array<{ title: string; author_ref: string }>>('dblp_papers.json');

  const acmTitles = new Set(acm.map((r) => r.title));
  const semanticTitles = new Set(semantic.map((r) => r.name));
  const dblpTitles = new Set(dblp.map((r) => r.title));

  // Set equality in BOTH directions, per source. Containment alone would let a
  // fixture grow — as acm_papers.csv did, from three rows to ten — while EXPECTED
  // still described a subset, and every later spec that asserts a full result set
  // or a row count would then be measuring against a corpus that no longer exists.
  expect([...EXPECTED.acmTitles].sort()).toEqual([...acmTitles].sort());
  expect([...EXPECTED.semanticTitles].sort()).toEqual([...semanticTitles].sort());
  expect([...EXPECTED.dblpTitles].sort()).toEqual([...dblpTitles].sort());

  expect(EXPECTED.paperTitles).toEqual([
    ...EXPECTED.acmTitles,
    ...EXPECTED.semanticTitles,
    ...EXPECTED.dblpTitles,
  ]);

  // Counts are DERIVED from the fixtures, never written as literals here: a
  // hardcoded number is exactly what made this test fail when the corpus grew.
  const totalPapers = acm.length + semantic.length + dblp.length;
  expect(EXPECTED.paperTitles).toHaveLength(totalPapers);
  expect(EXPECTED.authorPairs).toHaveLength(totalPapers);

  const totalResearchers =
    readCsv('researchers.csv').length +
    readJson<Array<{ id: string }>>('wikidata_researchers.json').length;
  expect(EXPECTED.affiliationPairs).toHaveLength(totalResearchers);

  // Build (title -> author_ref) from every paper source and (ref -> name)
  // from every researcher source, then prove each EXPECTED pair *is* that
  // join result — not a plausible-looking value someone typed by hand.
  const titleToAuthorRef = new Map<string, string>([
    ...acm.map((r): [string, string] => [r.title, r.author_ref]),
    ...semantic.map((r): [string, string] => [r.name, r.author_ref]),
    ...dblp.map((r): [string, string] => [r.title, r.author_ref]),
  ]);
  const refToName = new Map<string, string>([
    ...readCsv('researchers.csv').map((r): [string, string] => [r.rid, r.full_name]),
    ...readJson<Array<{ id: string; label: string }>>('wikidata_researchers.json').map(
      (r): [string, string] => [r.id, r.label],
    ),
  ]);

  for (const [title, name] of EXPECTED.authorPairs) {
    const ref = titleToAuthorRef.get(title);
    expect(ref, `no paper titled "${title}" in any fixture source`).toBeDefined();
    expect(refToName.get(ref!), `author_ref "${ref}" for "${title}" resolves to a different name`).toBe(
      name,
    );
  }

  // affiliationPairs' names aren't hand-typed guesses either — every one
  // must be a real researcher name drawn from the same fixtures.
  const knownNames = new Set(refToName.values());
  for (const [name] of EXPECTED.affiliationPairs) {
    expect(knownNames.has(name), `"${name}" is not a researcher name in any fixture`).toBe(true);
  }
});
