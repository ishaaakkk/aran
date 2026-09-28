import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Exercises the demo user's mocked backend through the real `api` client — not
 * `resolveDemoRequest` directly — so a regression that breaks the *wiring*
 * (api.ts forgetting to check `isDemoUser()`) fails a test here, not just a
 * manual click-through.
 *
 * `fetch` is stubbed to throw: every path this suite exercises must be fully
 * answered by the interceptor, never fall through to the network. That includes
 * a blocked write — rejecting is itself "fully answered"; only an unblocked
 * write reaching real `fetch` would be the regression this guards against.
 */

vi.mock('../keycloak', () => ({
  ensureFreshToken: vi.fn(async () => 'demo-token'),
  tokenParsed: () => ({ preferred_username: 'demo', email: 'demo@example.com' }),
}));

const { api } = await import('../api');
const { DEMO_DATASET, DEMO_PROJECT_ID, DEMO_TENANT_ID } = await import('./demoWorld');
const { DemoReadOnlyError, canChangeOwnPassword, isDemoUser, resolveDemoRequest } = await import('./demoInterceptor');
const { RECORDED_ACTIONS } = await import('../../views/admin/auditActions');
const { joinScoreTone } = await import('../../lib/joinScore');
type ProjectMember = import('../types').ProjectMember;

