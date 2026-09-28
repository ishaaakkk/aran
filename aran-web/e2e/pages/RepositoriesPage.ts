import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';
import { PORTS } from '../stack/ports';

export class RepositoriesPage {
  constructor(private readonly page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('/repositories');
    await expect(this.page.getByTestId('repos-new-button')).toBeVisible();
  }

  private async openDialogFor(projectName: string): Promise<void> {
    // One click. This used to be retried (F9): the button was live during the view's first
    // load and bailed with a toast while the projects store was still empty. Since UI audit
    // J1-F16 the button is disabled until the list has loaded, and Playwright's click waits
    // for it to be enabled — so the race is gone by construction. A load that FAILS keeps it
    // disabled beside `repos-load-error`, and this click then times out on a visible error
    // instead of being retried past it.
    await this.page.getByTestId('repos-new-button').click();
    await expect(this.page.getByTestId(`repos-project-${projectName}`)).toBeVisible();

    await this.page.getByTestId(`repos-project-${projectName}`).click();
    await this.page.getByTestId('repos-project-confirm').click();
    await expect(this.page.getByTestId('repo-name-input')).toBeVisible();
  }

  /**
   * This view aggregates repositories across every project in the tenant, so
   * a row's `data-testid` is keyed only by repository name — a bare
   * `toBeVisible()` on that testid would still pass if the repo landed under
   * the wrong project, or if the type select silently failed to switch away
   * from its RELATIONAL_DB default. Both are real ways this flow can go
   * quietly wrong, so pin the row to its project and type columns too.
   */
  private async expectRow(name: string, projectName: string, typeLabel: string): Promise<Locator> {
    const row = this.page.getByTestId(`repos-row-${name}`);
    await expect(row).toBeVisible();
    await expect(row).toContainText(projectName);
    await expect(row).toContainText(typeLabel);
    return row;
  }

  /** Upload-only since D1: a name is all a file-system repository takes. */
  async createFileSystem(projectName: string, name: string): Promise<void> {
    await this.openDialogFor(projectName);
    await this.page.getByTestId('repo-name-input').fill(name);
    await this.page.getByTestId('repo-description-input').fill('e2e uploads');
    await this.page.getByTestId('repo-type-select').selectOption('FILE_SYSTEM');
    await expect(this.page.getByTestId('repo-filesystem-upload-note')).toBeVisible();
    await this.page.getByTestId('repo-submit').click();
    await this.expectRow(name, projectName, 'FILE SYSTEM');
  }

  /**
   * The row's "last test" cell — what the last test of the STORED repository found (UI audit
   * J2-F1/J2-F19). Nothing is probed on page load; a save runs one test, and so does the row's
   * test button. Generous timeout: a first test of an engine downloads its JDBC driver.
   */
  async expectConnectionStatus(name: string, label: string | RegExp): Promise<void> {
    await expect(this.page.getByTestId(`repos-status-${name}`)).toContainText(label, { timeout: 60_000 });
  }

  /**
   * Run the row's test of the stored repository and wait for THIS test's own answer — the toast it
   * raises — rather than the row's label, which an earlier test may already have set to the same
   * word. `message` is what the server's probe said ("Connected to POSTGRESQL at …").
   */
  async testConnection(name: string, message: string | RegExp): Promise<void> {
    await this.page.getByTestId(`repos-test-${name}`).click();
    await expect(this.page.getByTestId('repos-toast')).toContainText(message, { timeout: 60_000 });
  }

  /** Points at the compose `source-postgres` service seeded with `affiliations`. */
  async createPostgres(projectName: string, name: string): Promise<void> {
    await this.openDialogFor(projectName);
    await this.page.getByTestId('repo-name-input').fill(name);
    await this.page.getByTestId('repo-description-input').fill('e2e federated source');
    await this.page.getByTestId('repo-type-select').selectOption('RELATIONAL_DB');
    await this.page.getByTestId('repo-host-input').fill('localhost');
    await this.page.getByTestId('repo-port-input').fill(String(PORTS.sourcePostgres));
    await this.page.getByTestId('repo-database-input').fill('sourcedb');
    await this.page.getByTestId('repo-schema-input').fill('public');
    await this.page.getByTestId('repo-username-input').fill('source');
    await this.page.getByTestId('repo-password-input').fill('source');
    await this.page.getByTestId('repo-submit').click();
    await this.expectRow(name, projectName, 'RELATIONAL DB');
  }
}
