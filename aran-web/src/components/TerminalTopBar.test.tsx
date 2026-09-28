import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'

/**
 * The top bar's jobs button (UI audit J8-F7): how many jobs run, whether any failed since the
 * drawer was last opened, both said to a screen reader — and the count drawn solid on the primary
 * token, since `text-violet-300` on a violet tint read at ~1.4:1 on the default light skin.
 */

vi.mock('../services/api', () => ({ api: { jobEvents: () => () => {}, listJobs: async () => [] } }))

const PALE = /\btext-(rose|red|pink|violet|purple)-(50|100|200|300|400)\b/

async function render(running: number, newFailures: number) {
  const { JobsButtonView } = await import('./TerminalTopBar')
  return renderToStaticMarkup(<JobsButtonView running={running} newFailures={newFailures} onOpen={() => {}} />)
}

describe('JobsButtonView', () => {
  it('counts running jobs on the primary token and names both counts in its label', async () => {
    const html = await render(2, 1)
    expect(html).toContain('data-testid="topbar-jobs-running"')
    expect(html).toContain('bg-primary text-primary-foreground')
    expect(html).toContain('aria-label="Jobs — 2 running, 1 failed since you last looked"')
    expect(html).toContain('data-testid="topbar-jobs-failed"')
    expect(html).not.toMatch(PALE)
  })

  it('is a plain "Jobs" button when nothing runs or failed', async () => {
    const html = await render(0, 0)
    expect(html).toContain('aria-label="Jobs"')
    expect(html).not.toContain('topbar-jobs-running')
    expect(html).not.toContain('topbar-jobs-failed')
  })
})
