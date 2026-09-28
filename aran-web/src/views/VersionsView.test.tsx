import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'

vi.mock('@/services/keycloak', () => ({ ensureFreshToken: async () => 'token', tokenParsed: () => undefined }))

/**
 * Third review of UA2 (MINOR, J4-F11): a promote or rollback on /versions that outlived its 150 s
 * deadline said "still working… check the result before trying again" in the modal, beside an
 * enabled Rollback, with the list unrefreshed — and a retried rollback that lands creates another
 * identical ACTIVE version. lib/versionActionOutcome.ts decides; no DOM here to press the buttons,
 * so these pin the page's region and read the wiring from the source.
 */
describe('VersionsView after a promote or rollback the page stopped waiting for', () => {
  it('has its outcome region mounted, empty, before anything happens', async () => {
    const { VersionsView } = await import('./VersionsView')
    const html = renderToStaticMarkup(<VersionsView />)
    expect(html).toContain('<p role="status" data-testid="versions-action-status" class="sr-only"></p>')
    expect(html).not.toContain('versions-action-check')
  })

  it('closes the modal and re-reads the list instead of leaving "still working" beside the button', () => {
    const source = readFileSync(new URL('./VersionsView.tsx', import.meta.url), 'utf8')
    const promote = source.slice(source.indexOf('const doPromote = async'), source.indexOf('const doRollback = async'))
    expect(promote).toContain("if (stillRunning(err)) {\n        setPromoteTarget(null);\n        await checkAction('promote', target, newestBefore);")
    const rollback = source.slice(source.indexOf('const doRollback = async'), source.indexOf('const actionTone'))
    expect(rollback).toContain("if (stillRunning(err)) {\n        setRollbackTarget(null);\n        await checkAction('rollback', target, newestBefore);")
    const check = source.slice(source.indexOf('const checkAction = async'), source.indexOf('const doPromote = async'))
    expect(check).toContain('await api.listSchemaVersions().catch(() => null)')
    expect(check).toContain('setVersions(versionsNow)')
    expect(check).toContain('describeVersionActionTimeout(action, target, newestBefore, versionsNow)')
  })
})
