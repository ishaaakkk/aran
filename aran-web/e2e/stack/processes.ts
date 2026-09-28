import { spawn, type ChildProcess } from 'node:child_process';
import { createWriteStream, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const REPO = path.resolve(HERE, '../../..');

/** Logs from every host process land here; teardown keeps it only on failure. */
export const RUN_DIR = path.join(REPO, 'build', 'e2e-run');

/** How long a child gets to honour SIGTERM before the group is killed outright. */
const TERM_GRACE_MS = 10_000;

const children: Array<{ name: string; proc: ChildProcess }> = [];

/**
 * Start a host process in its OWN process group.
 *
 * <p>`detached: true` is load-bearing, not incidental. `gradlew bootRun` forks a
 * separate JVM for the Spring app, so signalling the immediate child only reaches
 * the Gradle wrapper and leaves that JVM holding 8181/8182 — the next run then
 * fails on a port clash that looks like a new bug. Spawning into a new group lets
 * `stopAll` signal the whole tree at once.
 *
 * <p>The child is deliberately NOT `unref`'d: it must keep the parent alive so the
 * pipes below stay open for the whole run.
 */
export function startProcess(
  name: string,
  cmd: string,
  args: string[],
  opts: { cwd: string; env: Record<string, string> },
): void {
  mkdirSync(RUN_DIR, { recursive: true });
  const log = createWriteStream(path.join(RUN_DIR, `${name}.log`), { flags: 'a' });

  const proc = spawn(cmd, args, {
    cwd: opts.cwd,
    env: { ...process.env, ...opts.env },
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: true,
  });
  proc.stdout?.pipe(log);
  proc.stderr?.pipe(log);
  proc.on('exit', (code) => {
    if (code !== 0 && code !== null) {
      console.error(`[e2e] ${name} exited with code ${code} — see ${RUN_DIR}/${name}.log`);
    }
  });

  children.push({ name, proc });
}

/** Resolves once the process has actually exited, or after `ms` — whichever first. */
function waitForExit(proc: ChildProcess, ms: number): Promise<boolean> {
  if (proc.exitCode !== null || proc.signalCode !== null) return Promise.resolve(true);
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), ms);
    proc.once('exit', () => {
      clearTimeout(timer);
      resolve(true);
    });
  });
}

/**
 * Signal a whole process group. A negative PID means "the group", which is why
 * `startProcess` spawns detached — see its comment.
 */
function signalGroup(proc: ChildProcess, signal: NodeJS.Signals): void {
  if (proc.pid === undefined) return;
  try {
    process.kill(-proc.pid, signal);
  } catch {
    // ESRCH: the group is already gone. Fall back to the child alone in case the
    // platform never gave it its own group.
    try {
      proc.kill(signal);
    } catch {
      /* already dead */
    }
  }
}

export async function stopAll(): Promise<void> {
  // Copy before iterating: `reverse()` mutates in place, and mutating the shared
  // array while walking it is a footgun even though we clear it below.
  const toStop = [...children].reverse();
  children.length = 0;

  for (const { name, proc } of toStop) {
    if (proc.exitCode !== null || proc.signalCode !== null) continue;

    signalGroup(proc, 'SIGTERM');
    // Wait for the process to actually go, rather than assuming a fixed delay is
    // enough — Gradle's shutdown hook has to stop the Spring JVM it forked.
    const exited = await waitForExit(proc, TERM_GRACE_MS);
    if (!exited) {
      console.warn(`[e2e] ${name} ignored SIGTERM after ${TERM_GRACE_MS / 1000}s — killing group`);
      signalGroup(proc, 'SIGKILL');
      await waitForExit(proc, 5_000);
    }
    console.log(`[e2e] stopped ${name}`);
  }
}
