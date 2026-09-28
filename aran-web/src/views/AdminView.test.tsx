import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import AdminView, { ActivityHeader, AdminScreen, NotAnOwnerPanel, SystemPanel, TokenRow } from './AdminView'
import { SectionState } from './admin/SectionState'
import type { AdminTokenSummary, SystemStatus, TenantMember } from '../services/types'

/**
 * The admin area's sections (audit J7-F1, J7-F3, J7-F5, J7-F8, J7-F11).
 *
 * No jsdom in this project; `renderToStaticMarkup` renders each section with the state that
 * decides it. What these pin is the class of failure the audit found: a failure shown as a blank
 * page, a dead token shown as live, an admin surface shown to someone it will refuse.
 */

/**
 * Renders a section for a role. The role is passed rather than set on the auth store: under
 * `renderToStaticMarkup` zustand serves its initial state (the server snapshot), whatever a test
 * sets — the same reason AdminView reads the store and hands the role to AdminScreen.
 */
function renderAs(role: 'OWNER' | 'MEMBER' | null, section: Parameters<typeof AdminScreen>[0]['section']) {
  return renderToStaticMarkup(
    <MemoryRouter initialEntries={[`/admin/${section}`]}>
      <AdminScreen section={section} role={role} />
    </MemoryRouter>,
  )
}

