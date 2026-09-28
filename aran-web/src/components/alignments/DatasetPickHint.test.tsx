import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { DatasetPickHint, type DatasetPickState } from './DatasetPickHint'

const render = (state: DatasetPickState) =>
  renderToStaticMarkup(<MemoryRouter><DatasetPickHint state={state} /></MemoryRouter>)

/**
 * Review of UA2 (MAJOR, AlignmentsView half): the pane said "This project has no datasets yet —
 * Register a dataset" whenever the project's load had finished, including after some of its
 * repositories failed to be read.
 */
describe('DatasetPickHint', () => {
  it('a partial load with nothing in it is not "no datasets yet", and offers no Register', () => {
    const html = render('incomplete')
    expect(html).toContain('could not be read')
    expect(html).not.toContain('no datasets yet')
    expect(html).not.toContain('href="/datasets"')
  })

  it('only a complete, empty load says "no datasets yet" and links to Register', () => {
    const html = render('empty')
    expect(html).toContain('This project has no datasets yet.')
    expect(html).toContain('href="/datasets"')
  })

  it.each<DatasetPickState>(['loading', 'error', 'pick'])('%s makes no emptiness claim', (state) => {
    expect(render(state)).not.toContain('no datasets yet')
  })
})
