// Assert that every Mermaid diagram authored in the docs actually draws.
//
// Why this exists: Mermaid runs client-side, so a syntax error in a diagram is
// invisible to `docusaurus build` AND to the link checker. Both gates went green
// on a sequence diagram that rendered nothing at all, because a semicolon inside
// a `Note over` silently truncated it. A diagram that does not draw is a missing
// section of the page, so it fails the build here instead.
//
// The expectation comes from the SOURCE, not from the rendered DOM. Counting
// ```mermaid fences per file and then requiring exactly that many rendered SVGs on
// the corresponding route is the only version of this check that can tell "this
// page has no diagrams" apart from "this page's diagrams did not render" — the two
// look identical in the DOM, and an earlier DOM-only version of this script
// reported success after finding zero diagrams anywhere.
//
// Needs a Chromium: `npx playwright install chromium`, or PLAYWRIGHT_BROWSERS_PATH
// pointing at an existing one.
import {chromium} from 'playwright';
import {createServer} from 'node:http';
import {readFile, readdir} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {join, extname} from 'node:path';

const BUILD = 'build';
const DOCS = 'docs';
// Must match `routeBasePath` for the docs plugin in docusaurus.config.ts.
const DOCS_ROUTE_BASE = '/docs';
const MIME = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.json': 'application/json',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.xml': 'application/xml',
  '.txt': 'text/plain',
};

async function walk(dir) {
  const out = [];
  for (const entry of await readdir(dir, {withFileTypes: true})) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await walk(path)));
    else if (/\.mdx?$/.test(entry.name)) out.push(path);
  }
  return out;
}

