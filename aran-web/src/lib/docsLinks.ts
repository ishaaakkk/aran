/**
 * Links from the app into the user documentation (docs-site/, a Docusaurus site whose docs live
 * under `/docs/`). The SPA had none: a screen that named a door — JDBC, Iceberg — left the reader
 * to find the page that says how to open it (J6-F4).
 *
 * The base is the published site. A deployment that mirrors the docs elsewhere (an air-gapped
 * install, a fork) sets `VITE_DOCS_URL` at build time.
 */

const PUBLISHED_DOCS = 'https://dtak-upc.github.io/aran'

const configured: unknown = import.meta.env.VITE_DOCS_URL

export const DOCS_BASE_URL: string = (
  typeof configured === 'string' && configured.trim() !== '' ? configured.trim() : PUBLISHED_DOCS
).replace(/\/+$/, '')

/** A page of the docs site, as its path under `docs-site/docs/` without the `.md`. */
export type DocsPage =
  | 'programmatic-access/jdbc'
  | 'programmatic-access/iceberg'
  | 'programmatic-access/mcp'

export const DOCS_PAGES: readonly DocsPage[] = [
  'programmatic-access/jdbc',
  'programmatic-access/iceberg',
  'programmatic-access/mcp',
]

export function docsUrl(page: DocsPage, base: string = DOCS_BASE_URL): string {
  return `${base}/docs/${page}`
}
