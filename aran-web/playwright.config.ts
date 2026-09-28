import { defineConfig, devices } from '@playwright/test';
import { BASE_URL } from './e2e/stack/ports';

export default defineConfig({
  testDir: './e2e/specs',
  // Full journeys are long; the ceiling is per-test, not per-action.
  timeout: 5 * 60 * 1000,
  expect: { timeout: 15 * 1000 },
  // Specs are isolated by tenant, so files parallelise freely. A file whose
  // tests build on each other's promoted schema must opt out with
  // `test.describe.configure({ mode: 'serial' })`.
  fullyParallel: true,
  workers: 4,
  // Stress specs measure repeated runs and concurrency: slow, and their timing
  // assertions are noise next to a parallel functional suite. The default run
  // excludes them; `ARAN_E2E_STRESS=1` runs them and nothing else.
  //
  // An env flag rather than `--grep @stress`, because grep and grepInvert
  // INTERSECT — with grepInvert set unconditionally, `--grep @stress` selects
  // exactly zero tests rather than the ones you asked for.
  ...(process.env.ARAN_E2E_STRESS
    ? { grep: /@stress/, workers: 1 }
    : { grepInvert: /@stress/ }),
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }], ['./e2e/stack/keepLogsReporter.ts']]
    : [['list'], ['./e2e/stack/keepLogsReporter.ts']],
  globalSetup: './e2e/stack/globalSetup.ts',
  globalTeardown: './e2e/stack/globalTeardown.ts',
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
