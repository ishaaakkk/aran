import { test, expect, specUser } from '../fixtures';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PORTS } from '../stack/ports';

test.use(specUser('p1'));
test.describe.configure({ mode: 'serial', timeout: 5 * 60 * 1000 });

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const ARAN_MCP_PYTHON = path.join(REPO, 'aran-mcp', '.venv', 'bin', 'python');
const LIVE_CLIENT_TEST = path.join(REPO, 'aran-mcp', 'tests', 'test_live_client.py');

/**
 * P1 — the programmatic-client register's real gap: V21
 * closes here, driven for real for the first time.
 *
 * `aran-mcp/tests/test_server.py` already proves the protocol logic against a loopback
 * stub of Aran (18 tests). What that cannot prove — and what this does — is the actual
 * `python -m aran_mcp` process, spawned as a real subprocess, driven by the official MCP
 * SDK's client over real stdio, reading a real materialized data product from this real,
 * running Aran over real HTTP. See `aran-mcp/tests/test_live_client.py` for the client
 * side; this spec is only the setup — a real schema, a real materialized product, a real
 * READ-scoped API token — and the one line that invokes it.
 *
 * Setup reuses A2's proven two-source scenario (`journey` + `alignments`) rather than a
 * bespoke one-source shortcut: `SchemaIntegrator` only ever mints an integrated class from
 * an alignment (see a1-single-csv.spec.ts's own note), so a lone unaligned source has
 * nothing to pin a data product's schema version to.
 */
test('a real MCP client reads a real materialized product from a real Aran', async ({
  journey, alignments, page,
}) => {
  await journey.bootstrapSources({
    project: 'p1-proj',
    repo: 'p1-files',
    sources: [
      { name: 'acm', file: 'acm_papers.csv' },
      { name: 'semantic', file: 'semantic_papers.json' },
    ],
  });

  await alignments.goto();
  await alignments.selectProject('p1-proj');
  await alignments.selectSources('acm', 'semantic');
  await alignments.addAlignment({
    leftProp: 'acm', rightProp: 'semantic', label: 'Paper', kind: 'class',
  });
  await alignments.addAlignment({
    leftProp: 'title', rightProp: 'name', label: 'title', kind: 'datatype',
  });
  await alignments.addAlignment({
    leftProp: 'year', rightProp: 'pub_year', label: 'year', kind: 'datatype',
  });
  // Same F12 workaround a2 needs — see a2-csv-json-union.spec.ts for the full reasoning.
  await alignments.addAlignment({
    leftProp: 'author_ref', rightProp: 'author_ref', label: 'author_ref', kind: 'datatype',
  });
  await alignments.integrate();
  await alignments.promote();

  // The project id: every product/token endpoint below is REST, not a page object,
  // because neither has one yet — nothing before this spec has created a data product
  // through the API.
  //
  // page.request shares the browser context's COOKIES, not its in-memory state — and
  // this app's auth token lives only in keycloak-js's memory (see services/keycloak.ts:
  // "never in localStorage — that would expose it to XSS"), attached per-request by
  // services/api.ts's ensureFreshToken(). A raw page.request call therefore goes out
  // with no Authorization header at all and gets a bodyless 401, which is what an
  // earlier version of this spec hit ("Unexpected end of JSON input" parsing that empty
  // body). Fixed by asking the page itself for a fresh token — a dynamic import of the
  // same module URL the app already loaded resolves to the same keycloak-js singleton,
  // so this is the real, current session's real token, not a second login.
  const authHeader = async () => {
    const token = await page.evaluate(async () => {
      // A computed specifier, not a literal: tsc resolves and type-checks a literal
      // dynamic-import path against this project's own module graph, which does not
      // include a root-relative /src/... URL — that resolution only exists at runtime,
      // inside the page, against Vite's dev server.
      const keycloakModulePath = '/src/services/keycloak.ts';
      const { ensureFreshToken } = await import(keycloakModulePath);
      return ensureFreshToken() as Promise<string | undefined>;
    });
    return { Authorization: `Bearer ${token}` };
  };

  const projects = await (await page.request.get('/api/projects', { headers: await authHeader() })).json();
  const project = projects.find((p: { name: string }) => p.name === 'p1-proj');
  expect(project, 'the project just created via the UI must be readable back via the API').toBeTruthy();

  // A real data product, pinned to the schema version `alignments.promote()` just made
  // ACTIVE — plain OQL over the class the alignments above produced.
  const createRes = await page.request.post(`/api/projects/${project.id}/products`, {
    headers: await authHeader(),
    data: { name: 'Papers', description: 'p1 live-client fixture', oql: 'SELECT Paper.title FROM Paper' },
  });
  expect(createRes.ok(), await createRes.text()).toBeTruthy();
  const product = await createRes.json();

  const materializeRes = await page.request.post(
    `/api/projects/${project.id}/products/${product.id}/materialize`,
    { headers: await authHeader() },
  );
  expect(materializeRes.ok(), await materializeRes.text()).toBeTruthy();
  const { jobId } = await materializeRes.json();

  // Poll the job substrate rather than the product's own status: this is the same
  // real async path DatasetsPage.waitForBootstrap polls for dataset bootstrap, and
  // materialize is job-backed the identical way (DataProductService.materialize).
  await expect(async () => {
    const job = await (await page.request.get(`/api/jobs/${jobId}`, { headers: await authHeader() })).json();
    expect(job.status, JSON.stringify(job)).toBe('SUCCEEDED');
  }).toPass({ timeout: 60_000 });

  // A real, usable, READ-scoped credential — the exact kind aran-mcp's own README tells
  // an operator to mint for it.
  const tokenRes = await page.request.post('/api/tokens', {
    headers: await authHeader(),
    data: { name: 'p1-mcp-live-client', scope: 'READ' },
  });
  expect(tokenRes.ok(), await tokenRes.text()).toBeTruthy();
  const { secret } = await tokenRes.json();

  // The real client, the real server subprocess, the real backend — over real HTTP on
  // the app's own port (not the vite dev-server proxy `page.request` used above).
  const aranUrl = `http://localhost:${PORTS.backend}`;
  execFileSync(
    ARAN_MCP_PYTHON,
    ['-m', 'pytest', LIVE_CLIENT_TEST, '-q'],
    {
      env: { ...process.env, ARAN_URL: aranUrl, ARAN_TOKEN: secret },
      stdio: 'inherit',
      cwd: REPO,
    },
  );
});
