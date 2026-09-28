import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { ApiTokensDialog, TokenList } from './ApiTokensDialog'
import type { ApiTokenSummary } from '../services/types'

/**
 * The token list's states (J6-F18 = J7-F21). The defects: a failed load read "no tokens yet",
 * one click revoked a token a nightly export depended on, Escape did not close the dialog.
 * No jsdom here — the list is presentational so each state renders directly.
 */

const TOKEN: ApiTokenSummary = {
  id: 'tok-1',
  name: 'nightly export',
  prefix: 'aran_pat_3f2a',
  scope: 'READ',
  status: 'active',
  expiresAt: null,
  lastUsedAt: '2026-09-20T02:00:00Z',
  revokedAt: null,
  createdAt: '2026-08-01T00:00:00Z',
}

const noop = () => {}

function list(over: Partial<Parameters<typeof TokenList>[0]> = {}) {
  return renderToStaticMarkup(
    <TokenList
      tokens={[TOKEN]}
      loading={false}
      loadError={null}
      onRetry={noop}
      confirmingId={null}
      onAskRevoke={noop}
      onConfirmRevoke={noop}
      onCancelRevoke={noop}
      revoking={false}
      {...over}
    />,
  )
}

describe('TokenList', () => {
  it('renders a failed load as an error with Retry, never as "no tokens yet"', () => {
    const html = list({ tokens: [], loadError: 'Request timed out' })
    expect(html).toContain('data-testid="api-token-load-error"')
    expect(html).toContain('role="alert"')
    expect(html).toContain('Request timed out')
    expect(html).toContain('retry')
    expect(html).not.toContain('no tokens yet')
  })

  it('says "no tokens yet" only for a list that loaded empty', () => {
    expect(list({ tokens: [] })).toContain('no tokens yet')
  })

  it('revoking asks first, naming the token, its scope and its last use', () => {
    const idle = list()
    expect(idle).toContain('data-testid="api-token-revoke-tok-1"')
    expect(idle).toContain('aria-label="Revoke token nightly export"')
    expect(idle).not.toContain('api-token-revoke-confirm-tok-1')

    const asking = list({ confirmingId: 'tok-1' })
    expect(asking).toContain('data-testid="api-token-revoke-confirm-tok-1"')
    expect(asking).toContain('data-testid="api-token-revoke-cancel-tok-1"')
    expect(asking).toContain('nightly export')
    expect(asking).toContain('read')
    expect(asking).toMatch(/last used/)
    expect(asking).toMatch(/cannot be undone/)
  })

  it('the confirmation takes focus on its safe choice and is described by its warning', () => {
    const asking = list({ confirmingId: 'tok-1' })
    // Focus lands on "keep", never on "revoke": a stray Enter must not cut the token off.
    expect(asking).toMatch(/<button[^>]*autofocus=""[^>]*data-testid="api-token-revoke-cancel-tok-1"/)
    expect(asking).not.toMatch(/<button[^>]*autofocus=""[^>]*data-testid="api-token-revoke-confirm-tok-1"/)
    // A screen reader hears the question, not just "keep, button".
    expect(asking).toContain('aria-describedby="api-token-revoke-warning-tok-1"')
    expect(asking).toMatch(/id="api-token-revoke-warning-tok-1"[^>]*>Revoke “nightly export”/)
  })

  it('says a never-used token was never used', () => {
    expect(list({ confirmingId: 'tok-1', tokens: [{ ...TOKEN, lastUsedAt: null }] })).toContain('never used')
  })
})

describe('ApiTokensDialog', () => {
  it('is the Dialog primitive — a named dialog Escape closes — keeping its testid', () => {
    const html = renderToStaticMarkup(<ApiTokensDialog isOpen onClose={noop} />)
    expect(html).toContain('role="dialog"')
    expect(html).toContain('aria-label="API tokens"')
    expect(html).toContain('data-testid="api-tokens-dialog"')
  })
})
