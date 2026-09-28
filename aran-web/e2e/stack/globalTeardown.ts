import { execFileSync } from 'node:child_process';
import { rmSync } from 'node:fs';
import path from 'node:path';
import { stopAll, REPO, RUN_DIR } from './processes';

const COMPOSE = ['-p', 'aran-e2e', '-f', path.join(REPO, 'docker/e2e/docker-compose.e2e.yml')];

export default async function globalTeardown(): Promise<void> {
  await stopAll();

  execFileSync('docker', ['compose', ...COMPOSE, 'down', '-v'], {
    stdio: 'inherit',
    cwd: REPO,
  });

  // Keep the logs when anything failed; Playwright sets this for us.
  if (process.env.E2E_KEEP_LOGS === '1') {
    console.log(`[e2e] logs kept at ${RUN_DIR}`);
  } else {
    rmSync(RUN_DIR, { recursive: true, force: true });
  }
}
