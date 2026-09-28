import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * J8-F14: every confirmation goes through ConfirmDialog. A native `window.confirm` cannot list
 * consequences, and Playwright dismisses it on its own, so no e2e ever sees the question a user
 * is asked. This fails the build on the first one that comes back.
 */
const SRC = fileURLToPath(new URL('..', import.meta.url));

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.(ts|tsx)$/.test(name) && !/\.test\.(ts|tsx)$/.test(name) ? [path] : [];
  });
}

describe('native dialogs', () => {
  it('no source file calls window.confirm, alert or prompt', () => {
    const offenders = sources(SRC).filter((file) =>
      /\bwindow\.(confirm|alert|prompt)\s*\(/.test(readFileSync(file, 'utf8')),
    );
    expect(offenders).toEqual([]);
  });
});
