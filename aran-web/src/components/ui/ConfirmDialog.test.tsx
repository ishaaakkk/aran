import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { ConfirmDialog } from './ConfirmDialog'
import { canConfirm } from '@/lib/confirm'

/**
 * J8-F14: one confirmation, built on the one Dialog, replacing the native `window.confirm`s.
 * No jsdom in this project; static markup is enough to pin what the dialog says and which of its
 * buttons can be pressed.
 */

function render(extra: Partial<Parameters<typeof ConfirmDialog>[0]> = {}) {
  return renderToStaticMarkup(
    <ConfirmDialog
      open
      title="Delete dataset acm?"
      consequences={['Its 3 alignments are rejected in every project.', 'The lake copy is kept.']}
      confirmLabel="Delete dataset"
      onConfirm={() => {}}
      onCancel={() => {}}
      {...extra}
    />,
  )
}

/** The whole `<button …>` tag carrying a testid, so assertions do not depend on attribute order. */
function buttonTag(html: string, testId: string): string {
  return html.match(new RegExp(`<button[^>]*data-testid="${testId}"[^>]*>`))?.[0] ?? ''
}

describe('ConfirmDialog', () => {
  it('is a real dialog named by its title, listing every consequence', () => {
    const html = render()
    expect(html).toContain('role="alertdialog"')
    expect(html).toMatch(/<h2 id="[^"]+"[^>]*>Delete dataset acm\?<\/h2>/)
    expect(html).toContain('<li>Its 3 alignments are rejected in every project.</li>')
    expect(html).toContain('<li>The lake copy is kept.</li>')
    expect(html).toContain('Delete dataset')
  })

  it('puts Cancel before the destructive button, so Cancel takes initial focus', () => {
    const html = render()
    expect(html.indexOf('confirm-dialog-cancel')).toBeLessThan(html.indexOf('confirm-dialog-confirm'))
  })

  it('keeps the typed-name tier disabled until the name is typed', () => {
    const html = render({ typeToConfirm: 'acm' })
    expect(html).toContain('confirm-dialog-typed')
    expect(buttonTag(html, 'confirm-dialog-confirm')).toMatch(/\sdisabled=""/)
    expect(buttonTag(html, 'confirm-dialog-cancel')).not.toMatch(/\sdisabled=""/)
  })

  it('renders a failure inside the dialog, as an alert', () => {
    const html = render({ error: 'Conflict: nothing was deleted.' })
    expect(html).toContain('role="alert"')
    expect(html).toContain('Conflict: nothing was deleted.')
  })

  it('renders nothing when closed', () => {
    expect(render({ open: false })).toBe('')
  })
})

describe('canConfirm', () => {
  it('allows the plain tier unless busy', () => {
    expect(canConfirm('', undefined, false)).toBe(true)
    expect(canConfirm('', undefined, true)).toBe(false)
  })

  it('requires the exact typed name, ignoring surrounding whitespace', () => {
    expect(canConfirm('', 'acm', false)).toBe(false)
    expect(canConfirm('ac', 'acm', false)).toBe(false)
    expect(canConfirm(' acm ', 'acm', false)).toBe(true)
    expect(canConfirm('ACM', 'acm', false)).toBe(false)
  })
})

function renderRemoval(extra: Partial<React.ComponentProps<typeof ConfirmDialog>> = {}) {
  return renderToStaticMarkup(
    <ConfirmDialog
      open
      title="Remove Marta from UPC Data Lab?"
      consequences={['They lose access immediately.', 'Every API token they hold is revoked.']}
      confirmLabel="Remove Marta"
      onConfirm={() => {}}
      onCancel={() => {}}
      testId="confirm"
      {...extra}
    />,
  )
}

describe('ConfirmDialog as an alertdialog (A8 admin surfaces)', () => {
  it('is an alertdialog named by its title, listing each consequence', () => {
    const html = renderRemoval()
    expect(html).toContain('role="alertdialog"')
    expect(html).toContain('<li>They lose access immediately.</li>')
    expect(html).toContain('<li>Every API token they hold is revoked.</li>')
  })

  it('is named by its heading and described by its consequences, so both are announced on open', () => {
    const html = renderRemoval()
    const labelledBy = html.match(/aria-labelledby="([^"]+)"/)?.[1]
    const describedBy = html.match(/aria-describedby="([^"]+)"/)?.[1]
    expect(labelledBy).toBeTruthy()
    expect(describedBy).toBeTruthy()
    expect(html).toMatch(new RegExp(`<h2 id="${labelledBy}"[^>]*>Remove Marta from UPC Data Lab\\?</h2>`))
    expect(html).toMatch(new RegExp(`<ul id="${describedBy}"[^>]*><li>They lose access immediately.</li>`))
    // One name, not two: the heading, rather than a copy of it that could drift.
    expect(html).not.toContain('aria-label="Remove Marta')
  })

  it('puts Cancel before the destructive verb, so Enter on open does not destroy', () => {
    const html = renderRemoval()
    expect(html.indexOf('>Cancel<')).toBeGreaterThan(-1)
    expect(html.indexOf('>Cancel<')).toBeLessThan(html.indexOf('Remove Marta</button>'))
  })

  it('renders nothing when closed', () => {
    expect(render({ open: false })).toBe('')
  })

  it('shows the action’s failure inside the dialog', () => {
    expect(render({ error: 'this is the tenant’s only owner' })).toContain('role="alert"')
  })

  it('keeps the confirm disabled until the typed confirmation matches', () => {
    const html = renderRemoval({ typeToConfirm: 'marta@upc.edu' })
    const confirm = html.match(/<button[^>]*data-testid="confirm-confirm"[^>]*>/)?.[0] ?? ''
    expect(confirm).toContain('disabled=""')
    expect(html).toContain('marta@upc.edu')
  })

  it('disables both buttons while the action runs', () => {
    const html = renderRemoval({ busy: true })
    const buttons = html.match(/<button[^>]*>/g) ?? []
    expect(buttons.length).toBe(2)
    expect(buttons.every((b) => b.includes('disabled=""'))).toBe(true)
  })
})
