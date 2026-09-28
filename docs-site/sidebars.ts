import {existsSync} from 'node:fs';
import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

// The rendered REST reference is a redocusaurus route, not a doc, and it only
// exists when the OpenAPI snapshot has been generated (see docs/api/index.md).
// Linking to it unconditionally would 404 — and the link checker would rightly
// fail the build — on a checkout where the snapshot is absent.
const hasOpenApiSnapshot = existsSync('openapi/aran-api.json');

// With the snapshot present this is a category (overview + the rendered spec).
// Without it there is only one page, and a category with an empty `items` renders
// as a bare entry carrying the *doc's* label — which showed up in the sidebar as a
// stray "Overview" sitting between two real categories. Collapse it to a plain doc
// with an explicit label instead.
const apiSection = hasOpenApiSnapshot
  ? {
      type: 'category' as const,
      label: 'API Reference',
      link: {type: 'doc' as const, id: 'api/index'},
      items: [{type: 'link' as const, label: 'REST endpoints', href: '/api/rest-endpoints/'}],
    }
  : {type: 'doc' as const, id: 'api/index', label: 'API Reference'};

/**
 * The information architecture is Diátaxis-shaped: every page belongs to exactly
 * one of tutorial / how-to / reference / explanation, and each page declares its
 * mode in a front-matter comment. Categories below group by *audience task*, not
 * by mode, because readers arrive with a job to do rather than a genre in mind.
 */
const sidebars: SidebarsConfig = {
  docsSidebar: [
    'index',
    {
      type: 'category',
      label: 'Overview',
      collapsed: false,
      items: [
        'overview/what-is-aran',
        'overview/why-aran',
        'overview/core-concepts',
        'overview/architecture',
        'overview/glossary',
      ],
    },
    {
      type: 'category',
      label: 'Getting Started',
      collapsed: false,
      items: ['getting-started/quickstart', 'getting-started/walkthrough'],
    },
    {
      type: 'category',
      label: 'How-to Guides',
      items: [
        'how-to/register-a-data-repository',
        'how-to/bootstrap-and-profile-a-dataset',
        'how-to/align-and-integrate-schemas',
        'how-to/write-wrapper-sql',
        'how-to/run-join-discovery',
        'how-to/configure-denial-constraints',
        'how-to/invoke-a-capability-operator',
        'how-to/export-query-results',
        'how-to/create-a-data-product',
        'how-to/query-a-database-live',
        'how-to/write-a-new-operator',
      ],
    },
    {
      type: 'category',
      label: 'Data Repositories',
      link: {type: 'doc', id: 'repositories/index'},
      items: [
        'repositories/relational-databases',
        'repositories/object-storage',
        'repositories/file-uploads',
        'repositories/singer-taps',
      ],
    },
    {
      type: 'category',
      label: 'OQL',
      link: {type: 'doc', id: 'oql/index'},
      items: [
        'oql/tutorial',
        'oql/syntax-reference',
        'oql/sql-fallback',
        'oql/cookbook',
        'oql/cheat-sheet',
      ],
    },
    {
      // Behaviour a user can observe in a plan or a badge, rather than the
      // engine's internals — those stay in Architecture Deep Dives.
      type: 'category',
      label: 'Query Engine',
      items: [
        'query-engine/typed-columns-and-costing',
        'query-engine/wrapper-optimisation',
        'query-engine/referential-evidence',
        'query-engine/denial-constraints',
      ],
    },
    {
      type: 'category',
      label: 'Programmatic Access',
      link: {type: 'doc', id: 'programmatic-access/index'},
      items: [
        'programmatic-access/jdbc',
        'programmatic-access/mcp',
        'programmatic-access/python-sdk',
        'programmatic-access/temporal-types',
      ],
    },
    {
      type: 'category',
      label: 'Architecture Deep Dives',
      items: [
        'deep-dives/query-pipeline',
        'deep-dives/bootstrapping-and-the-schema-graph',
        'deep-dives/virtual-data-products',
        'deep-dives/wrapper-fields',
        'deep-dives/multi-tenancy',
        'deep-dives/driver-resolution',
        'deep-dives/licensing-modes',
      ],
    },
    {
      type: 'category',
      label: 'Administration & Deployment',
      items: [
        'administration/standalone',
        'administration/production-build',
        'administration/docker-compose',
        'administration/configuration-reference',
        'administration/license-configuration',
        'administration/troubleshooting',
      ],
    },
    apiSection,
    {
      type: 'category',
      label: 'Contributor Guide',
      link: {type: 'doc', id: 'contributing/index'},
      items: [
        'contributing/module-layout',
        'contributing/build-and-test',
        'contributing/comment-philosophy',
        'contributing/branching-and-merge-gate',
        'contributing/e2e-suite',
        'contributing/python-sidecar-security',
        'contributing/writing-style',
      ],
    },
    'release-notes',
  ],
};

export default sidebars;
