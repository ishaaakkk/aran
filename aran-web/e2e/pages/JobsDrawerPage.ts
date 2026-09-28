import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';

/**
 * The top bar's Jobs drawer (UI audit J8-F7): every job the user can see, from any page — running,
 * failed and recent, each with its kind and the object it ran on.
 */
export class JobsDrawerPage {
  constructor(private readonly page: Page) {}

  private drawer(): Locator {
    return this.page.getByTestId('jobs-drawer');
  }

  async open(): Promise<void> {
    await this.page.getByTestId('topbar-jobs-button').click();
    await expect(this.drawer()).toBeVisible();
  }

  /** Escape closes it — the dialog contract — and focus returns to the Jobs button. */
  async close(): Promise<void> {
    await this.page.keyboard.press('Escape');
    await expect(this.drawer()).toHaveCount(0);
    await expect(this.page.getByTestId('topbar-jobs-button')).toBeFocused();
  }

  /** The newest listed job of this kind ("Refresh", "Import", …) on this object. */
  item(kind: string, object: string): Locator {
    return this.drawer()
      .locator('li[data-testid^="jobs-item-"]')
      .filter({ hasText: kind })
      .filter({ hasText: object })
      .first();
  }

  /** Wait (the drawer re-reads the list every few seconds while open) for the job's state. */
  async expectJob(kind: string, object: string, status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'): Promise<void> {
    await expect(this.item(kind, object)).toHaveAttribute('data-job-status', status, { timeout: 30_000 });
  }
}
