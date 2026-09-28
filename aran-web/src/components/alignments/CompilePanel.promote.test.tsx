import { describe, expect, it, vi } from 'vitest'
import type { ComponentProps } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'

vi.mock('@/services/keycloak', () => ({ ensureFreshToken: async () => 'token', tokenParsed: () => undefined }))

/**
 * J4-F11 (second review of UA2): a promote that is neither confirmed nor failed — the page stopped
 * waiting, or the model could not be read after a NOT_A_DRAFT — was rendered under "Integration
 * Failed" as "Promotion failed: … it was already promoted". lib/promoteOutcome.ts decides; this
 * pins that the panel shows such an outcome as a status with a way to re-check.
 */
const render = async (over: Partial<ComponentProps<typeof import('./CompilePanel').CompilePanel>>) => {
  const { CompilePanel } = await import('./CompilePanel')
  const noop = () => {}
  return renderToStaticMarkup(
    <CompilePanel
      rebuildRequired={false}
      onIntegrate={noop}
      integrating={false}
      compileJobId={null}
      selectedProjectId="p1"
      enrolledDatasetIds={['d1', 'd2']}
      incrementalDatasetId=""
      setIncrementalDatasetId={noop}
      canIncrement={false}
      addableDatasetIds={[]}
      datasetName={(id) => id}
      onIncrementalAdd={noop}
      hasBaseline={false}
      integrationResult={null}
      onCompileDone={noop}
      integrationError={null}
      promoted={false}
      onPromoteDraft={noop}
      promoting={false}
      integratedGraph={null}
      integratedGraphLoading={false}
      integratedGraphError={null}
      integratedSelectedNode={null}
      setIntegratedSelectedNode={noop}
      {...over}
    />,
  )
}

describe('CompilePanel with a promote that is not confirmed', () => {
  it('shows it as a status with Check again, not under "Integration Failed"', async () => {
    const html = await render({
      promoteCheck: {
        message: 'This compile run cannot be promoted: it was already promoted. The model could not be read to check whether this run is the active one now — check again.',
        checking: false,
      },
      onRecheckPromote: () => {},
    })
    const note = html.slice(html.indexOf('data-testid="align-promote-unconfirmed"'))
    expect(note).toContain('Promotion not confirmed yet')
    expect(note).toContain('Check again')
    // The heading and the message are the announcement; Check again sits outside it.
    expect(html).toMatch(/<p role="status" data-testid="align-promote-status" class="[^"]*"><span[^>]*>Promotion not confirmed yet<\/span>This compile run cannot be promoted/)
    expect(html.match(/data-testid="align-promote-status"[^>]*>(.*?)<\/p>/)?.[1]).not.toContain('Check again')
    expect(html).not.toContain('Integration Failed')
    expect(html).not.toContain('Promotion failed')
  })

  it('shows nothing of the kind when there is no such promote — its status region waits, empty', async () => {
    const html = await render({})
    expect(html).not.toContain('align-promote-unconfirmed')
    expect(html).toContain('<p role="status" data-testid="align-promote-status" class="sr-only"></p>')
  })

  it('keeps Check again focusable while it checks (third review of UA2)', async () => {
    const html = await render({ promoteCheck: { message: 'Not confirmed.', checking: true }, onRecheckPromote: () => {} })
    const again = html.match(/<button[^>]*>(?:(?!<\/button>).)*Checking…<\/button>/)?.[0] ?? ''
    expect(again).toContain('aria-disabled="true"')
    expect(again).not.toContain('disabled=""')
  })

  it('is what AlignmentsView gives a pending or unconfirmed promote — only "failed" reaches the error box', async () => {
    const { readFileSync } = await import('node:fs')
    const source = readFileSync(new URL('../../views/AlignmentsView.tsx', import.meta.url), 'utf8')
    const apply = source.slice(source.indexOf('const applyPromoteOutcome'), source.indexOf('const handleRecheckPromote'))
    expect(apply).toContain("} else if (outcome.kind === 'failed') {")
    expect(apply).toContain('setPromoteCheck({ err, runId, message: outcome.message, checking: false })')
    expect(source).toContain('promoteCheck={promoteCheck}')
    expect(source).toContain('onRecheckPromote={() => void handleRecheckPromote()}')
  })
})
