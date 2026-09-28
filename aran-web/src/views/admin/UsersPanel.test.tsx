import { describe, expect, it, vi } from 'vitest'
import { isValidElement, type ReactElement, type ReactNode } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import type { TenantMember } from '@/services/types'
import type { ResourceState } from './useAdminResource'

// Every write the Users section can make. The role-select test asserts none of them is reached from
// a change event alone (J7-F4); the rest never call them.
const apiWrites = vi.hoisted(() => ({
  changeTenantMemberRole: vi.fn(),
  removeTenantMember: vi.fn(),
  resetTenantMemberPassword: vi.fn(),
  createTenantMember: vi.fn(),
}))
vi.mock('@/services/api', () => ({
  api: { ...apiWrites, listTenantMembers: vi.fn(() => new Promise(() => {})) },
}))

const { HandoverPanel, MemberRow, RemovalPanel, UsersPanel, UsersSection } = await import('./UsersPanel')

/**
 * The Users section's rows and handover panel (audit J7-F4, J7-F16, J7-F18, UI-OD9).
 *
 * No jsdom in this project: `renderToStaticMarkup` renders each piece with the props that decide
 * it — what an owner can and cannot do to their own row, the lock on the only owner, and what the
 * panel shows after a create versus a reinstatement.
 */

const user = (overrides: Partial<TenantMember> = {}): TenantMember => ({
  userId: 'u-marta', email: 'marta@upc.edu', displayName: 'Marta', role: 'MEMBER',
  joinedAt: '2026-09-01T10:00:00Z', lastSeenAt: null, ...overrides,
})

/** The first element in a rendered tree that `match` accepts — a stand-in for a DOM query. */
function find(node: ReactNode, match: (el: ReactElement<Record<string, unknown>>) => boolean): ReactElement<Record<string, unknown>> | null {
  if (Array.isArray(node)) {
    for (const child of node) {
      const found = find(child, match)
      if (found) return found
    }
    return null
  }
  if (!isValidElement<Record<string, unknown>>(node)) return null
  if (match(node)) return node
  return find(node.props.children as ReactNode, match)
}

function row(u: TenantMember, isSelf = false, onlyOwner = false) {
  return renderToStaticMarkup(
    <table><tbody>
      <MemberRow user={u} isSelf={isSelf} onlyOwner={onlyOwner} onPropose={() => {}} />
    </tbody></table>,
  )
}

/** The whole `<button …>` tag whose aria-label is `label`, attribute order aside. */
function buttonTag(html: string, label: string): string {
  return html.match(new RegExp(`<button[^>]*aria-label="${label}"[^>]*>`))?.[0] ?? ''
}

describe('MemberRow', () => {
  it('marks your own row and disables removing or resetting yourself', () => {
    const html = row(user({ displayName: 'Alice', role: 'OWNER' }), true)
    expect(html).toContain('(you)')
    expect(buttonTag(html, 'Remove Alice')).toContain('disabled=""')
    expect(buttonTag(html, 'Reset password for Alice')).toContain('disabled=""')
  })

  it('leaves another member removable', () => {
    const html = row(user())
    expect(html).not.toContain('(you)')
    expect(buttonTag(html, 'Remove Marta')).not.toContain('disabled=""')
  })

  it('locks the only owner’s role select and says why', () => {
    const html = row(user({ role: 'OWNER' }), false, true)
    const select = html.match(/<select[^>]*>/)?.[0] ?? ''
    expect(select).toContain('disabled=""')
    expect(select).toContain('The only owner cannot be demoted')
  })

  it('shows last activity, and "never" for someone never seen — not a blank', () => {
    expect(row(user({ lastSeenAt: null }))).toContain('>never<')
  })

  it('choosing a role only proposes it — nothing is committed until the confirmation (J7-F4)', () => {
    // The original defect: onChange called the API, so ArrowDown on a focused select made someone
    // an owner. A change event must reach onPropose and nothing else.
    const onPropose = vi.fn()
    const marta = user()
    const tree = MemberRow({ user: marta, isSelf: false, onlyOwner: false, onPropose })
    const select = find(tree, (el) => el.type === 'select')
    expect(select).not.toBeNull()

    ;(select!.props.onChange as (e: unknown) => void)({ target: { value: 'OWNER' } })

    expect(onPropose).toHaveBeenCalledWith({ kind: 'role', user: marta, next: 'OWNER' })
    for (const write of Object.values(apiWrites)) expect(write).not.toHaveBeenCalled()
  })
})

