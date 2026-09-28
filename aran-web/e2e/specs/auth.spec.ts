import { test, expect } from '../fixtures';
import { LoginPage } from '../pages/LoginPage';
import { SPEC_PASSWORD, storageStatePath, usernameFor } from '../stack/specUsers';

// The one spec that starts from a clean context and drives the real form.
test.use({ storageState: { cookies: [], origins: [] } });

test('login through Keycloak lands on the app as the right user, then logs out', async ({ page }) => {
  const login = new LoginPage(page);
  await test.step('Sign in to Aran', async () => {
    await login.login(usernameFor('auth'), SPEC_PASSWORD);
  });

  await test.step('Land in the workspace, signed in as yourself', async () => {
    await expect(page.getByTestId('app-shell')).toBeVisible();

    // Prove the session belongs to THIS spec's own Keycloak user, not merely
    // that "some" authenticated shell rendered. One user per spec exists
    // precisely so specs cannot see or clobber each other's tenant-scoped
    // schema graph — an assertion that stops at "authenticated" would still
    // pass even if every spec shared one tenant, which is the failure this
    // whole design exists to prevent.
    await expect(login.signedInEmail()).resolves.toBe(`${usernameFor('auth')}@example.com`);
  });

  await test.step('Sign out and return to the login screen', async () => {
    await login.logout();
    await expect(page.getByTestId('login-submit')).toBeVisible();
  });
});

test('a session saved by authenticateAll() starts already authenticated as its own user', async ({ browser }) => {
  // storageStatePath('auth') is exactly the file globalSetup's authenticateAll()
  // wrote for this spec's user — the same mechanism every other spec relies on
  // to skip the interactive Keycloak round-trip. Load it directly rather than
  // going through the `specUser()` fixture (which this spec opts out of above)
  // to prove the artifact itself, not just the wiring, restores a session.
  const context = await browser.newContext({ storageState: storageStatePath('auth') });
  const page = await context.newPage();
  await page.goto('/');

  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(new LoginPage(page).signedInEmail()).resolves.toBe(`${usernameFor('auth')}@example.com`);

  await context.close();
});

test('without a saved session, the app shows the login screen, not the shell', async ({ browser }) => {
  const context = await browser.newContext({ storageState: undefined });
  const page = await context.newPage();
  await page.goto('/');
  // No saved state → the app must not show the authenticated shell.
  await expect(page.getByTestId('login-submit')).toBeVisible();
  await context.close();
});
