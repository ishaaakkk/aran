import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { InfoTip } from './InfoTip'
import { CmdHeader } from './CmdHeader'
import { CONCEPTS } from '../lib/concepts'

/**
 * The concept tooltips exist for the person meeting "bootstrapping" or
 * "federated" for the first time, so the contract worth pinning is the copy
 * itself and its reachability: a real button with an accessible name, closed
 * until asked. No jsdom in this project; `renderToStaticMarkup` covers the
 * closed state (the open state is plain useState, not worth a DOM harness —
 * see SaveAsProductDialog.test.tsx for the idiom).
 */
describe('InfoTip', () => {
  it('renders an accessible, keyboard-reachable button, closed by default', () => {
    const html = renderToStaticMarkup(<InfoTip text={CONCEPTS.federation} label="federated execution" />)
    expect(html).toContain('aria-label="About federated execution"')
    expect(html).toContain('aria-expanded="false"')
    // Closed means the copy is not in the DOM at all — not merely hidden.
    expect(html).not.toContain('role="tooltip"')
    expect(html).not.toContain('queried live')
  })
})

describe('CmdHeader info slot', () => {
  it('shows a tip only when a panel opts in', () => {
    const withInfo = renderToStaticMarkup(<CmdHeader command="Join discovery" info={CONCEPTS.joinDiscovery} />)
    expect(withInfo).toContain('aria-label="About Join discovery"')
    const without = renderToStaticMarkup(<CmdHeader command="Projects" />)
    expect(without).not.toContain('data-testid="info-tip"')
  })
})

describe('concept glossary', () => {
  it('every entry is a real explanation', () => {
    for (const [key, text] of Object.entries(CONCEPTS)) {
      expect(text.length, key).toBeGreaterThan(80)
    }
  })
})