beforeEach(() => {
  // Demo mode is a BUILD-time opt-in (VITE_DEMO), not merely an identity check — see
  // the gate suite at the bottom of this file. Every suite here exercises a bundle
  // that opted in.
  vi.stubEnv('VITE_DEMO', 'true');
  vi.stubGlobal('fetch', vi.fn(() => {
    throw new Error('demo user traffic must never reach fetch');
  }));
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('demo user — identity & dashboard', () => {
  it('resolves an identity scoped to the demo tenant', async () => {
    const me = await api.me();
    expect(me.subject).toBe('demo');
    expect(me.tenants).toHaveLength(1);
    expect(me.tenants[0].role).toBe('OWNER');
  });

  it('answers /me in the shape the app actually reads', async () => {
    // The mock returned `id` (never read) instead of `userId`, and memberships without
    // `slug`/`joinedAt` — a payload no backend would send, in a file the type checker
    // was not looking at.
    const me = await api.me();
    expect(me.userId).toBeTruthy();
    expect(me).not.toHaveProperty('id');
    expect(me.displayName).toBeTruthy();
    expect(Date.parse(me.createdAt)).not.toBeNaN();

    const tenant = me.tenants[0];
    expect(tenant.slug).toBeTruthy();
    expect(Date.parse(tenant.joinedAt)).not.toBeNaN();
  });

  it('answers the project-members read with ProjectMember rows, not tenant members', async () => {
    // No `api` method covers this endpoint (nothing in the app calls it yet), so this
    // one asserts against the interceptor directly. The mock still has to match
    // `ProjectMemberResponse` — (userId, addedAt) — rather than the email/name/role
    // row it used to return, which is a different endpoint's shape.
    const members = resolveDemoRequest(`/projects/${DEMO_PROJECT_ID}/members`, {}) as ProjectMember[];
    expect(members).toHaveLength(1);
    expect(members[0].userId).toBeTruthy();
    expect(Date.parse(members[0].addedAt)).not.toBeNaN();
    expect(members[0]).not.toHaveProperty('role');
  });

  it('answers the license read with the meters TenantSelectView renders', async () => {
    // seatsUsed/seatsAllowed are not fields of LicenseSummary: the view renders
    // currentUsers/maxUsers and currentProjects/maxProjects, so the old payload showed
    // "undefined / undefined" and a NaN-width bar.
    const license = await api.getLicense(DEMO_TENANT_ID);
    expect(license.tenantId).toBe(DEMO_TENANT_ID);
    expect(license).not.toHaveProperty('seatsUsed');
    expect(license).not.toHaveProperty('seatsAllowed');
    for (const n of [license.currentUsers, license.maxUsers, license.currentProjects, license.maxProjects]) {
      expect(typeof n).toBe('number');
      expect(Number.isFinite(n)).toBe(true);
    }
    expect(license.currentUsers).toBeLessThanOrEqual(license.maxUsers);
    expect(typeof license.mutationsAllowed).toBe('boolean');
  });

  it('reports a populated dashboard, not an empty-state', async () => {
    const summary = await api.getDashboardSummary();
    expect(summary.datasets).toBeGreaterThan(0);
    expect(summary.recentAudit.length).toBeGreaterThan(0);
  });

  it('shows only audit lines the product really records (J7-F1)', async () => {
    // The Activity header says compiles are not recorded; a demo feed with a "Compiled
    // integration" line contradicted it.
    const summary = await api.getDashboardSummary();
    for (const line of summary.recentAudit) {
      expect(Object.keys(RECORDED_ACTIONS)).toContain(line.action);
    }
  });
});

describe('demo user — jobs', () => {
  it('answers the jobs list with no jobs, instead of an error in the top bar on every page', async () => {
    await expect(api.listJobs({ limit: 100 })).resolves.toEqual([]);
  });
});

describe('demo user — projects, repositories, datasets', () => {
  it('lists the one seeded project', async () => {
    const projects = await api.listProjects();
    expect(projects).toHaveLength(1);
    expect(projects[0].id).toBe(DEMO_PROJECT_ID);
  });

  it('lists three repositories, one per seminar source', async () => {
    const repos = await api.listRepositories(DEMO_PROJECT_ID);
    expect(repos.map((r) => r.name).sort()).toEqual([
      'ACM Digital Library', 'Researcher Profiles', 'Semantic Scholar',
    ]);
  });

  it('scopes datasets to the repository they were registered under', async () => {
    const repos = await api.listRepositories(DEMO_PROJECT_ID);
    const acm = repos.find((r) => r.name === 'ACM Digital Library')!;
    const datasets = await api.listDatasets(DEMO_PROJECT_ID, acm.id);
    expect(datasets).toHaveLength(1);
    expect(datasets[0].name).toBe('acm_papers.csv');
    // The list carries no profile — `Dataset.columns` was a wire field the backend never sent
    // (J2-F13). The Profile tab reads the stored profile from its own endpoint, which the demo
    // answers as the product does (J3-F21).
    expect('columns' in datasets[0]).toBe(false);
    const profiles = await api.listDatasetProfiles(DEMO_PROJECT_ID, acm.id, datasets[0].id);
    expect(profiles.groups[0].subject).toBe('SNAPSHOT');
    expect(profiles.groups[0].keyEvidence).toBe(true);
    expect(profiles.groups[0].columns.map((c) => c.columnName)).toContain('author_id');
  });

  it('previews real-looking rows for a dataset', async () => {
    const preview = await api.previewDatasetById(DEMO_PROJECT_ID, 'repo-acm', DEMO_DATASET.acm);
    expect(preview.headers).toContain('title');
    expect(preview.rows.length).toBeGreaterThan(0);
  });
});

describe('demo user — integration model (Alignments)', () => {
  it('returns a bundle with a promoted baseline and a pending suggestion', async () => {
    const bundle = await api.getModel(DEMO_PROJECT_ID);
    expect(bundle.model.activeIntegrationRunId).not.toBeNull();
    expect(bundle.sources).toHaveLength(3);
    expect(bundle.mappings.some((m) => m.status === 'SUGGESTED')).toBe(true);
    expect(bundle.mappings.some((m) => m.kind === 'join' && m.status === 'ACCEPTED')).toBe(true);
    expect(bundle.constraints.length).toBeGreaterThan(0);
  });

  it('discovers a join candidate between ACM and Researcher Profiles', async () => {
    // The Alignments view's defaults: the 'exact' method, whose scale peaks near 0.5.
    const res = await api.invokeOperator('join-discovery', {
      operation: 'discover',
      parameters: { datasetIdA: DEMO_DATASET.acm, datasetIdB: DEMO_DATASET.researchers, threshold: '0.0', method: 'exact', strictness: '0.25' },
    });
    expect(res.success).toBe(true);
    const candidates = JSON.parse(res.data!.candidates);
    expect(candidates.length).toBeGreaterThan(1);
    expect([candidates[0].columnA, candidates[0].columnB]).toEqual(['author_id', 'researcher_id']);
    expect(joinScoreTone(candidates[0].score, 'exact')).toBe('strong');
    // The tour presents venue ↔ affiliation as the false positive a threshold filters out.
    expect(joinScoreTone(candidates[candidates.length - 1].score, 'exact')).toBe('weak');
  });

  it('honours the threshold and scores on the chosen method\'s scale (J3-F21)', async () => {
    const discover = async (method: string, threshold: string) => {
      const res = await api.invokeOperator('join-discovery', {
        operation: 'discover',
        parameters: { datasetIdA: DEMO_DATASET.acm, datasetIdB: DEMO_DATASET.researchers, threshold, method, strictness: '0.25' },
      });
      return JSON.parse(res.data!.candidates) as { columnA: string; score: number }[];
    };
    // A threshold above the weak pair drops it, exactly as the operator filters.
    expect((await discover('exact', '0.2')).map((c) => c.columnA)).toEqual(['author_id']);
    // The ML model scores on 0–1: the true join reads strong on its own bands.
    const ml = await discover('ml', '0');
    expect(joinScoreTone(ml[0].score, 'ml')).toBe('strong');
  });
});

describe('demo user — schema versions', () => {
  it('shows a two-version history with the second active', async () => {
    const versions = await api.listSchemaVersions();
    expect(versions).toHaveLength(2);
    expect(versions.find((v) => v.status === 'ACTIVE')?.versionNumber).toBe(2);
  });

  it('diffs the two versions with a real added class', async () => {
    const diff = await api.diffSchemaVersions('v1', 'v2');
    expect(diff.addedClasses.length).toBeGreaterThan(0);
  });
});

describe('demo user — data products', () => {
  it('lists ready-to-preview products', async () => {
    const products = await api.listProducts(DEMO_PROJECT_ID);
    expect(products.length).toBeGreaterThanOrEqual(2);
    expect(products.every((p) => p.status === 'READY')).toBe(true);
  });

  it('previews rows for a product', async () => {
    const products = await api.listProducts(DEMO_PROJECT_ID);
    const preview = await api.previewProduct(DEMO_PROJECT_ID, products[0].id);
    expect(preview.rows.length).toBeGreaterThan(0);
  });
});

describe('demo user — query workbench reflects the actual query text', () => {
  it('returns different rows for different OQL queries instead of one static default', async () => {
    const byCountry = await api.runOql(
      "SELECT Int_researchers.country, AVG(Int_researchers.hIndex) FROM Int_researchers GROUP BY Int_researchers.country",
    );
    const joinUpc = await api.runOql(
      "SELECT IPaper.title, Int_researchers.name FROM IPaper -[hasAuthor]-> Int_researchers WHERE Int_researchers.affiliation = 'UPC'",
    );

    expect(byCountry.result.headerNames).toContain('country');
    expect(joinUpc.result.headerNames).toContain('name');
    // The pre-fix interceptor read `body.sql`/`body.oql` off the wrong object shape
    // and always fell through to one hardcoded default — this pins the fix.
    expect(byCountry.result.rows).not.toEqual(joinUpc.result.rows);
  });

  it('parses the query-engine catalog into two integrated classes with a join', async () => {
    const catalog = await api.queryEngineCatalog();
    expect(catalog.empty).toBe(false);
    expect(catalog.classes.map((c) => c.name).sort()).toEqual(['IPaper', 'Int_researchers']);
    expect(catalog.joinProperties).toHaveLength(1);
  });

  it('rewrite-oql\'s planShape fixture parses into the PlanShapeNode shape the plan view renders', async () => {
    // rewrite-oql's operator response is a Map<String,String>, so planShape reaches the
    // client JSON-encoded as a string (types.ts's OqlRewriteResult) — same as the real
    // backend, and the same defensive parse PlanTree does.
    const res = await api.rewriteOql('SELECT IPaper.title, Int_researchers.name FROM IPaper -[hasAuthor]-> Int_researchers');
    expect(res.planShape).toBeDefined();

    const shape = JSON.parse(res.planShape as string);
    expect(shape.kind).toBe('PROJECT');
    expect(shape.inputs).toHaveLength(1);
    expect(shape.inputs[0].kind).toBe('JOIN');
    expect(shape.inputs[0].inputs.map((n: { kind: string }) => n.kind)).toEqual(['SCAN', 'SCAN']);
    // consistent with the plain-text optimizedPlan returned alongside it.
    expect(res.optimizedPlan).toContain('EnumerableHashJoin');
    expect(res.planShapeText).toContain('JOIN[');
  });
});

describe('demo user — read-only enforcement', () => {
  const productId = 'prod-demo-1';

  it.each<[string, () => Promise<unknown>]>([
    ['create a project', () => api.createProject('New project', 'desc')],
    ['create a repository', () => api.createRepository(DEMO_PROJECT_ID, { name: 'x', type: 'FILE_SYSTEM' } as never)],
    ['test a repository connection', () => api.testConnection(DEMO_PROJECT_ID, {} as never)],
    ['delete a repository', () => api.deleteRepository(DEMO_PROJECT_ID, 'repo-acm')],
    ['register a dataset by file', () => api.createDatasetWithFile(DEMO_PROJECT_ID, 'repo-acm', { name: 'x', description: 'y' }, new File(['a,b'], 'x.csv'))],
    ['update a dataset', () => api.updateDataset(DEMO_PROJECT_ID, 'repo-acm', DEMO_DATASET.acm, { name: 'x', description: 'y' })],
    ['delete a dataset', () => api.deleteDataset(DEMO_PROJECT_ID, 'repo-acm', DEMO_DATASET.acm)],
    ['bootstrap (re-profile a source)', () => api.bootstrapDataset(DEMO_DATASET.acm)],
    ['profile a dataset', () => api.invokeOperator('profiling', { operation: 'profile', parameters: { snapshotId: 'snap-acm-1' } }).then((r) => { if (!r.success) throw new Error(r.errorMessage); })],
    ['add a model source', () => api.addModelSource(DEMO_PROJECT_ID, DEMO_DATASET.acm)],
    ['add a mapping', () => api.addMapping(DEMO_PROJECT_ID, { kind: 'datatype', sourceIri: 'a', targetIri: 'b' })],
    ['request new suggestions', () => api.suggestMappings(DEMO_PROJECT_ID, DEMO_DATASET.acm, DEMO_DATASET.researchers)],
    ['compile the integration model', () => api.compileModel(DEMO_PROJECT_ID)],
    ['incrementally integrate a source', () => api.incrementalIntegrate(DEMO_PROJECT_ID, DEMO_DATASET.acm)],
    ['promote a model run', () => api.promoteModelRun(DEMO_PROJECT_ID, 'run-demo-1')],
    ['add a constraint', () => api.addConstraint(DEMO_PROJECT_ID, { classIri: 'x', expression: 'y' })],
    ['discover constraints for a class', () => api.discoverConstraints(DEMO_PROJECT_ID, { datasetId: DEMO_DATASET.acm, classIri: 'x' })],
    ['roll back a schema version', () => api.rollbackSchemaVersion('v1')],
    ['create a data product', () => api.createProduct(DEMO_PROJECT_ID, { name: 'x', oql: 'SELECT 1' })],
    ['delete a data product', () => api.deleteProduct(DEMO_PROJECT_ID, productId)],
    ['materialize a data product', () => api.materializeProduct(DEMO_PROJECT_ID, productId)],
  ])('blocks an attempt to %s', async (_label, attempt) => {
    await expect(attempt()).rejects.toBeInstanceOf(DemoReadOnlyError);
  });

  it('never lets a job-creating call return a jobId to subscribe to', async () => {
    // If this ever regresses back to "fake success", jobEvents would be handed a
    // real-shaped jobId and poll/stream against a job that was never created.
    await expect(api.compileModel(DEMO_PROJECT_ID)).rejects.toThrow();
  });
});

describe('demo mode is a build-time opt-in, not just an identity', () => {
  // The interceptor ships in every bundle. Identity alone as the gate meant a real
  // customer who happened to be named `demo` (or reachable at demo@example.com) had
  // their own tenant replaced by this fabricated world — reads answered from fixtures,
  // writes refused as "read-only demo". VITE_DEMO is compiled in, so a bundle built
  // without it cannot enter demo mode for anyone.
  it('is off for the demo identity when VITE_DEMO is unset', () => {
    vi.stubEnv('VITE_DEMO', '');
    expect(isDemoUser()).toBe(false);
  });

  it('is off when VITE_DEMO is anything other than the string "true"', () => {
    for (const value of ['false', '1', 'yes', 'TRUE']) {
      vi.stubEnv('VITE_DEMO', value);
      expect(isDemoUser()).toBe(false);
    }
  });

  it('is on only when the build opted in AND the identity matches', () => {
    vi.stubEnv('VITE_DEMO', 'true');
    expect(isDemoUser()).toBe(true);
  });

  it('never offers the shared demo account "Change password" — one visitor would lock out the rest', () => {
    vi.stubEnv('VITE_DEMO', 'true');
    expect(canChangeOwnPassword()).toBe(false);
    vi.stubEnv('VITE_DEMO', '');
    expect(canChangeOwnPassword()).toBe(true);
  });

  it('sends a demo-named user of a non-demo build to the real backend', async () => {
    vi.stubEnv('VITE_DEMO', '');
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ userId: 'real-user', tenants: [] }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    const me = await api.me();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(me.userId).toBe('real-user');
  });
});
