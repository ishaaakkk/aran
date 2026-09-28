import { test as base } from '@playwright/test';
import { storageStatePath } from './stack/specUsers';
import { ProjectsPage } from './pages/ProjectsPage';
import { RepositoriesPage } from './pages/RepositoriesPage';
import { DatasetsPage } from './pages/DatasetsPage';
import { AlignmentsPage } from './pages/AlignmentsPage';
import { DriftPage } from './pages/DriftPage';
import { WorkbenchPage } from './pages/WorkbenchPage';
import { ProductsPage } from './pages/ProductsPage';
import { Journey } from './pages/Journey';
import { JobsDrawerPage } from './pages/JobsDrawerPage';

/**
 * Binds a spec to its own Keycloak user, hence its own tenant. The schema
 * graph is per-tenant, so this is what keeps specs from overwriting each
 * other's promoted schema — and what lets them run in parallel.
 *
 *   test.use(specUser('a1'));
 */
export function specUser(specId: string): { storageState: string } {
  return { storageState: storageStatePath(specId) };
}

type AranPages = {
  projects: ProjectsPage;
  repositories: RepositoriesPage;
  datasets: DatasetsPage;
  alignments: AlignmentsPage;
  drift: DriftPage;
  workbench: WorkbenchPage;
  products: ProductsPage;
  journey: Journey;
  jobs: JobsDrawerPage;
};

export const test = base.extend<AranPages>({
  projects: async ({ page }, use) => {
    await use(new ProjectsPage(page));
  },
  repositories: async ({ page }, use) => {
    await use(new RepositoriesPage(page));
  },
  datasets: async ({ page }, use) => {
    await use(new DatasetsPage(page));
  },
  alignments: async ({ page }, use) => {
    await use(new AlignmentsPage(page));
  },
  drift: async ({ page }, use) => {
    await use(new DriftPage(page));
  },
  workbench: async ({ page }, use) => {
    await use(new WorkbenchPage(page));
  },
  products: async ({ page }, use) => {
    await use(new ProductsPage(page));
  },
  journey: async ({ projects, repositories, datasets }, use) => {
    await use(new Journey(projects, repositories, datasets));
  },
  jobs: async ({ page }, use) => {
    await use(new JobsDrawerPage(page));
  },
});

export { expect } from '@playwright/test';
