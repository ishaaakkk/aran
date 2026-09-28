/// <reference types="node" />
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { SCENARIO_NOTE_TITLE, TOURS, tourAnchorIds, tourForPath, tourSteps } from './tourRegistry';

/**
 * Every route App.tsx declares, read from the file rather than a hand list: the old hand list
 * omitted the detail routes, so the Guided tour button silently vanished on `/datasets/:id` with
 * this test still green (UI audit J1-F12).
 */
function routedPaths(): string[] {
  const app = readFileSync(join(process.cwd(), 'src', 'App.tsx'), 'utf8');
  return [...app.matchAll(/<Route\s+path="([^"]+)"/g)].map((m) => m[1]);
}

/** Routes with no tour, and why. Anything else App.tsx declares must resolve to one. */
const UNTOURED: Record<string, string> = {
  '/login': 'public — nobody is signed in to replay a tour',
  '/offline': 'public — the diagnostics page, not a workspace screen',
  '/admin': 'redirect to /admin/users',
  '/admin/:section': 'administration has its own shell without the rail that offers tours',
  '*': 'catch-all redirect',
};

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return /\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

const allText = (tour: (typeof TOURS)[string]) =>
  tour.steps.map((s) => `${s.popover?.title ?? ''} ${s.popover?.description ?? ''}`).join('\n');

describe('tourRegistry', () => {
  it('covers every routed workspace page, detail routes included', () => {
    const routes = routedPaths();
    expect(routes.length).toBeGreaterThan(5);
    const untoured = routes
      .filter((r) => !(r in UNTOURED))
      .filter((r) => tourForPath(r.replace(/:[A-Za-z]+/g, 'sample-id')) === null);
    expect(untoured, `routes with no tour: ${untoured.join(', ')}`).toEqual([]);
  });

  it('resolves a detail route to its list page tour', () => {
    expect(tourForPath('/datasets/3f2a')?.id).toBe('datasets');
  });

  it('gives every tour a unique id', () => {
    const ids = Object.values(TOURS).map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('gives every step a title and a description', () => {
    for (const [path, tour] of Object.entries(TOURS)) {
      expect(tour.steps.length, `${path} has no steps`).toBeGreaterThan(0);
      for (const step of tour.steps) {
        expect(step.popover?.title, `${path}: step missing title`).toBeTruthy();
        expect(step.popover?.description, `${path}: step missing description`).toBeTruthy();
      }
    }
  });

  it('anchors only via dedicated tour ids, never a data-testid', () => {
    for (const tour of Object.values(TOURS)) {
      for (const step of tour.steps) {
        if (step.element === undefined) continue;
        expect(typeof step.element).toBe('string');
        expect(step.element as string).toMatch(/^#tour-[a-z0-9-]+$/);
      }
    }
  });

  /**
   * The guard that would have caught the original plan's central defect: every
   * selector pointed at a `data-testid`, so nothing resolved and driver.js
   * degraded each step into an unanchored popover with the build still green.
   */
  it('every anchor id is actually rendered somewhere in src/', () => {
    const sources = sourceFiles(join(process.cwd(), 'src'))
      .map((file) => readFileSync(file, 'utf8'))
      .join('\n');

    const missing = tourAnchorIds().filter((id) => !sources.includes(`id="${id}"`));
    expect(missing, `no id="…" attribute found for: ${missing.join(', ')}`).toEqual([]);
  });

  it('resolves a known path and rejects an unknown one', () => {
    expect(tourForPath('/oql')?.id).toBe('oql');
    expect(tourForPath('/login')).toBeNull();
    expect(tourForPath('/nope')).toBeNull();
  });
});

/**
 * J1-F12: the tours narrate the demo scenario. For the demo user that is the truth; for anyone
 * else the tour must say so before it describes "one project, three repositories" over tiles
 * reading 00 — and must name what an empty workspace can do on that page.
 */
describe('tours outside the demo say they follow the demo scenario (J1-F12)', () => {
  it('prepends the scenario note, with the page\'s empty-workspace start, for a real user', () => {
    for (const [path, tour] of Object.entries(TOURS)) {
      if (!tour.scenario) continue;
      const steps = tourSteps(tour, { demo: false });
      expect(steps[0].popover?.title, path).toBe(SCENARIO_NOTE_TITLE);
      expect(tour.emptyStart, `${path} has no empty-workspace start`).toBeTruthy();
      expect(steps[0].popover?.description, path).toContain(tour.emptyStart as string);
      expect(steps.length, path).toBe(tour.steps.length + 1);
    }
  });

  it('shows the demo user the scenario as it is, with no note', () => {
    for (const tour of Object.values(TOURS)) {
      expect(tourSteps(tour, { demo: true })).toEqual(tour.steps);
    }
  });

  it('leaves a non-scenario tour unprefixed for everyone', () => {
    const tenants = TOURS['/tenants'];
    expect(tenants.scenario).toBeFalsy();
    expect(tourSteps(tenants, { demo: false })).toEqual(tenants.steps);
  });
});

/** Claims the audit found false in tour copy (J1-F12, J2-F30, J4-F25, J6-F1, J6-F22). */
describe('tour copy says only what this build does', () => {
  it('no organization switching — a user belongs to exactly one (V23)', () => {
    expect(allText(TOURS['/tenants'])).not.toMatch(/switching organization/i);
  });

  it('the OQL example uses a property the walkthrough data has (full_name, not name)', () => {
    expect(allText(TOURS['/oql'])).not.toContain('Int_researchers.name');
    expect(allText(TOURS['/oql'])).toContain('Int_researchers.full_name');
  });

  it('old schema versions are compared and restored, not queried (J4-F25)', () => {
    expect(allText(TOURS['/versions'])).not.toMatch(/queryable forever/i);
    expect(allText(TOURS['/versions'])).toContain('only the ACTIVE one is queried');
  });

  it('a product is defined against a version, not pinned to it (J6-F1)', () => {
    expect(allText(TOURS['/products'])).not.toMatch(/pinned to the schema version/i);
  });

  it('the products tour ends by saying how anyone reads what was built (J6-F22)', () => {
    const last = TOURS['/products'].steps[TOURS['/products'].steps.length - 1];
    expect(last.popover?.description).toContain('JDBC');
    expect(last.popover?.description).toContain('agents');
  });

  it('repositories: no file-system path to point at — uploads only (J2-F30)', () => {
    expect(allText(TOURS['/repositories'])).not.toContain('file-system path');
    expect(allText(TOURS['/repositories'])).not.toContain('folder of files');
  });
});
