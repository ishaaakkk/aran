import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { FilterBuilder } from './WorkbenchPanels'
import type { Predicate } from '../../lib/workbenchModel'

const catalogData = {
  classByName: {
    Paper: { name: 'Paper', iri: 'x:Paper', intView: 'int_Paper', rowCount: 0, enforcedConstraintCount: 0, sources: [],
      fields: [{ name: 'venue', type: 'string', key: false }, { name: 'year', type: 'string', key: false }] },
  },
} as never

const f = (id: string, field: string, value: string, or = false): Predicate =>
  ({ id, cls: 'Paper', field, type: 'string', op: '=', value, or })

function render(props: Partial<React.ComponentProps<typeof FilterBuilder>> = {}) {
  return renderToStaticMarkup(
    <FilterBuilder catalogData={catalogData} classes={['Paper']} onAdd={() => {}} onRemove={() => {}} {...props} />,
  )
}

describe('FilterBuilder — WHERE reads the way it runs (J5-F8)', () => {
  it('shows "venue AND 2023 OR 2024" as two groups, and says what a group means', () => {
    const html = render({ filters: [f('a', 'venue', 'VLDB'), f('b', 'year', '2023'), f('c', 'year', '2024', true)] })
    expect(html).toContain('data-testid="wb-where-groups"')
    expect(html.match(/role="group"/g)).toHaveLength(2)
    expect(html).toContain('Rows matching any group are kept; within a group, every condition must hold.')
    expect(html).toContain('+ and')
    expect(html).toContain('+ or group')
  })

  it('says nothing about groups when there is only one', () => {
    const html = render({ filters: [f('a', 'venue', 'VLDB'), f('b', 'year', '2023')] })
    expect(html).not.toContain('wb-where-caption')
  })

  it('names what each remove button removes', () => {
    const html = render({ filters: [f('a', 'venue', 'VLDB')] })
    expect(html).toContain('aria-label="Remove condition Paper.venue ="')
  })
})

describe('FilterBuilder — COUNT(*) AS n (J5-F9)', () => {
  it('shows the row count as a SELECT aggregate chip', () => {
    expect(render({ countAll: true })).toContain('COUNT(*) AS n')
  })
})

describe('FilterBuilder — paused while the text is hand-edited (J5-F4)', () => {
  it('says the clauses are in the text, offers an explicit rebuild, and edits nothing', () => {
    const html = render({ paused: true, onRebuild: () => {}, filters: [f('a', 'venue', 'VLDB')] })
    expect(html).toContain('data-testid="wb-builder-paused"')
    expect(html).toContain('Clauses are in the text')
    expect(html).toContain('data-testid="wb-rebuild"')
    expect(html).not.toContain('Remove condition')
    expect(html).toMatch(/data-testid="wb-add-clause"[^>]*disabled/)
    // Never the unfiltered claim while the text may hold a WHERE the builder cannot see.
    expect(html).not.toContain('the query runs unfiltered')
  })

  it('is live when the text is the builder’s', () => {
    const html = render({ filters: [] })
    expect(html).not.toContain('wb-builder-paused')
    expect(html).toContain('the query runs unfiltered')
  })
})

describe('FilterBuilder — the inspector seeds a draft, never a condition', () => {
  // The inspector's "filter on this field" used to commit `Paper.venue = ''` at once: zero rows
  // on a string column, a refusal on a number, and a "…" chip for a value nobody typed.
  it('opens the WHERE draft on the seeded field and adds nothing until a value is given', () => {
    const added: Predicate[] = []
    const html = render({
      filters: [],
      seed: { cls: 'Paper', field: 'year', type: 'string', nonce: 1 },
      onAdd: (p) => { added.push(p) },
    })
    expect(html).toContain('fb-draft')
    expect(html).toMatch(/<option value="Paper\.year" selected="">/)
    expect(html).toMatch(/<button class="fb-ok" disabled=""[^>]*aria-label="Add this condition"/)
    expect(html).not.toContain('Remove condition')
    expect(added).toEqual([])
  })

  it('offers the seeded class’s fields even before the class is in the query', () => {
    const html = render({ classes: [], seed: { cls: 'Paper', field: 'venue', type: 'string', nonce: 2 } })
    expect(html).toMatch(/<option value="Paper\.venue" selected="">/)
  })

  it('shows the seeded draft over hand-edited text, where the menu drafts stay closed', () => {
    const html = render({ paused: true, seed: { cls: 'Paper', field: 'venue', type: 'string', nonce: 3 } })
    expect(html).toContain('fb-draft')
    expect(html).toContain('data-testid="wb-builder-paused"')
  })
})
