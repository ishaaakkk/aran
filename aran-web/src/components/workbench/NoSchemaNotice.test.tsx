import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { NoSchemaNotice } from './NoSchemaNotice'

/** The workbench before any promote (J1-F15 = J5-F16). */
describe('NoSchemaNotice', () => {
  it('names the missing prerequisite and links to each step towards it', () => {
    const html = renderToStaticMarkup(<MemoryRouter><NoSchemaNotice /></MemoryRouter>)
    expect(html).toContain('No schema is promoted yet.')
    expect(html).toContain('href="/datasets"')
    expect(html).toContain('href="/alignments"')
  })

  it('is shown beside the editor, never instead of it — oql-editor and Run stay (workbench-contract)', () => {
    const view = readFileSync(new URL('../../views/OqlPlanView.tsx', import.meta.url), 'utf8')
    expect(view).toContain('{catalog?.empty && <NoSchemaNotice />}\n        {body}')
  })
})
