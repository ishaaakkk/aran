import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';
import { REALM } from '../stack/specUsers';

/**
 * Keycloak's own login form. Its markup is Keycloak's, not ours, so the
 * selectors here are the only ones in the suite we do not control — they are
 * the stable ids Keycloak has shipped for many major versions.
 */
export class LoginPage {
  constructor(private readonly page: Page) {}

  async login(username: string, password: string): Promise<void> {
    await this.page.goto('/');
    // check-sso lands unauthenticated visitors on our own login screen first.
    const signIn = this.page.getByTestId('login-submit');
    await signIn.waitFor({ state: 'visible' });
    await signIn.click();

    // Built from REALM rather than spelled out: a renamed realm should fail loudly at
    // the source of truth, not as an opaque navigation timeout here.
    await this.page.waitForURL(
      new RegExp(`/realms/${REALM}/protocol/openid-connect/auth`),
    );
    await this.page.locator('#username').fill(username);
    await this.page.locator('#password').fill(password);
    await this.page.locator('#kc-login').click();

    await this.page.waitForURL((url) => !url.pathname.includes('/realms/'));
    // `playwright.config.ts`'s `expect.timeout: 15_000` is wired in per-worker
    // by the test runner; `authenticateAll()` drives this same login() from
    // `globalSetup`, which runs outside any worker, so an un-parameterised
    // expect() here would silently fall back to Playwright's bare 5000ms
    // default — too short for a real Keycloak redirect round-trip plus our
    // own RequireAuth -> /api/me fetch on a possibly still-warming backend.
    // Passing the timeout explicitly makes this assertion correct in both
    // contexts instead of depending on which one happens to be present.
    await expect(this.page.getByTestId('app-shell')).toBeVisible({ timeout: 30_000 });
  }

  /**
   * The identity ("who does this session belong to") lives inside the user
   * menu in IconRail, gated behind `showMenu`. Opens the menu if it is not
   * already open and returns the signed-in user's email, without assuming
   * (or forcing) any particular open/closed state on exit — `logout()`
   * below tolerates either.
   */
  async signedInEmail(): Promise<string> {
    const email = this.page.getByTestId('nav-user-email');
    if (!(await email.isVisible())) {
      await this.page.getByTestId('nav-user-menu').click();
    }
    await expect(email).toBeVisible();
    return (await email.innerText()).trim();
  }

  async logout(): Promise<void> {
    // Logout lives behind the same user menu as the identity check above;
    // only open it if something else hasn't already.
    const logoutButton = this.page.getByTestId('nav-logout');
    if (!(await logoutButton.isVisible())) {
      await this.page.getByTestId('nav-user-menu').click();
    }
    await logoutButton.click();
    await this.page.waitForURL(/\/(login)?$/);
  }
}
