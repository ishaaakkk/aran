import { test, expect, type APIRequestContext, type APIResponse } from '@playwright/test';
import { PORTS } from '../stack/ports';
import { REALM } from '../stack/specUsers';

// Every parallel worker's first page load is anonymous from localhost, and the anonymous
// bucket is 60 requests/minute per IP, so under full-suite load a probe can meet 429 before
// the status it is asserting. The limiter refills greedily (about one request a second) while
// its Retry-After is a flat, conservative 60 s, so poll each second up to that 60 s instead.
async function getPastRateLimit(request: APIRequestContext, url: string): Promise<APIResponse> {
  const deadline = Date.now() + 61_000;
  let response = await request.get(url);
  while (response.status() === 429 && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 1_000));
    response = await request.get(url);
  }
  return response;
}

test('the frontend serves and redirects an anonymous visitor to login', async ({ page }) => {
  await page.goto('/');
  // No route matches "/" — App.tsx's catch-all sends an anonymous visitor to
  // /dashboard, and RequireAuth bounces it on to /login. Asserting the final
  // URL and the login form's own copy is what actually proves the redirect
  // happened, rather than merely proving *some* page rendered.
  await page.waitForURL(/\/login$/);
  await expect(page.getByText('Sign in to Aran.')).toBeVisible();
  await expect(page.getByRole('button', { name: /continue with single sign-on/i })).toBeVisible();
});

test('the vite proxy rejects anonymous callers', async ({ request }) => {
  // Necessary but nowhere near sufficient on its own — apiSecurityFilterChain is
  // identical across profiles, so an unauthenticated /api/me returns 401 from ANY
  // instance of this codebase. The next test is what actually pins the identity of
  // the backend behind the proxy.
  test.setTimeout(3 * 60 * 1000);
  const proxied = await getPastRateLimit(request, '/api/me');
  expect([401, 403]).toContain(proxied.status());

  const direct = await getPastRateLimit(request, `http://localhost:${PORTS.backend}/api/me`);
  expect(direct.status()).toBe(proxied.status());
});

// NOTE on proving the proxy reaches the E2E backend rather than a dev one that happens
// to be running on the same machine — a silent failure that would run the whole suite
// against the wrong database while still looking green.
//
// Nothing available to an unauthenticated HTTP probe distinguishes them: the /api/**
// chain is identical across profiles, so the status is 401 either way, and Spring emits
// a bare `Bearer` challenge with no issuer when no token is presented. Comparing the
// proxied and direct responses does not help either — both are 401 from either backend.
//
// A bare authenticated request cannot do it here: storageState restores Keycloak's SSO
// cookies, but api.ts takes its Bearer token from keycloak-js's IN-MEMORY state, so a
// request issued outside the app carries no token at all.
//
// The discriminating proof therefore has to go through the app, and it already exists:
// auth.spec.ts asserts the signed-in email after a session restore. That only succeeds
// if the backend behind the proxy both accepts a token minted by the aran-e2e realm and
// has that user in its database — neither of which a dev backend does. Duplicating it
// here would add no coverage.

test('health probes are anonymous but the rest of actuator is not', async ({ request }) => {
  // SecurityConfig gained an @Order(0) chain scoped to /actuator/health/** so the
  // container healthcheck (docker-compose.yml) and this suite's stack gate can poll
  // readiness unauthenticated. Chain 2 declares no securityMatcher, so without that
  // chain it catches the management port too and redirects every probe into OAuth2.
  const readiness = await request.get(
    `http://localhost:${PORTS.backendManagement}/actuator/health/readiness`,
  );
  expect(readiness.status()).toBe(200);
  expect(await readiness.text()).toContain('"status":"UP"');

  // The matcher must NOT have opened the whole actuator surface. maxRedirects: 0 is
  // essential — following the redirect lands on Keycloak's login page, which is a
  // perfectly good 200 and would hide the fact that metrics is gated.
  const metrics = await request.get(
    `http://localhost:${PORTS.backendManagement}/actuator/metrics`,
    { maxRedirects: 0 },
  );
  expect(metrics.status()).not.toBe(200);
});

test('keycloak serves the e2e realm', async ({ request }) => {
  const res = await request.get(
    `http://localhost:${PORTS.keycloak}/realms/${REALM}/.well-known/openid-configuration`,
  );
  expect(res.ok()).toBeTruthy();
  expect((await res.json()).issuer).toContain(REALM);
});
