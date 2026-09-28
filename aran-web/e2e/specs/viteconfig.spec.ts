import { test, expect } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const CONFIG_PATH = path.join(WEB, 'vite.config.ts');

interface ProxyRoute {
  target: string;
  changeOrigin?: boolean;
  rewrite?: (path: string) => string;
}
interface ResolvedViteConfig {
  server: {
    port: number;
    proxy: Record<string, ProxyRoute>;
  };
}

// `vite.config.ts` reads `process.env` at module-evaluation time, and Node's
// ESM loader caches a module by specifier — so re-importing the same path
// would just hand back the first, cached export. A unique query string on
// each import forces a fresh evaluation with whatever env is set at that
// moment, letting us prove the config actually *reads through* the env var
// (not merely that the default happens to match a hardcoded literal).
let importCounter = 0;
async function loadConfig(env: Record<string, string | undefined>): Promise<ResolvedViteConfig> {
  const saved: Record<string, string | undefined> = {};
  for (const key of Object.keys(env)) {
    saved[key] = process.env[key];
    const value = env[key];
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
  try {
    const url = `${pathToFileURL(CONFIG_PATH).href}?bust=${importCounter++}`;
    const mod = (await import(url)) as { default: ResolvedViteConfig };
    return mod.default;
  } finally {
    for (const key of Object.keys(saved)) {
      if (saved[key] === undefined) delete process.env[key];
      else process.env[key] = saved[key];
    }
  }
}

test('dev server defaults are the dev-mode ports and targets', async () => {
  const cfg = await loadConfig({
    VITE_API_TARGET: undefined,
    VITE_AUTH_TARGET: undefined,
    VITE_DEV_PORT: undefined,
  });

  expect(cfg.server.port).toBe(5173);
  expect(cfg.server.proxy['/api'].target).toBe('http://localhost:8081');
  expect(cfg.server.proxy['/actuator'].target).toBe('http://localhost:8081');
  expect(cfg.server.proxy['/auth'].target).toBe('http://localhost:8080');
});

test('/api and /actuator both read their target from VITE_API_TARGET', async () => {
  const cfg = await loadConfig({ VITE_API_TARGET: 'http://example.test:9001' });

  // Both must move together off the *same* env var. A route left hardcoded
  // (or reading a different var) would not track this override.
  expect(cfg.server.proxy['/api'].target).toBe('http://example.test:9001');
  expect(cfg.server.proxy['/actuator'].target).toBe('http://example.test:9001');
});

test('/auth reads its target from VITE_AUTH_TARGET independently of the API target', async () => {
  const cfg = await loadConfig({
    VITE_API_TARGET: 'http://example.test:9001',
    VITE_AUTH_TARGET: 'http://example.test:9002',
  });

  expect(cfg.server.proxy['/auth'].target).toBe('http://example.test:9002');
  // Sanity: it didn't fall through to the API var.
  expect(cfg.server.proxy['/auth'].target).not.toBe(cfg.server.proxy['/api'].target);
});

test('the dev port reads from VITE_DEV_PORT and survives an empty or non-numeric value', async () => {
  expect((await loadConfig({ VITE_DEV_PORT: '5273' })).server.port).toBe(5273);
  // Set-but-empty and non-numeric must both fall back to the 5173 default,
  // not bind port 0 or crash with NaN (Finding C).
  expect((await loadConfig({ VITE_DEV_PORT: '' })).server.port).toBe(5173);
  expect((await loadConfig({ VITE_DEV_PORT: 'not-a-port' })).server.port).toBe(5173);
});

test('/auth strips the leading /auth prefix via its rewrite function', async () => {
  const cfg = await loadConfig({});
  const rewrite = cfg.server.proxy['/auth'].rewrite;

  expect(typeof rewrite).toBe('function');
  expect(rewrite!('/auth/realms/aran/protocol/openid-connect/token')).toBe(
    '/realms/aran/protocol/openid-connect/token',
  );
  expect(rewrite!('/auth')).toBe('');
});