describe('HandoverPanel', () => {
  it('after a create: the password once, with Copy invitation beside it', () => {
    const html = renderToStaticMarkup(
      <HandoverPanel
        organization="UPC Data Lab"
        onDismiss={() => {}}
        handover={{ reset: false, created: { member: user(), temporaryPassword: 'k7Fq2mZp', reinstated: false } }}
      />,
    )
    expect(html).toContain('k7Fq2mZp')
    expect(html).toContain('Copy invitation')
    expect(html).toContain('at their next sign-in')
    expect(html.toLowerCase()).not.toContain('keycloak')
  })

  it('after a reinstatement: a new password, that the old one is dead, and what did not come back', () => {
    const html = renderToStaticMarkup(
      <HandoverPanel
        organization={null}
        onDismiss={() => {}}
        handover={{ reset: false, created: { member: user(), temporaryPassword: 'n3wPassw0rd', reinstated: true } }}
      />,
    )
    expect(html).toContain('Access restored for Marta')
    expect(html).toContain('n3wPassw0rd')
    expect(html).toContain('the one they had no longer works')
    expect(html).toContain('stay revoked')
    expect(html).toContain('Copy sign-in details')
  })
})

describe('RemovalPanel', () => {
  it('names the projects the removal handed to you, so none is lost without anyone knowing', () => {
    const html = renderToStaticMarkup(
      <RemovalPanel
        onDismiss={() => {}}
        removal={{
          name: 'Marta',
          result: { tokensRevoked: 1, projectAccessRemoved: 2, projectsHandedOver: [{ id: 'p', name: 'Sales' }] },
        }}
      />,
    )
    expect(html).toContain('Marta was removed — 1 API token revoked.')
    expect(html).toContain('Only Marta could see Sales')
    expect(html).toContain('role="status"')
  })
})

describe('UsersSection', () => {
  const state = (over: Partial<ResourceState<TenantMember[]>>): ResourceState<TenantMember[]> => ({
    data: null, error: null, loading: false, refreshedAt: null, ...over,
  })
  const section = (resource: ResourceState<TenantMember[]>) => renderToStaticMarkup(
    <UsersSection resource={resource} onRetry={() => {}} meId={null} roleFilter="" onRoleFilter={() => {}} onPropose={() => {}} />,
  )

  it('a failed read is a card with Retry, not an error line with no way back', () => {
    const html = section(state({ error: 'request failed: 503' }))
    expect(html).toContain('Could not load the organization’s members.')
    expect(html).toContain('request failed: 503')
    expect(html).toContain('Retry')
  })

  it('offers a role filter and a Last active column (J7-F17)', () => {
    const html = section(state({ data: [user()] }))
    expect(html).toContain('aria-label="Filter by role"')
    expect(html).toContain('Last active')
    expect(html).toContain('marta@upc.edu')
  })
})

describe('UsersPanel', () => {
  it('no longer tells an owner about the identity provider underneath (J7-F18)', () => {
    const html = renderToStaticMarkup(<MemoryRouter><UsersPanel /></MemoryRouter>)
    expect(html).toContain('Users')
    expect(html.toLowerCase()).not.toContain('keycloak')
  })

  it('shows its own read loading rather than an empty table', () => {
    const html = renderToStaticMarkup(<MemoryRouter><UsersPanel /></MemoryRouter>)
    expect(html).toContain('Loading…')
    expect(html).not.toContain('Nobody yet')
  })
})
