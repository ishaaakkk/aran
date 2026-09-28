import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { CompilePanel } from './CompilePanel'
import type { IntegrationResult } from './types'

/**
 * The compile-and-promote panel's words (UI audit J4-F22, J4-F25). Rendered in node with
 * `renderToStaticMarkup`, as the rest of the suite; the graph canvas is not reached because no
 * integrated graph is passed.
 */

const noop = () => {}

const RESULT: IntegrationResult = {
  draftVersionId: 'sv-3',
  draftVersionNumber: '3',
  tripleCount: '412',
  unusedAlignments: '[]',
  turtle: '@prefix nx: <http://example.org/nx#> .',
  integrationRunId: 'run-1',
  mode: 'rebuild',
}

function render(opts: { promoted?: boolean; hasBaseline?: boolean; result?: IntegrationResult | null } = {}) {
  return renderToStaticMarkup(
    <CompilePanel
      rebuildRequired={false}
      onIntegrate={noop}
      integrating={false}
      compileJobId={null}
      selectedProjectId="proj-1"
      enrolledDatasetIds={['ds-1', 'ds-2']}
      incrementalDatasetId=""
      setIncrementalDatasetId={noop}
      canIncrement={false}
      addableDatasetIds={[]}
      datasetName={(id) => id}
      onIncrementalAdd={noop}
      hasBaseline={opts.hasBaseline ?? true}
      integrationResult={opts.result === undefined ? RESULT : opts.result}
      onCompileDone={noop}
      integrationError={null}
      promoted={opts.promoted ?? false}
      onPromoteDraft={noop}
      promoting={false}
      integratedGraph={null}
      integratedGraphLoading={false}
      integratedGraphError={null}
      integratedSelectedNode={null}
      setIntegratedSelectedNode={noop}
    />,
  )
}

describe('CompilePanel — what a promotion changes (J4-F25)', () => {
  it('does not say every product now reads the promoted version', () => {
    const html = render({ promoted: true })
    expect(html).toContain('align-version-badge')
    expect(html).not.toContain('Every query and product now reads this version')
    expect(html).toContain('MATERIALIZED products keep serving their snapshot')
    expect(html).toContain('VIRTUAL')
  })
})

describe('CompilePanel — one verb set, expert detail tucked away (J4-F22)', () => {
  it('says "promote the draft", the button\'s own verb, not "accept"', () => {
    const html = render({ hasBaseline: false, result: null })
    expect(html).not.toContain('accept the draft')
    expect(html).toContain('promote the draft')
  })

  it('keeps triple counts and Turtle behind a disclosure rather than in the header', () => {
    const html = render()
    expect(html).not.toContain('Size: 412 Triples')
    expect(html).toContain('data-testid="align-draft-turtle"')
    expect(html).toMatch(/<details[^>]*data-testid="align-draft-turtle"/)
    expect(html).toContain('412 triples')
  })
})
