import { execFileSync, spawn } from 'node:child_process';
import path from 'node:path';
import { PORTS, BASE_URL } from './ports';
import { REALM } from './specUsers';
import { waitForHttp } from './health';
import { startProcess, REPO, RUN_DIR } from './processes';

const COMPOSE = ['-p', 'aran-e2e', '-f', path.join(REPO, 'docker/e2e/docker-compose.e2e.yml')];
const LAKE_DIR = path.join(REPO, 'aran-lake');
const LAKE_PY = path.join(LAKE_DIR, '.venv/bin/python');

interface ComposePsEntry {
  Service: string;
  State: string;
  Health: string;
  ExitCode: number;
}

/**
 * `docker compose up -d --wait`, streamed to the console as it always was, keeping the tail of
 * its stderr — where Compose writes its progress and its errors both — so a failure can quote it.
 */
function composeUp(): Promise<{ ok: boolean; stderrTail: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', ['compose', ...COMPOSE, 'up', '-d', '--wait'], {
      cwd: REPO,
      stdio: ['ignore', 'inherit', 'pipe'],
    });
    let tail = '';
    child.stderr.on('data', (chunk: Buffer) => {
      process.stderr.write(chunk);
      tail = (tail + chunk.toString()).slice(-4000);
    });
    child.on('error', reject);
    child.on('close', (code) => resolve({ ok: code === 0, stderrTail: tail }));
  });
}

/**
 * `docker compose up --wait` requires every container to reach "running" (or
 * "healthy", for the ones that declare a healthcheck) before it returns. But
 * `garage-init` is a one-shot bootstrap job (`restart: "no"`, no healthcheck)
 * that is *supposed* to exit 0 and stay exited — and this Compose CLI
 * (v5.1.3, confirmed by direct reproduction) counts that as a wait failure
 * even though nothing is actually broken. Ask Compose directly whether every
 * container is either healthy or has exited cleanly before deciding whether
 * `--wait`'s non-zero exit reflects a real problem.
 *
 * Every service in the file must have a container first. A failure before any
 * exists — `garage-init`'s image not building, an image not pulling — leaves
 * `ps` empty, and an empty list has nothing "not ready" in it: this check used
 * to pass, and the run then sat out every readiness timeout on a stack that
 * never started.
 */
function verifyContainersReady(composeStderr: string): void {
  const services = execFileSync('docker', ['compose', ...COMPOSE, 'config', '--services'], { cwd: REPO })
    .toString()
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  const out = execFileSync('docker', ['compose', ...COMPOSE, 'ps', '-a', '--format', 'json'], {
    cwd: REPO,
  }).toString();
  const containers: ComposePsEntry[] = out
    .trim()
    .split('\n')
    .filter(Boolean)
    .map((line) => JSON.parse(line) as ComposePsEntry);
  const composeSaid = `docker compose up said:\n${composeStderr.trim() || '(nothing on stderr)'}`;

  const created = new Set(containers.map((c) => c.Service));
  const neverCreated = services.filter((s) => !created.has(s));
  if (neverCreated.length > 0) {
    throw new Error(
      `[e2e] docker compose up failed before creating ${neverCreated.join(', ')}. ${composeSaid}`,
    );
  }

  const notReady = containers.filter((c) => {
    if (c.Health) return c.Health !== 'healthy';
    if (c.State === 'exited') return c.ExitCode !== 0;
    return c.State !== 'running';
  });

  if (notReady.length > 0) {
    throw new Error(
      '[e2e] containers not ready: ' +
        notReady
          .map((c) => `${c.Service} (state=${c.State}, health=${c.Health || 'n/a'}, exitCode=${c.ExitCode})`)
          .join(', ') +
        `. ${composeSaid}`,
    );
  }
}

