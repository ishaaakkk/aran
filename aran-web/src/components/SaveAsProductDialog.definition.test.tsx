import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { SaveAsProductDialog } from './SaveAsProductDialog'

/**
 * J5-F1: a product saved from the workbench is defined by the run whose result is on screen, and
 * the dialog shows that definition before it is saved — kept in its own file so the access-mode
 * tests beside it stay about access modes.
 */
function render(definition?: string): string {
  return renderToStaticMarkup(
    <MemoryRouter>
      <SaveAsProductDialog
        projects={[]} projectId="" onProjectIdChange={() => {}}
        name="" onNameChange={() => {}} description="" onDescriptionChange={() => {}}
        accessMode="MATERIALIZED" onAccessModeChange={() => {}}
        schemaVersionNumber={3} saving={false} error={null} savedId={null}
        onSave={() => {}} onClose={() => {}}
        definition={definition}
      />
    </MemoryRouter>,
  )
}

describe('SaveAsProductDialog definition', () => {
  it('shows the OQL it will save, read-only', () => {
    const html = render('SELECT Paper.title FROM Paper')
    expect(html).toContain('data-testid="product-definition"')
    expect(html).toContain('SELECT Paper.title FROM Paper')
    // Read-only: a <pre>, never an input the page object's "first two inputs" contract would hit.
    expect(html).not.toMatch(/<input[^>]*value="SELECT Paper/)
  })

  it('warns that a LIMIT in the definition caps the product — the builder appends one (J5-F14)', () => {
    const html = render('SELECT Paper.title FROM Paper\nLIMIT 100')
    expect(html).toContain('data-testid="product-definition-limit"')
    expect(html).toContain('at most 100 rows')
    expect(render('SELECT Paper.title FROM Paper')).not.toContain('product-definition-limit')
  })

  it('renders no definition block when none is given', () => {
    expect(render()).not.toContain('product-definition')
  })
})