describe('AdminView — who may see it (J7-F5)', () => {
  it('a member gets a permission panel and no admin navigation or live form', () => {
    const html = renderAs('MEMBER', 'users')
    expect(html).toContain('Administration is for organization owners')
    expect(html).toContain('Back to workspace')
    expect(html).not.toContain('aria-label="Administration"')
    expect(html).not.toContain('Add someone')
  })

  it('a caller with no organization gets the same panel, not a live form', () => {
    expect(renderAs(null, 'credentials')).toContain('Administration is for organization owners')
  })

  it('an owner gets the section', () => {
    const html = renderAs('OWNER', 'users')
    expect(html).toContain('aria-label="Administration"')
    expect(html).toContain('Add someone')
  })

  it('an owner opening System sees that section loading on its own, not a blank page', () => {
    const html = renderAs('OWNER', 'system')
    expect(html).toContain('<h1>System</h1>')
    expect(html).toContain('Loading…')
  })

  it('the routed view sends an unknown section to Users rather than an error', () => {
    const html = renderToStaticMarkup(
      <MemoryRouter initialEntries={['/admin/nope']}>
        <Routes>
          <Route path="/admin/:section" element={<AdminView />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(html).not.toContain('admin-shell')
  })
})

describe('NotAnOwnerPanel', () => {
  it('names the owners to ask', () => {
    const members: TenantMember[] = [
      { userId: 'a', email: 'alice@upc.edu', displayName: 'Alice', role: 'OWNER', joinedAt: '' },
      { userId: 'm', email: 'marta@upc.edu', displayName: 'Marta', role: 'MEMBER', joinedAt: '' },
    ]
    const html = renderToStaticMarkup(
      <MemoryRouter><NotAnOwnerPanel organization="UPC Data Lab" members={members} /></MemoryRouter>,
    )
    expect(html).toContain('Alice')
    expect(html).toContain('mailto:alice@upc.edu')
    expect(html).not.toContain('Marta')
  })
})

describe('SectionState — a failure is the section’s own card (J7-F3)', () => {
  it('with no data: an alert naming what failed, the reason, a hint and Retry', () => {
    const html = renderToStaticMarkup(
      <SectionState
        resource={{ data: null, error: 'An internal server error occurred.', loading: false, refreshedAt: null }}
        what="storage"
        hint="The lake did not answer."
        onRetry={() => {}}
      >
        {() => <p>never rendered</p>}
      </SectionState>,
    )
    expect(html).toContain('role="alert"')
    expect(html).toContain('Could not load storage.')
    expect(html).toContain('An internal server error occurred.')
    expect(html).toContain('The lake did not answer.')
    expect(html).toContain('Retry')
    expect(html).not.toContain('never rendered')
  })

  it('with data: keeps the figures and says the refresh failed and how old they are', () => {
    const html = renderToStaticMarkup(
      <SectionState
        resource={{ data: 42, error: 'timed out', loading: false, refreshedAt: new Date('2026-09-25T14:02:00Z') }}
        what="usage"
        onRetry={() => {}}
      >
        {(n) => <p>value {n}</p>}
      </SectionState>,
    )
    expect(html).toContain('value 42')
    expect(html).toContain('Refreshing usage failed: timed out')
    expect(html).toContain('14:02 UTC')
  })
})

describe('SystemPanel (J7-F3)', () => {
  const status: SystemStatus = {
    version: '0.1.0', commit: 'abc123', status: 'DOWN',
    components: [
      { name: 'lake', status: 'DOWN', details: { error: 'ConnectException', url: 'http://lake:8085' } },
      { name: 'fuseki', status: 'UP', details: {} },
    ],
  }

  it('gives the lake a card saying what stops working, not just a DOWN cell', () => {
    const html = renderToStaticMarkup(<SystemPanel status={status} />)
    expect(html).toContain('data-testid="admin-system-down-lake"')
    expect(html).toContain('Lake (file and snapshot storage) is DOWN')
    expect(html).toContain('Storage cannot be measured')
    expect(html).toContain('error=ConnectException')
  })

  it('gives an UP component no card', () => {
    expect(renderToStaticMarkup(<SystemPanel status={status} />)).not.toContain('admin-system-down-fuseki')
  })

  it('shows no attention block when everything is up', () => {
    const html = renderToStaticMarkup(
      <SystemPanel status={{ ...status, status: 'UP', components: [{ name: 'lake', status: 'UP', details: {} }] }} />,
    )
    expect(html).not.toContain('admin-system-down')
  })
})

describe('ActivityHeader (J7-F1)', () => {
  it('states what is recorded — promote, role changes, membership — and what is not yet', () => {
    const html = renderToStaticMarkup(<ActivityHeader />)
    expect(html).toContain('schema promoted')
    expect(html).toContain('role changed')
    expect(html).toContain('member removed')
    expect(html).toContain('does not mean it did not happen')
  })

  it('never reads as a complete list of what is missing — creations are not recorded either', () => {
    // Review of J7-F1: "Not recorded yet: deletes, compiles, …" read as complete, so an owner asking
    // "who created this product?" concluded creation would have been recorded.
    const html = renderToStaticMarkup(<ActivityHeader />)
    expect(html).toContain('Everything else is not recorded yet')
    expect(html).toContain('creating or deleting repositories, datasets, products and cubes')
    expect(html).toContain('accepting or rejecting alignments')
  })
})

describe('TokenRow (J7-F8)', () => {
  const token = (overrides: Partial<AdminTokenSummary> = {}): AdminTokenSummary => ({
    id: 'tok-1', userId: 'u-marta', ownerName: 'Marta', name: 'notebook', prefix: 'aran_pat_ab12',
    scope: 'READ', lastUsedAt: null, expiresAt: null, revoked: false, createdAt: '', status: 'active',
    ...overrides,
  })
  const row = (t: AdminTokenSummary) =>
    renderToStaticMarkup(<table><tbody><TokenRow token={t} onRevoke={() => {}} /></tbody></table>)

  it('offers Revoke on a live token, named for screen readers', () => {
    expect(row(token())).toContain('aria-label="Revoke token notebook held by Marta"')
  })

  it('shows a token whose holder was removed as such, struck through, still revocable', () => {
    const html = row(token({ status: 'owner_removed' }))
    expect(html).toContain('holder removed')
    expect(html).toContain('is-revoked')
    expect(html).toContain('Revoke')
  })

  it('offers nothing on a revoked token', () => {
    expect(row(token({ status: 'revoked', revoked: true }))).not.toContain('Revoke token')
  })

  it('never calls a token active when the server did not say so', () => {
    const html = row(token({ status: undefined }))
    expect(html).toContain('unknown')
    expect(html).not.toContain('>active<')
  })
})