export default async function globalSetup(): Promise<void> {
  console.log(`[e2e] logs → ${RUN_DIR}`);

  // ---- clear any wreckage from a previous run -------------------------------
  // Teardown lives inside the Playwright process, so a hard kill (a crash, a
  // Ctrl-C, an agent losing its session mid-run) leaves the stack standing.
  // Re-using it is worse than it sounds: garage-init has already run and exited,
  // so verifyContainersReady() would read a stale, previously-consumed exit code.
  // Start from nothing every time.
  console.log('[e2e] clearing any stack left over from a previous run');
  try {
    const portsToFree = [PORTS.lake, PORTS.freyja, PORTS.backend, PORTS.backendManagement, PORTS.vite];
    const lsofArgs = portsToFree.flatMap((p) => ['-ti', `:${p}`]);
    const pids = execFileSync('lsof', lsofArgs, {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
    if (pids) {
      for (const pid of pids.split('\n').filter(Boolean)) {
        try {
          process.kill(Number(pid), 'SIGKILL');
        } catch {
          /* ignore */
        }
      }
    }
  } catch {
    // lsof returns non-zero when no processes match
  }
  try {
    execFileSync('docker', ['compose', ...COMPOSE, 'down', '-v'], {
      stdio: 'inherit',
      cwd: REPO,
    });
  } catch {
    // Nothing to remove, or Docker is not reachable yet — `up` below reports the
    // real problem with a far better message than a teardown failure would.
  }

  // ---- containers ----------------------------------------------------------
  console.log('[e2e] starting containers');
  const up = await composeUp();
  if (!up.ok) {
    console.warn(
      '[e2e] `docker compose up --wait` reported failure — verifying container state directly ' +
        '(garage-init is expected to exit 0, which some Compose versions misreport as a wait failure)',
    );
    verifyContainersReady(up.stderrTail);
    console.log('[e2e] all containers verified healthy / exited cleanly — continuing despite --wait failure');
  }

  // `--wait` only blocks on services that declare a healthcheck. Keycloak and
  // Fuseki declare none, so they are reported ready immediately — these two
  // explicit gates are what actually guarantee they can answer requests.
  await waitForHttp(
    'keycloak',
    `http://localhost:${PORTS.keycloak}/realms/${REALM}/.well-known/openid-configuration`,
  );
  await waitForHttp('fuseki', `http://localhost:${PORTS.fuseki}/$/ping`);

  // ---- aran-lake -----------------------------------------------------------
  const lakeEnv = {
    ARAN_LAKE_DATABASE_URL: `postgresql+asyncpg://aran_lake:aran_lake_dev@localhost:${PORTS.lakePostgres}/aran_lake`,
    ARAN_LAKE_S3_ENDPOINT_URL: `http://localhost:${PORTS.garage}`,
    ARAN_LAKE_S3_REGION: 'garage',
    ARAN_LAKE_S3_BUCKET: 'aran-lake',
    ARAN_LAKE_S3_ACCESS_KEY: 'GK000000000000000000000000',
    ARAN_LAKE_S3_SECRET_KEY:
      '0000000000000000000000000000000000000000000000000000000000000000',
    ARAN_LAKE_SHARED_SECRET: 'dev-shared-secret-change-in-prod',
    ARAN_LAKE_PROFILE: 'dev',
  };

  console.log('[e2e] running lake migrations');
  execFileSync(path.join(LAKE_DIR, '.venv/bin/alembic'), ['upgrade', 'head'], {
    cwd: LAKE_DIR,
    env: { ...process.env, ...lakeEnv },
    stdio: 'inherit',
  });

  console.log('[e2e] starting aran-lake');
  startProcess(
    'aran-lake',
    LAKE_PY,
    ['-m', 'uvicorn', 'aran_lake.main:app', '--port', String(PORTS.lake)],
    { cwd: LAKE_DIR, env: lakeEnv },
  );
  await waitForHttp('aran-lake', `http://localhost:${PORTS.lake}/health`);

  // ---- freyja --------------------------------------------------------------
  console.log('[e2e] starting freyja');
  startProcess(
    'freyja',
    LAKE_PY,
    ['-m', 'uvicorn', 'freyja.api.profile_api:app', '--port', String(PORTS.freyja)],
    {
      cwd: REPO,
      env: {
        ARAN_LAKE_URL: `http://localhost:${PORTS.lake}`,
        ARAN_LAKE_FREYJA_SECRET: 'dev-shared-secret-change-in-prod',
        FREYJA_SHARED_SECRET: 'dev-freyja-secret-change-in-prod',
        FREYJA_PROFILE: 'dev',
      },
    },
  );
  await waitForHttp('freyja', `http://localhost:${PORTS.freyja}/healthz`);

  // ---- backend -------------------------------------------------------------
  console.log('[e2e] starting the backend (first run compiles — allow a few minutes)');
  startProcess(
    'backend',
    path.join(REPO, 'gradlew'),
    [':aran-application:bootRun', '--no-daemon', '-x', 'test'],
    {
      cwd: REPO,
      env: {
        SPRING_PROFILES_ACTIVE: 'e2e',
        // CredentialEncryptionConverter refuses to start without this unless
        // SPRING_PROFILES_ACTIVE contains "dev" or "test" — "e2e" contains
        // neither, and it must not: the dev profile points Flyway at
        // classpath:db/seed, which collides with db/seed-e2e on V4 and would
        // seed the wrong tenants. Any non-blank string is valid — it is
        // SHA-256 hashed into an AES key, so there's no length/format
        // requirement — this one is obviously test-only.
        ARAN_ENCRYPTION_KEY: 'aran-e2e-test-key',
      },
    },
  );
  await waitForHttp(
    'backend',
    `http://localhost:${PORTS.backendManagement}/actuator/health/readiness`,
    { timeoutMs: 420_000, expectBody: '"status":"UP"' },
  );

  // ---- frontend ------------------------------------------------------------
  console.log('[e2e] starting vite');
  startProcess('vite', 'npx', ['vite', '--strictPort'], {
    cwd: path.join(REPO, 'aran-web'),
    env: {
      VITE_DEV_PORT: String(PORTS.vite),
      VITE_API_TARGET: `http://localhost:${PORTS.backend}`,
      VITE_AUTH_TARGET: `http://localhost:${PORTS.keycloak}`,
      VITE_KEYCLOAK_URL: `http://localhost:${PORTS.keycloak}`,
      VITE_KEYCLOAK_REALM: REALM,
      VITE_KEYCLOAK_CLIENT_ID: 'aran-web',
    },
  });
  await waitForHttp('vite', BASE_URL);

  // ---- per-spec Keycloak sessions -------------------------------------------
  const { authenticateAll } = await import('./authenticate');
  await authenticateAll();

  console.log('[e2e] stack ready');
}
