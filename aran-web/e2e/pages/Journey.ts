import type { ProjectsPage } from './ProjectsPage';
import type { RepositoriesPage } from './RepositoriesPage';
import type { DatasetsPage } from './DatasetsPage';

/**
 * The setup half of every scenario, still entirely through the UI: create the
 * project, register a filesystem repository, then upload, bootstrap and enrol
 * each source. Scenario-specific work (alignments, discovery, queries) stays
 * in the spec.
 *
 * This does not call `DatasetsPage.waitForBootstrap`. Under F5 the bootstrap
 * badge may never leave "Bootstrapping…" in this stack, and a `waitForBootstrap`
 * call here would then add a 120s stall per source to every scenario's setup.
 * `enrolInModel` does not depend on that badge: it retries its own click for up
 * to 30s, which waits out the server-side bootstrap (it completes in ~1-2s
 * whatever the client hears about it; see F5). `pages-alignments.spec.ts` does
 * the same for the same reason. Twelve scenarios depend on this helper.
 *
 * "Fail loudly, not half-succeed": every step below is a page-object call
 * that either completes or throws (registerFile fails its own row-visibility
 * assertion, enrolInModel retries for 30s then throws via `toPass`) — there
 * is no step here that can silently no-op and let a later scenario discover
 * the gap on its own, several files away from the cause.
 */
export class Journey {
  constructor(
    private readonly projects: ProjectsPage,
    private readonly repositories: RepositoriesPage,
    private readonly datasets: DatasetsPage,
  ) {}

  async bootstrapSources(opts: {
    project: string;
    repo: string;
    sources: Array<{ name: string; file: string }>;
  }): Promise<void> {
    await this.projects.goto();
    await this.projects.create(opts.project, `e2e ${opts.project}`);

    await this.repositories.goto();
    await this.repositories.createFileSystem(opts.project, opts.repo);

    for (const source of opts.sources) {
      await this.datasets.goto();
      await this.datasets.registerFile(opts.project, opts.repo, source.name, source.file);
      await this.datasets.enrolInModel(source.name);
    }
  }
}
