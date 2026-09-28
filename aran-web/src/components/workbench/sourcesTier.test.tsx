import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { CatalogBrowser } from './WorkbenchPanels'

/**
 * SQL mode's catalog panel shows the sources-tier authoring names — the table and column
 * names a SQL statement actually types (`SELECT title FROM acm`), which are neither the OQL
 * class names nor the IRI-bearing view internals.
 */

const CATALOG_DATA = { classes: [], edges: [], classByName: {} } as never

describe('the sources tier in the catalog panel', () => {
  it('renders one entry per source table, keyed by its authoring name', () => {
    const html = renderToStaticMarkup(
      <CatalogBrowser
        catalogData={CATALOG_DATA}
        sourceTables={[
          { name: 'acm', datasetId: 'ds1', columns: ['author_id', 'title', 'year'] },
          { name: 'researchers', datasetId: 'ds3', columns: ['id', 'name'] },
        ]}
        onSelect={() => {}} onAddClass={() => {}}
      />,
    )
    expect(html).toContain('data-testid="catalog-source-acm"')
    expect(html).toContain('data-testid="catalog-source-researchers"')
    expect(html).toContain('source tables')
    // The authoring column names are visible — they are what the statement types.
    expect(html).toContain('author_id')
  })

  it('renders no sources section when the prop is absent — OQL mode is unchanged', () => {
    const html = renderToStaticMarkup(
      <CatalogBrowser
        catalogData={CATALOG_DATA}
        onSelect={() => {}} onAddClass={() => {}}
      />,
    )
    expect(html).not.toContain('source tables')
  })
})
