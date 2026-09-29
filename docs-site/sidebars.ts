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
 * The sidebar follows the product: introduction, getting started, then one
 * section per capability in the order data moves through Aran (connect, derive
 * and profile, align and version, query, serve), then reference, operations,
 * internals and contributing. Every page still belongs to one Diátaxis mode,
 * declared in a front-matter comment; the modes are the inside of each section,
 * not the top level, because readers arrive with a job rather than a genre.
 */
const sidebars: SidebarsConfig = {
  docsSidebar: [
    'index',
    {
      type: 'category',
      label: 'Introduction',
      collapsed: false,
      items: [
        'overview/what-is-aran',
        'overview/why-aran',
        'overview/core-concepts',
        'overview/status',
      ],
    },
    {
      type: 'category',
      label: 'Get Started',
      collapsed: false,
      items: ['getting-started/quickstart', 'getting-started/walkthrough'],
    },
    {
      type: 'category',
      label: 'Capabilities',
      collapsed: false,
      link: {type: 'doc', id: 'capabilities/index'},
      items: [
        {
          type: 'category',
          label: '1. Connect',
          link: {type: 'doc', id: 'capabilities/connect'},
          items: [
            'how-to/register-a-data-repository',
            {
              type: 'category',
              label: 'Federated access',
              link: {type: 'doc', id: 'capabilities/federated-access'},
              items: [
                'how-to/query-a-database-live',
                'query-engine/federated-execution-and-placement',
              ],
            },
            'repositories/index',
            'repositories/file-uploads',
            'repositories/relational-databases',
            'repositories/object-storage',
            'repositories/iceberg-rest-catalogs',
            'repositories/singer-taps',
          ],
        },
        {
          type: 'category',
          label: '2. Derive and profile',
          link: {type: 'doc', id: 'capabilities/derive-and-profile'},
          items: [
            'deep-dives/bootstrapping-methods',
            'how-to/bootstrap-and-profile-a-dataset',
            'how-to/write-wrapper-sql',
            'deep-dives/bootstrapping-and-the-schema-graph',
            'deep-dives/wrapper-fields',
          ],
        },
        {
          type: 'category',
          label: '3. Align and version',
          link: {type: 'doc', id: 'capabilities/align-and-version'},
          items: [
            'how-to/run-join-discovery',
            'how-to/align-and-integrate-schemas',
            'how-to/integrate-incrementally',
            'how-to/configure-denial-constraints',
          ],
        },
        {
          type: 'category',
          label: '4. Query',
          link: {type: 'doc', id: 'capabilities/query'},
          items: [
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
            'how-to/export-query-results',
            {
              type: 'category',
              label: 'Query optimisation',
              link: {type: 'doc', id: 'query-engine/index'},
              items: [
                'query-engine/typed-columns-and-costing',
                'query-engine/wrapper-optimisation',
                'query-engine/referential-evidence',
                'query-engine/denial-constraints',
                'deep-dives/query-pipeline',
              ],
            },
          ],
        },
        {
          type: 'category',
          label: '5. Serve',
          link: {type: 'doc', id: 'capabilities/serve'},
          items: [
            'how-to/create-a-data-product',
            'deep-dives/virtual-data-products',
          ],
        },
      ],
    },
    {
      type: 'category',
      label: 'Connect Your Tools',
      link: {type: 'doc', id: 'programmatic-access/index'},
      items: [
        'programmatic-access/jdbc',
        'programmatic-access/python-sdk',
        'programmatic-access/mcp',
        'programmatic-access/iceberg',
        'programmatic-access/temporal-types',
        'how-to/invoke-a-capability-operator',
      ],
    },
    {
      type: 'category',
      label: 'Reference',
      items: ['overview/glossary', 'administration/configuration-reference', apiSection],
    },
    {
      type: 'category',
      label: 'Operate',
      items: [
        'administration/standalone',
        'administration/production-build',
        'administration/docker-compose',
        'administration/license-configuration',
        'deep-dives/multi-tenancy',
        'deep-dives/licensing-modes',
        'administration/troubleshooting',
      ],
    },
    {
      type: 'category',
      label: 'Internals',
      items: ['overview/architecture', 'deep-dives/driver-resolution'],
    },
    {
      type: 'category',
      label: 'Contribute',
      link: {type: 'doc', id: 'contributing/index'},
      items: [
        'contributing/module-layout',
        'contributing/build-and-test',
        'how-to/write-a-new-operator',
        'contributing/comment-philosophy',
        'contributing/branching-and-merge-gate',
        'contributing/e2e-suite',
        'contributing/python-sidecar-security',
        'contributing/writing-style',
      ],
    },
  ],
};

export default sidebars;
