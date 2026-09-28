import type { DriveStep } from 'driver.js';

/**
 * Tour content, keyed by exact `location.pathname`.
 *
 * Anchors are dedicated `id="tour-…"` attributes, never the `data-testid`s the
 * Playwright page objects use. The two have different lifetimes: a testid is
 * renamed whenever a spec is restructured, and driver.js resolves a missing
 * selector into a silent unanchored popover rather than an error — so sharing
 * them means an e2e refactor breaks the tour with nothing to catch it.
 *
 * This module deliberately has no runtime imports (the driver.js import is
 * type-only and fully erased). `tourRegistry.test.ts` and the Playwright spec
 * both import it directly, and neither can afford to pull in React.
 */
export interface TourDefinition {
  /** Stable key for `seenTours`; never reuse one across routes. */
  id: string;
  steps: DriveStep[];
  /** True when the steps narrate the demo scenario rather than describe the page generically. */
  scenario?: boolean;
  /** What a user with an empty workspace can do on this page — the first thing a scenario tour
   *  tells anyone who is not the demo user (UI audit J1-F12). Required when `scenario` is set. */
  emptyStart?: string;
}

export const SCENARIO_NOTE_TITLE = 'This tour follows the demo scenario';

/**
 * The steps to show. The demo user sees the scenario as written; anyone else first gets a step
 * saying the tour narrates the demo scenario — so "one project, three repositories" over their
 * own tiles reading 00 is not read as a claim about their workspace — and what an empty
 * workspace can do on this page.
 */
export function tourSteps(tour: TourDefinition, opts: { demo: boolean }): DriveStep[] {
  if (opts.demo || !tour.scenario) return tour.steps;
  return [
    {
      popover: {
        title: SCENARIO_NOTE_TITLE,
        description:
          'This tour narrates a worked example — ACM Digital Library, Semantic Scholar and a ' +
          'Researcher Profiles registry integrated into IPaper and Int_researchers. Your workspace ' +
          'shows your own objects, so names and counts will differ. ' +
          (tour.emptyStart ?? ''),
      },
    },
    ...tour.steps,
  ];
}

/**
 * Every tour tells one continuous story — integrating ACM Digital Library,
 * Semantic Scholar and a Researcher Profiles registry into two classes
 * (`IPaper`, `Int_researchers`) joined on authorship — the exact scenario the demo
 * user's mocked backend serves (`services/demo/demoWorld.ts`) and
 * `demo/seminar/DEMO.md` narrates by hand. A tour's last step always points at
 * where that story continues, so walking `/dashboard` → `/repositories` →
 * `/datasets` → `/alignments` → `/versions` → `/oql` → `/products` reads as one
 * pipeline, not nine unrelated page intros.
 *
 * The demo account itself is read-only (`services/demo/demoInterceptor.ts`):
 * querying, browsing, and running discovery/explain all genuinely work, but
 * anything that would create, edit, or run a heavy job — register, integrate,
 * accept/reject, promote, materialize — answers with a message instead. Steps
 * that walk through one of those say so explicitly, so clicking through never
 * reads as broken.
 */
