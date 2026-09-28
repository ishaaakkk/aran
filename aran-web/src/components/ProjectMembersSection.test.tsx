import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { ProjectMembersList } from './ProjectMembersSection'
import { removeThenFollowUp } from '../lib/projectMembers'
import type { ProjectMemberLine } from '../lib/projectMembers'
import type { TenantMember } from '../services/types'

/**
 * Audit J1-F4: the project members endpoints existed with no UI, so an owner who invited Ana had no
 * way to let her see anything. The section lists who can see the project and, for an owner only,
 * adds and removes — never the last member.
 *
 * No jsdom in this project; `renderToStaticMarkup` renders the presentational half with the state
 * that decides it.
 */

const line = (overrides: Partial<ProjectMemberLine> = {}): ProjectMemberLine => ({
  userId: 'u-alice', name: 'Alice', email: 'alice@upc.edu', isSelf: true, known: true, ...overrides,
})
const ana: TenantMember = {
  userId: 'u-ana', email: 'ana@upc.edu', displayName: 'Ana', role: 'MEMBER', joinedAt: '',
}

function render(props: Partial<React.ComponentProps<typeof ProjectMembersList>> = {}) {
  return renderToStaticMarkup(
    <ProjectMembersList
      projectName="papers"
      lines={[line()]}
      candidates={[ana]}
      canManage
      busy={false}
      loadError={null}
      actionError={null}
      onRetry={() => {}}
      onAdd={() => {}}
      onRemove={() => {}}
      {...props}
    />,
  )
}

/** The whole `<button …>` tag whose aria-label is `label`. */
const buttonTag = (html: string, label: string) =>
  html.match(new RegExp(`<button[^>]*aria-label="${label}"[^>]*>`))?.[0] ?? ''

describe('ProjectMembersList', () => {
  it('an owner can add someone from the organization who is not yet a member', () => {
    const html = render()
    expect(html).toContain('aria-label="Add a member to papers"')
    expect(html).toContain('Ana — ana@upc.edu')
    expect(html).toContain('sees no project until they are added to one')
  })

  it('the last member cannot be removed, and the control says why', () => {
    const html = render()
    const remove = buttonTag(html, 'Remove Alice from papers')
    expect(remove).toContain('disabled=""')
    expect(remove).toContain('The last member cannot be removed')
  })

  it('with two members, an owner can remove the other one', () => {
    const html = render({ lines: [line(), line({ userId: 'u-ana', name: 'Ana', isSelf: false })] })
    expect(buttonTag(html, 'Remove Ana from papers')).not.toContain('disabled=""')
  })

  it('a member sees who can see the project but no controls', () => {
    const html = render({ canManage: false, lines: [line(), line({ userId: 'u-ana', name: 'Ana', isSelf: false })] })
    expect(html).toContain('Ana')
    expect(html).not.toContain('Add a member')
    expect(html).not.toContain('Remove Ana')
    expect(html).toContain('An organization owner can change who is in it')
  })

  it('a load failure is an alert with Retry, not an empty list', () => {
    const html = render({ lines: null, loadError: 'request failed: 500' })
    expect(html).toContain('role="alert"')
    expect(html).toContain('request failed: 500')
    expect(html).toContain('Retry')
    expect(html).not.toContain('Members (0)')
  })

  it('a failed organization read is said, not rendered as "everyone is a member"', () => {
    // Review of J1-F4: the failure was swallowed into an empty list, so the select said everyone
    // was already a member and every line said they had left the organization.
    const html = render({
      namesUnavailable: true,
      candidates: [],
      lines: [line(), line({ userId: 'u-x', name: 'u-x…', email: null, isSelf: false, known: null })],
    })
    expect(html).toContain('Could not load the organization’s members')
    expect(html).toContain('Retry')
    expect(html).not.toContain('Everyone in the organization is a member')
    expect(html).not.toContain('no longer in the organization')
  })

  it('a reload that fails with a list on screen says so, rather than the old list reading as success', () => {
    const html = render({ loadError: 'request failed: 500' })
    expect(html).toContain('role="alert"')
    expect(html).toContain('request failed: 500')
    expect(html).toContain('The list below is from before')
    expect(html).toContain('Members (1)')
  })

  it('says when someone is no longer in the organization rather than dropping them', () => {
    const html = render({ lines: [line(), line({ userId: 'u-gone', name: 'u-gone12…', email: null, isSelf: false, known: false })] })
    expect(html).toContain('no longer in the organization')
  })
})

describe('removeThenFollowUp', () => {
  it('removing yourself hands back to the panel instead of reloading a list you cannot read', async () => {
    const remove = vi.fn(async () => {})
    const reload = vi.fn(async () => {})
    const onSelfRemoved = vi.fn()

    await removeThenFollowUp(line({ isSelf: true }), { remove, reload, onSelfRemoved })

    expect(remove).toHaveBeenCalledWith('u-alice')
    expect(onSelfRemoved).toHaveBeenCalledOnce()
    expect(reload).not.toHaveBeenCalled()
  })

  it('removing someone else reloads the list', async () => {
    const reload = vi.fn(async () => {})
    const onSelfRemoved = vi.fn()

    await removeThenFollowUp(line({ userId: 'u-ana', isSelf: false }), { remove: async () => {}, reload, onSelfRemoved })

    expect(reload).toHaveBeenCalledOnce()
    expect(onSelfRemoved).not.toHaveBeenCalled()
  })
})
