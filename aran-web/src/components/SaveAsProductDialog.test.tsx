import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { SaveAsProductDialog } from './SaveAsProductDialog'
import { defaultProductProject } from '../lib/productProject'
import type { Project, ProductAccessMode } from '../services/types'

/**
 * The creation dialog's access-mode choice (Phase 5): the one place a user decides whether a
 * product gets a stored, pull-refreshed snapshot or always reads live. Both the offer and the
 * default matter — a wrong default would make "always fresh, heavier per query" the accidental
 * choice for someone who never meant to pick it.
 *
 * No jsdom in this project; `renderToStaticMarkup` is enough for output assertions (see
 * DatasetWrapperTab.test.tsx for the same idiom). Radio `checked` state still renders correctly
 * under SSR — React reflects the controlled value into the static HTML either way.
 */

const PROJECT: Project = {
  id: 'proj-1',
  tenantId: 'tenant-1',
  name: 'demo project',
  description: '',
  createdBy: 'user-1',
  createdAt: '2026-08-01T00:00:00Z',
} as Project

function renderDialog(overrides: { accessMode?: ProductAccessMode; savedId?: string | null; definition?: string } = {}) {
  // The "saved" state renders a react-router <Link>, which throws without a router context
  // even under renderToStaticMarkup (it's a context requirement, not a DOM one) — MemoryRouter
  // satisfies it without needing jsdom.
  return renderToStaticMarkup(
    <MemoryRouter>
      <SaveAsProductDialog
        projects={[PROJECT]}
        projectId="proj-1"
        onProjectIdChange={() => {}}
        name="papers_by_author"
        onNameChange={() => {}}
        description=""
        onDescriptionChange={() => {}}
        accessMode={overrides.accessMode ?? 'MATERIALIZED'}
        onAccessModeChange={() => {}}
        schemaVersionNumber={3}
        saving={false}
        error={null}
        savedId={overrides.savedId ?? null}
        onSave={() => {}}
        onClose={() => {}}
        definition={overrides.definition ?? 'SELECT Paper.title FROM Paper'}
      />
    </MemoryRouter>,
  )
}

/** Pulls the whole `<input .../>` tag for a given `data-testid` out of a static-markup
 *  string, so an assertion about `checked` does not depend on JSX attribute-emission order. */
function inputTag(html: string, testId: string): string {
  const match = html.match(new RegExp(`<input[^>]*data-testid="${testId}"[^>]*/?>`))
  return match ? match[0] : ''
}

describe('SaveAsProductDialog access-mode choice', () => {
  it('offers both modes with the honest, non-performance-tip framing', () => {
    const html = renderDialog()

    expect(html).toContain('product-access-mode-materialized')
    expect(html).toContain('product-access-mode-virtual')
    expect(html).toContain('computed once to a snapshot; as fresh as its last refresh')
    // J6-F2: "always fresh" was false for a virtual product over a month-old upload — it is as
    // fresh as the sources the active schema points at, and it gives things up (Iceberg, the
    // agent row limit, breakage) that the choice must name.
    expect(html).not.toContain('always fresh')
    expect(html).toContain('recomputed on every read from the sources the active schema points at')
    expect(html).toContain('not served over Iceberg')
  })

  it('says the product is defined against a version, not pinned to it (J6-F1)', () => {
    // A refresh re-plans the OQL under whatever schema is ACTIVE then (DataProduct.java: "a
    // record, not an execution pin"), so "Pinned to schema v3" told users something false.
    const html = renderDialog()

    expect(html).not.toMatch(/pinned to schema/i)
    expect(html).toContain('Defined against schema v3')
    expect(html).toContain('whatever schema is active then')
  })

  it('defaults to MATERIALIZED selected, not VIRTUAL', () => {
    const html = renderDialog({ accessMode: 'MATERIALIZED' })

    expect(inputTag(html, 'product-access-mode-materialized')).toContain('checked')
    expect(inputTag(html, 'product-access-mode-virtual')).not.toContain('checked')
  })

  it('reflects a VIRTUAL choice back as the selected radio', () => {
    const html = renderDialog({ accessMode: 'VIRTUAL' })

    expect(inputTag(html, 'product-access-mode-virtual')).toContain('checked')
    expect(inputTag(html, 'product-access-mode-materialized')).not.toContain('checked')
  })

  it('swaps the form for a confirmation once the product is saved', () => {
    const html = renderDialog({ savedId: 'prod-new-1' })

    expect(html).toContain('data product created')
    expect(html).not.toContain('product-access-mode-choice')
  })
})

describe('SaveAsProductDialog — a first-time save lands somewhere readable (J6-F5)', () => {
  it('says a saved MATERIALIZED product is a DRAFT nobody can read until it is refreshed', () => {
    const html = renderDialog({ savedId: 'prod-new-1', accessMode: 'MATERIALIZED' })

    expect(html).toContain('data-testid="product-saved-note"')
    expect(html).toMatch(/DRAFT/)
    expect(html).toMatch(/refresh/i)
  })

  it('says a saved VIRTUAL product is readable now', () => {
    const html = renderDialog({ savedId: 'prod-new-1', accessMode: 'VIRTUAL' })

    expect(html).toContain('data-testid="product-saved-note"')
    expect(html).not.toMatch(/DRAFT/)
  })

  it('links to the product it created, not just the products page — and keeps the link text the e2e page object waits for', () => {
    const html = renderDialog({ savedId: 'prod-new-1' })

    expect(html).toContain('href="/products?product=prod-new-1"')
    expect(html).toContain('open data products')
  })

  it('shows the query it is about to save, read-only', () => {
    const html = renderDialog({ definition: 'SELECT Paper.title FROM Paper WHERE Paper.year > 2020' })

    expect(html).toContain('data-testid="product-definition"')
    expect(html).toContain('SELECT Paper.title FROM Paper WHERE Paper.year &gt; 2020')
  })

  it('is a real dialog: named, with labelled fields', () => {
    const html = renderDialog()

    expect(html).toContain('role="dialog"')
    expect(html).toContain('aria-label="Save as data product"')
    expect(html).toContain('for="product-save-project"')
    expect(html).toContain('for="product-save-name"')
  })
})

describe('defaultProductProject — the dialog opens on the project the user is working in', () => {
  const A = { ...PROJECT, id: 'a' } as Project
  const B = { ...PROJECT, id: 'b' } as Project

  it('keeps a choice the user already made in this dialog', () => {
    expect(defaultProductProject('b', 'a', [A, B])).toBe('b')
  })

  it('preselects the active project (the breadcrumb) when nothing is chosen yet', () => {
    expect(defaultProductProject('', 'a', [A, B])).toBe('a')
  })

  it('preselects the only project there is', () => {
    expect(defaultProductProject('', null, [A])).toBe('a')
  })

  it('does not guess between several projects with none active, nor keep an active id that no longer exists', () => {
    expect(defaultProductProject('', null, [A, B])).toBe('')
    expect(defaultProductProject('', 'gone', [A, B])).toBe('')
  })
})
