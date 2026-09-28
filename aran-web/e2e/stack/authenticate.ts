import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { BASE_URL } from './ports';
import { SPEC_IDS, STATE_DIR, SPEC_PASSWORD, storageStatePath, usernameFor } from './specUsers';
import { LoginPage } from '../pages/LoginPage';

/**
 * Log every spec user in once and persist its session. Specs then start
 * authenticated; only auth.spec.ts drives the real form. Fourteen Keycloak
 * redirect round-trips per run is pure overhead and the flakiest step we have.
 */
export async function authenticateAll(): Promise<void> {
  mkdirSync(STATE_DIR, { recursive: true });
  const browser = await chromium.launch();

  try {
    for (const specId of SPEC_IDS) {
      const context = await browser.newContext({ baseURL: BASE_URL });
      const page = await context.newPage();
      await new LoginPage(page).login(usernameFor(specId), SPEC_PASSWORD);
      await context.storageState({ path: storageStatePath(specId) });
      await context.close();
    }
    console.log(`[e2e] saved ${SPEC_IDS.length} authenticated sessions`);
  } finally {
    await browser.close();
  }
}
