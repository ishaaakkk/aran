import {existsSync} from 'node:fs';
import {themes as prismThemes} from 'prism-react-renderer';
import type {Config, PresetConfig} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

// Published on GitHub Pages under the repository's path: the deploy job in
// .github/workflows/docs.yml builds with DOCS_BASE_URL=/aran/. Every other build —
// local, and the gate's link and diagram checks — serves the site from the root.
const siteUrl = process.env.DOCS_SITE_URL ?? 'https://dtak-upc.github.io';
const baseUrl = process.env.DOCS_BASE_URL ?? '/';

const githubRepo = 'https://github.com/dtak-upc/aran';

// The API reference renders a committed OpenAPI snapshot, so building the docs
// never needs a running backend. Registering the preset only when the snapshot is
// present keeps a checkout without one building cleanly instead of failing on a
// missing file — the CI job in .github/workflows/docs.yml is what guarantees the
// snapshot exists and is current on main.
const openApiSpec = 'openapi/aran-api.json';
const hasOpenApiSnapshot = existsSync(openApiSpec);

if (!hasOpenApiSnapshot) {
  console.warn(
    `[aran-docs] No ${openApiSpec} — the REST reference will be absent from this build.\n` +
      '           Generate it with:\n' +
      "             ./gradlew :aran-application:test --tests '*OpenApiGenerationTest'\n" +
      '             node scripts/openapi-sync.mjs --write',
  );
}

const redocPreset: PresetConfig[] = hasOpenApiSnapshot
  ? [
      [
        'redocusaurus',
        {
          specs: [{id: 'aran-api', spec: openApiSpec, route: '/api/rest-endpoints/'}],
          theme: {primaryColor: '#1f6feb'},
        },
      ],
    ]
  : [];

const config: Config = {
  title: 'Aran',
  tagline: 'Your data sources were never designed to fit together. Aran integrates them anyway.',
  favicon: 'img/favicon.svg',

  future: {
    v4: true,
  },

  url: siteUrl,
  baseUrl,

  organizationName: 'dtak-upc',
  projectName: 'aran',

  // A broken internal link is a build failure, not a warning — the milestone
  // gate depends on this being fatal.
  onBrokenLinks: 'throw',
  onBrokenAnchors: 'throw',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  markdown: {
    mermaid: true,
    hooks: {
      onBrokenMarkdownLinks: 'throw',
    },
  },

  themes: ['@docusaurus/theme-mermaid'],

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          // The landing page owns '/', so the docs live under '/docs'. Everything
          // that links into them — navbar, footer, README — carries that prefix;
          // doc-to-doc links are relative .md paths and resolve on their own.
          routeBasePath: '/docs',
          editUrl: `${githubRepo}/tree/main/docs-site/`,
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
        sitemap: {
          lastmod: 'date',
          changefreq: 'weekly',
        },
      } satisfies Preset.Options,
    ],
    ...redocPreset,
  ],

  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    docs: {
      sidebar: {
        hideable: true,
      },
    },
    mermaid: {
      options: {
        // Mermaid's default shrinks a diagram to the width of its container,
        // which turns a wide sequence diagram into unreadable 5px text. Render
        // at natural size instead and let the container scroll (see custom.css).
        flowchart: {useMaxWidth: false},
        sequence: {useMaxWidth: false},
      },
    },
    navbar: {
      title: 'Aran',
      logo: {
        alt: 'Aran',
        src: 'img/logo.svg',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Documentation',
        },
        {
          to: '/docs/getting-started/quickstart',
          label: 'Quickstart',
          position: 'left',
        },
        {
          to: '/docs/oql/syntax-reference',
          label: 'OQL',
          position: 'left',
        },
        {
          href: githubRepo,
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Start here',
          items: [
            {label: 'What is Aran', to: '/docs/overview/what-is-aran'},
            {label: 'Quickstart', to: '/docs/getting-started/quickstart'},
            {label: 'Core concepts', to: '/docs/overview/core-concepts'},
          ],
        },
        {
          title: 'Reference',
          items: [
            {label: 'OQL syntax', to: '/docs/oql/syntax-reference'},
            {label: 'Data repositories', to: '/docs/repositories/'},
            {label: 'REST API', to: '/docs/api/'},
          ],
        },
        {
          title: 'Project',
          items: [
            {label: 'Contributor guide', to: '/docs/contributing/'},
            {label: 'Release notes', to: '/docs/release-notes'},
            {label: 'GitHub', href: githubRepo},
          ],
        },
      ],
      copyright: `Aran — Universitat Politècnica de Catalunya (DTAK). Docs built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['java', 'sql', 'bash', 'yaml', 'json', 'python', 'properties'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
