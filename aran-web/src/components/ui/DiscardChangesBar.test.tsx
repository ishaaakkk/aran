/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { DiscardChangesBar } from './DiscardChangesBar'

/**
 * UI audit J2-F26: a filled-in repository form was lost to one click outside it, Escape did
 * nothing in the dataset dialog, and focus was not trapped. The three dialogs the audit names
 * move onto the shared Dialog primitive, and dismissing an edited form asks first — inline, in
 * the same dialog, naming what is lost.
 */
describe('DiscardChangesBar (J2-F26)', () => {
  it('names what would be lost and offers both ways out', () => {
    const html = renderToStaticMarkup(
      <DiscardChangesBar what="this repository" onKeep={() => {}} onDiscard={() => {}} />,
    )
    expect(html).toContain('role="alert"')
    expect(html).toContain('Discard your changes to this repository?')
    expect(html).toContain('Keep editing')
    expect(html).toContain('data-testid="discard-changes-confirm"')
  })

  // Announced but out of reach: focus stayed in the form, so a keyboard user had to Tab to the
  // end of a long dialog to answer. The safe answer takes focus when the question appears (React
  // focuses an autoFocus element on mount), so Enter keeps editing and Tab reaches Discard.
  it('puts focus on the safe answer, "Keep editing", and not on Discard', () => {
    const html = renderToStaticMarkup(
      <DiscardChangesBar what="this repository" onKeep={() => {}} onDiscard={() => {}} />,
    )
    expect(html).toMatch(/<button[^>]*autofocus=""[^>]*>Keep editing<\/button>/)
    expect(html).not.toMatch(/<button[^>]*autofocus=""[^>]*data-testid="discard-changes-confirm"/)
  })
})

describe('the J2-F26 dialogs route every dismissal through dismissOutcome', () => {
  it.each(['components/RegisterRepositoryDialog.tsx', 'components/RegisterDatasetDialog.tsx'])(
    '%s: a second Escape while asking keeps editing',
    (file) => {
      const text = readFileSync(join(process.cwd(), 'src', file), 'utf8')
      expect(text).toMatch(/dismissOutcome\(\{ dirty, asking: confirmingDiscard \}\)/)
    },
  )
})

describe('the J2-F26 dialogs sit on the shared Dialog', () => {
  it.each(['components/RegisterRepositoryDialog.tsx', 'components/RegisterDatasetDialog.tsx', 'views/DatasetsListView.tsx'])(
    '%s has no hand-rolled role="button" backdrop',
    (file) => {
      const text = readFileSync(join(process.cwd(), 'src', file), 'utf8')
      expect(text).not.toMatch(/className="fixed inset-0[^"]*"[\s\S]{0,300}?role="button"/)
    },
  )
})