export const TOURS: Record<string, TourDefinition> = {
  '/dashboard': {
    id: 'dashboard',
    scenario: true,
    emptyStart: 'On an empty workspace, start with a project: Projects → new project. Repositories, datasets and alignments all hang off it.',
    steps: [
      {
        popover: {
          title: 'Welcome to Aran',
          description:
            'Aran turns scattered sources into one queryable schema. This walkthrough integrates three real-shaped sources — ACM Digital Library, Semantic Scholar, and a Researcher Profiles registry — into two classes, IPaper and Int_researchers, joined on authorship. The dashboard is the view from the top; the next five pages build that integration end to end.',
        },
      },
      {
        element: '#tour-dashboard-stats',
        popover: {
          title: 'Platform statistics',
          description: 'In the demo scenario these read one project, three repositories, three datasets and one active schema version — the scale of the example you are about to walk through. On your workspace they count your own objects.',
        },
      },
      {
        element: '#tour-dashboard-activity',
        popover: {
          title: 'Activity feed',
          description: 'Every step this tour covers already happened once: sources registered, an alignment accepted, a schema promoted. Recreate any of it yourself — nothing here is fixed.',
        },
      },
      {
        popover: {
          title: 'Next: Repositories',
          description: 'Head to Repositories in the left rail to see where the three sources actually live.',
        },
      },
    ],
  },

  '/projects': {
    id: 'projects',
    scenario: true,
    emptyStart: 'On an empty workspace this is the first step: create a project with "new project".',
    steps: [
      {
        element: '#tour-projects-header',
        popover: {
          title: 'Projects',
          description: 'A project is a workspace where you integrate a set of data sources into one unified schema. This tour\'s scenario lives in "Research Publications".',
        },
      },
      {
        element: '#tour-projects-new',
        popover: {
          title: 'Create a project',
          description: 'Start a new integration project. Everything downstream — repositories, datasets, schema versions, products — hangs off it. (This demo account is read-only — clicking through shows what the form looks like, but creating is disabled.)',
        },
      },
      {
        element: '#tour-projects-list',
        popover: {
          title: 'Your projects',
          description: 'Open "Research Publications" to pick up the ACM / Semantic Scholar / Researcher Profiles integration — or open any other project to see a different one.',
        },
      },
    ],
  },

  '/repositories': {
    id: 'repositories',
    scenario: true,
    emptyStart: 'On an empty workspace, register one in a project you created — a File System repository is the quickest start: it holds the files you upload.',
    steps: [
      {
        popover: {
          title: 'Repositories',
          description:
            'A repository is where your data comes from — a database connection, an Iceberg catalog, a Singer tap, or a File System repository that holds the files you upload. Aran reads from it; it never takes ownership of it. Three are registered in this scenario: ACM Digital Library (a CSV export), Semantic Scholar (a JSON extract with an author array), and Researcher Profiles (an internal JSON registry) — all three uploaded files.',
        },
      },
      {
        element: '#tour-repos-new',
        popover: {
          title: 'Register a repository',
          description: 'Register where data comes from: connection details for a database or an Iceberg catalog, a tap for a SaaS API, or nothing at all for a File System repository — Aran reads no directory on the server; you upload the files. A database or catalog can then list its tables for you to register. (Registering is disabled for this read-only demo account — the three sources below are as far as it goes.)',
        },
      },
      {
        popover: {
          title: 'Next: Datasets',
          description: 'Open Datasets to see the three files these repositories expose, and the schema Aran inferred from each.',
        },
      },
    ],
  },

  '/datasets': {
    id: 'datasets',
    scenario: true,
    emptyStart: 'On an empty workspace, register a dataset once a repository exists: upload a CSV, JSON or Parquet file, or import a table from a database repository.',
    steps: [
      {
        popover: {
          title: 'Datasets',
          description:
            'A dataset is one logical table or collection inside a repository — here, acm_papers.csv, semantic_papers.json, and researchers.json. Each is bootstrapped into its own source schema graph the moment it is registered: Aran infers columns, types, and (for JSON) nested structure like the array of author ids Semantic Scholar carries per paper.',
        },
      },
      {
        element: '#tour-datasets-new',
        popover: {
          title: 'Register a dataset',
          description: 'Pick a file or table from a registered repository. Aran parses its structure, snapshots it into the lake (unless the table is read live), and profiles its columns — cardinality, uniqueness, common values — feeding the join-discovery step two pages from now. (Registering and re-profiling are disabled for this read-only demo account; the three datasets below are already bootstrapped and profiled.)',
        },
      },
      {
        element: '#tour-datasets-list',
        popover: {
          title: 'Open any dataset',
          description:
            'Click acm_papers.csv to see its five columns (paper_id, title, year, venue, author_id), the generated wrapper SQL that normalises it, and a live preview — the detail panel every dataset gets.',
        },
      },
      {
        popover: {
          title: 'Next: Alignments',
          description: 'All three sources are bootstrapped. Head to Alignments to align their concepts into one global schema.',
        },
      },
    ],
  },

  '/alignments': {
    id: 'alignments',
    scenario: true,
    emptyStart: 'On an empty workspace this page needs bootstrapped datasets first; with two of them in a project you can align, rebuild and promote here.',
    steps: [
      {
        popover: {
          title: 'Schema integration',
          description:
            'Where separate source schemas become one global schema (GAV — global-as-view). ACM\'s "Paper" and Semantic Scholar\'s "Paper" both become IPaper; Researcher Profiles becomes Int_researchers. You align equivalent concepts across two sources, then integrate.',
        },
      },
      {
        element: '#tour-align-left',
        popover: {
          title: 'Left source',
          description: 'Choose the first of the two schemas to align — try ACM Digital Library. Its five columns load as a graph.',
        },
      },
      {
        element: '#tour-align-right',
        popover: {
          title: 'Right source',
          description: 'And the second — Researcher Profiles. Both graphs render side by side so you can click matching properties (e.g. ACM\'s author_id and Researcher Profiles\' researcher_id) and correspond them by hand.',
        },
      },
      {
        element: '#tour-align-discover',
        popover: {
          title: 'Discover joins automatically',
          description:
            'Rather than matching every column by hand, the profiling sidecar proposes joinable attributes from the statistics Aran already collected. For ACM ↔ Researcher Profiles it finds author_id ↔ researcher_id at 49% — the top of the scale the exact method uses, which peaks around 50%, because every ACM author id is a registered researcher — and a much weaker venue ↔ affiliation candidate at 5%, exactly the kind of false positive a similarity threshold exists to filter out.',
        },
      },
      {
        element: '#tour-align-suggestions',
        popover: {
          title: 'Review queue',
          description:
            'Not every suggestion is accepted automatically. This scenario has one still pending: venue ↔ affiliation, a plausible-looking but low-confidence match — normally you would accept or reject it here. (Both are disabled for this read-only demo account, so it stays pending for the next person to look at.)',
        },
      },
      {
        element: '#tour-align-integrate',
        popover: {
          title: 'Integrate',
          description:
            'Commit the accepted alignments into the global schema. This compiles an integration run — a draft graph with IPaper (unioning ACM + Semantic Scholar) and Int_researchers, joined by hasAuthor — which you then promote to a schema version. (Compiling is disabled for this read-only demo account — v2, on the Versions page, is what this step already produced.)',
        },
      },
      {
        popover: {
          title: 'Next: Versions',
          description: 'Once promoted, the integration becomes an immutable schema version. Head to Versions to see its history.',
        },
      },
    ],
  },

  '/versions': {
    id: 'versions',
    scenario: true,
    emptyStart: 'On an empty workspace this list stays empty until you rebuild and promote an integration on Alignments.',
    steps: [
      {
        popover: {
          title: 'Schema versions',
          description:
            'Every promotion of an integration model creates an immutable version of the global schema. Nothing overwrites history — v1 was IPaper alone (ACM + Semantic Scholar); v2 added Int_researchers and the hasAuthor join. Old versions can be compared and restored; only the ACTIVE one is queried. MATERIALIZED products keep serving their snapshot and turn STALE after a promotion; VIRTUAL ones follow the active version.',
        },
      },
      {
        element: '#tour-versions-history',
        popover: {
          title: 'History and diff',
          description:
            'Each row is a version you own. Select v1 and v2 (the checkboxes) for a class- and property-level diff — you will see Int_researchers listed as an added class; that part genuinely runs. Promoting a draft or rolling back to a historical version is disabled for this read-only demo account.',
        },
      },
      {
        popover: {
          title: 'Next: Query workbench',
          description: 'v2 is active. Head to the OQL workbench to query IPaper and Int_researchers across all three sources at once.',
        },
      },
    ],
  },

  '/oql': {
    id: 'oql',
    scenario: true,
    emptyStart: 'On an empty workspace, queries have nothing to run against until a schema version is promoted — Run then answers that no schema is active.',
    steps: [
      {
        popover: {
          title: 'Query workbench',
          description:
            'OQL queries the global schema, not the sources — you never write against acm_papers or semantic_papers directly. Name IPaper and Int_researchers; Aran plans the union across ACM + Semantic Scholar and the join to Researcher Profiles for you.',
        },
      },
      {
        element: '#tour-oql-editor',
        popover: {
          title: 'Write OQL',
          description:
            'Try: SELECT IPaper.title, Int_researchers.full_name FROM IPaper -[hasAuthor]-> Int_researchers WHERE Int_researchers.affiliation = \'UPC\' — a multi-hop join across two sources, expressed as one arrow. ⌘space suggests what exists in the active schema version.',
        },
      },
      {
        element: '#tour-oql-run',
        popover: {
          title: 'Run it',
          description:
            'The planner optimises the query, rewrites it to SQL over the underlying int_ views, and executes it — pulling rows from both ACM and Semantic Scholar\'s union, joined against Researcher Profiles. Use "explain" first to inspect the generated plan and SQL before running.',
        },
      },
      {
        popover: {
          title: 'Next: Data products',
          description: 'A query worth keeping becomes a data product. Head to Products to see two already saved from this scenario.',
        },
      },
    ],
  },

  '/products': {
    id: 'products',
    scenario: true,
    emptyStart: 'On an empty workspace there is nothing to save yet: once a schema is promoted, run a query in the workbench and choose "save as data product".',
    steps: [
      {
        popover: {
          title: 'Data products',
          description:
            'A data product is a saved OQL query with a stable name, recorded against the schema version active when it was created. That record is provenance, not a pin: a refresh re-runs the query under whatever version is active then, and a VIRTUAL product does so on every read.',
        },
      },
      {
        element: '#tour-products-project',
        popover: {
          title: 'Pick a project',
          description:
            'Products are scoped to a project. To create one, run a query in the OQL workbench and choose "save as data product" in the result panel. (Creating and materializing are disabled for this read-only demo account — the two below are already saved and materialized.)',
        },
      },
      {
        element: '#tour-products-list',
        popover: {
          title: 'Two products from this scenario',
          description:
            '"High-impact papers by country" and "UPC publications, 2023–2024" — both READY, both materialized to a parquet snapshot in the lake. Click a row to preview its rows — the "⟳ refresh" button is disabled for this read-only demo account.',
        },
      },
      {
        popover: {
          title: 'Publish and connect',
          description: 'A product is how anyone reads what you built: a JDBC client sees it as a table in the products schema, agents read it through the MCP sidecar ("⌁ agents" on its row), and a materialized one is also served by the read-only Iceberg catalog. Sources → alignment → a promoted schema → OQL → a product → its readers: that completes the walkthrough. Replay any page\'s tour from the "Guided tour" button in the left rail.',
        },
      },
    ],
  },

  '/tenants': {
    id: 'tenants',
    steps: [
      {
        popover: {
          title: 'Your organization',
          description:
            'Everything in your workspace — projects, repositories, schema versions and products — belongs to your organization and is invisible to any other. You belong to exactly one organization, so there is nothing to switch; someone who works in two holds two accounts.',
        },
      },
    ],
  },
};

/**
 * Detail routes whose page is the same view as a list route, so the list's tour applies
 * (`/datasets/:datasetId` is DatasetsListView with the detail open). Steps whose anchor the
 * detail hides are dropped by TutorialManager's `resolvableSteps`.
 */
const DETAIL_ROUTE_PARENTS: Array<[RegExp, string]> = [[/^\/datasets\/[^/]+$/, '/datasets']];

export function tourForPath(pathname: string): TourDefinition | null {
  const exact = TOURS[pathname];
  if (exact) return exact;
  const parent = DETAIL_ROUTE_PARENTS.find(([re]) => re.test(pathname))?.[1];
  return parent ? TOURS[parent] ?? null : null;
}

/** Every anchor id referenced by any tour, without the leading `#`. */
export function tourAnchorIds(): string[] {
  return Object.values(TOURS)
    .flatMap((tour) => tour.steps)
    .map((step) => step.element)
    .filter((element): element is string => typeof element === 'string')
    .map((selector) => selector.replace(/^#/, ''));
}
