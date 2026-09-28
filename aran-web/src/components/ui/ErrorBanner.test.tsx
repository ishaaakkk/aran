/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { ErrorBanner } from './ErrorBanner'
import { SaveAsProductDialog } from '../SaveAsProductDialog'

/**
 * UI audit J6-F13: a refused delete or a failed refresh was explained in light rose on pink —
 * about 1.6:1 in the default light skin. ErrorBanner sits on the semantic destructive pair each
 * skin defines at AA contrast, and is announced. The audit names three files to start with; the
 * guard below keeps the hand-rolled rose block out of them.
 */
describe('ErrorBanner (J6-F13)', () => {
  it('is announced and uses the semantic destructive tokens', () => {
    const html = renderToStaticMarkup(<ErrorBanner title="Refresh failed">lake unreachable</ErrorBanner>)
    expect(html).toContain('role="alert"')
    expect(html).toContain('bg-destructive')
    expect(html).toContain('text-destructive-foreground')
    expect(html).toContain('Refresh failed')
    expect(html).toContain('lake unreachable')
  })

  it('carries the save dialog\'s error', () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <SaveAsProductDialog
          definition="SELECT * FROM Paper"
          projects={[]}
          projectId=""
          onProjectIdChange={() => {}}
          name="x"
          onNameChange={() => {}}
          description=""
          onDescriptionChange={() => {}}
          accessMode="MATERIALIZED"
          onAccessModeChange={() => {}}
          schemaVersionNumber={3}
          saving={false}
          error="a product named x already exists"
          savedId={null}
          onSave={() => {}}
          onClose={() => {}}
        />
      </MemoryRouter>,
    )
    expect(html).toContain('role="alert"')
    expect(html).toContain('a product named x already exists')
  })

  it.each(['views/ProductsView.tsx', 'components/SaveAsProductDialog.tsx', 'components/JobCard.tsx'])(
    '%s has no hand-rolled rose-on-pink error block',
    (file) => {
      const text = readFileSync(join(process.cwd(), 'src', file), 'utf8')
      expect(text).not.toMatch(/bg-rose-500\/10[^"]*text-rose-300|text-rose-300[^"]*bg-rose-500\/10/)
    },
  )
})