/** Count fenced ```mermaid blocks, ignoring any that are themselves inside a wider fence. */
function countMermaidFences(source) {
  let count = 0;
  let openFence = null;
  for (const line of source.split('\n')) {
    const fence = line.match(/^\s*(`{3,}|~{3,})\s*(\S*)/);
    if (!fence) continue;
    const [, ticks, lang] = fence;
    if (openFence === null) {
      openFence = ticks;
      if (lang.toLowerCase() === 'mermaid') count++;
    } else if (ticks.startsWith(openFence[0]) && ticks.length >= openFence.length && !lang) {
      openFence = null;
    }
  }
  return count;
}

/** The route Docusaurus serves a docs file at, honouring an explicit `slug`. */
function routeFor(file, source) {
  const slug = source.match(/^---\n[\s\S]*?^slug:\s*(\S+)\s*$[\s\S]*?^---/m);
  if (slug) {
    const s = slug[1] === '/' ? '' : slug[1].replace(/^\//, '').replace(/\/$/, '');
    return s ? `${DOCS_ROUTE_BASE}/${s}/` : `${DOCS_ROUTE_BASE}/`;
  }
  const rel = file.slice(DOCS.length + 1).replace(/\.mdx?$/, '');
  const withoutIndex = rel.replace(/(^|\/)index$/, '');
  return withoutIndex === '' ? `${DOCS_ROUTE_BASE}/` : `${DOCS_ROUTE_BASE}/${withoutIndex}/`;
}

const server = createServer(async (req, res) => {
  const url = decodeURIComponent(req.url.split('?')[0]);
  const candidates = url.endsWith('/')
    ? [join(BUILD, url, 'index.html')]
    : [join(BUILD, url), join(BUILD, url, 'index.html')];
  for (const path of candidates) {
    try {
      const body = await readFile(path);
      res.writeHead(200, {'Content-Type': MIME[extname(path)] ?? 'application/octet-stream'});
      res.end(body);
      return;
    } catch {
      /* try the next candidate */
    }
  }
  res.writeHead(404).end('not found');
});

await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;

/**
 * Any Chromium under PLAYWRIGHT_BROWSERS_PATH, whatever its build number.
 *
 * Playwright insists on the exact build it shipped with, which makes this check
 * fail on machines and images that already have a perfectly usable Chromium
 * installed under a different build. Rendering a Mermaid diagram does not depend
 * on that version, so fall back to whatever is there rather than downloading.
 */
async function findInstalledChromium() {
  const root = process.env.PLAYWRIGHT_BROWSERS_PATH;
  if (!root || !existsSync(root)) return null;
  const suffixes = [
    'chrome-linux/chrome',
    'chrome-linux/headless_shell',
    'chrome-headless-shell-linux64/chrome-headless-shell',
    'chrome-mac/Chromium.app/Contents/MacOS/Chromium',
  ];
  for (const entry of await readdir(root)) {
    if (!entry.startsWith('chromium')) continue;
    for (const suffix of suffixes) {
      const candidate = join(root, entry, suffix);
      if (existsSync(candidate)) return candidate;
    }
  }
  return null;
}

// Every docs page that authors at least one diagram.
const expectations = [];
for (const file of await walk(DOCS)) {
  const source = await readFile(file, 'utf8');
  const expected = countMermaidFences(source);
  if (expected > 0) expectations.push({file, route: routeFor(file, source), expected});
}

if (expectations.length === 0) {
  console.error('No ```mermaid blocks found anywhere in docs/. The scan is misconfigured.');
  server.close();
  process.exit(1);
}

let browser;
try {
  browser = await chromium.launch();
} catch (primaryError) {
  const fallback = await findInstalledChromium();
  if (!fallback) {
    server.close();
    console.error(
      'Could not launch Chromium for the diagram check.\n' +
        'Install one with `npx playwright install chromium`, or point ' +
        'PLAYWRIGHT_BROWSERS_PATH at an existing install.\n\n' +
        primaryError.message,
    );
    process.exit(1);
  }
  console.log(`Using the Chromium already installed at ${fallback}`);
  browser = await chromium.launch({executablePath: fallback});
}

const ctx = await browser.newContext({viewport: {width: 1280, height: 900}});
const failures = [];
let drawn = 0;

for (const {file, route, expected} of expectations) {
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => consoleErrors.push(e.message));

  // Not `networkidle`: the Redoc-rendered API page requests Redocly's logo from a
  // CDN, which never resolves without egress, so that route is never "idle".
  const response = await page.goto(base + route, {waitUntil: 'domcontentloaded'});
  if (!response || response.status() !== 200) {
    failures.push(`${file}: route ${route} returned ${response ? response.status() : 'no response'}`);
    await page.close();
    continue;
  }

  // Diagrams appear only after hydration, so wait for the authored count to be
  // present AND drawn rather than sampling the DOM once.
  await page
    .waitForFunction(
      (n) => {
        const containers = [...document.querySelectorAll('.docusaurus-mermaid-container')];
        return containers.length >= n && containers.every((c) => c.querySelector('svg'));
      },
      expected,
      {timeout: 20000},
    )
    .catch(() => {});

  const found = await page.evaluate(() =>
    [...document.querySelectorAll('.docusaurus-mermaid-container')].map((c) => {
      const svg = c.querySelector('svg');
      return {
        hasSvg: !!svg,
        height: svg ? Math.round(svg.getBoundingClientRect().height) : 0,
        errorText: /Syntax error|mermaid version/i.test(c.textContent ?? ''),
      };
    }),
  );

  if (found.length < expected) {
    failures.push(
      `${file}: authors ${expected} diagram(s), only ${found.length} rendered at ${route}`,
    );
  }
  found.forEach((d, i) => {
    if (!d.hasSvg || d.errorText || d.height < 40) {
      failures.push(`${file} diagram #${i + 1}: no SVG drawn (or an error placeholder)`);
    } else {
      drawn++;
    }
  });

  const mermaidErrors = consoleErrors.filter((e) => /parse error|mermaid/i.test(e));
  if (mermaidErrors.length) {
    failures.push(`${file}: ${mermaidErrors[0].split('\n').slice(0, 3).join(' ').slice(0, 300)}`);
  }

  await page.close();
}

await browser.close();
server.close();

if (failures.length) {
  console.error('Mermaid diagrams failed to render:\n');
  for (const f of failures) console.error(`  ${f}`);
  console.error(
    `\n${failures.length} failure(s). A diagram that does not draw is a missing ` +
      'section of the page.',
  );
  process.exit(1);
}

const authored = expectations.reduce((n, e) => n + e.expected, 0);
console.log(
  `OK — ${drawn}/${authored} authored diagram(s) rendered across ${expectations.length} page(s).`,
);
