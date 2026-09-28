import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';

export class ProjectsPage {
  constructor(private readonly page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('/projects');
    await expect(this.page.getByTestId('projects-new-button')).toBeVisible();
  }

  async create(name: string, description: string): Promise<void> {
    await this.page.getByTestId('projects-new-button').click();
    await this.page.getByTestId('projects-name-input').fill(name);
    await this.page.getByTestId('projects-description-input').fill(description);
    await this.page.getByTestId('projects-submit').click();

    // Unlike the repositories table (which aggregates across every project and
    // therefore needs the project+type pinned explicitly, see RepositoriesPage),
    // this view is scoped to one tenant with no second dimension a row could be
    // ambiguous against — `name` is the only identity a project row carries, and
    // it only renders once the create POST has actually succeeded (a failed
    // create leaves the dialog open with formError set, never reaching the
    // table). A bare visibility check on the name-keyed testid is not inert here.
    await expect(this.page.getByTestId(`projects-row-${name}`)).toBeVisible();
  }
}
