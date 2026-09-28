import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { PlanTree } from './PlanTree'

/**
 * The plan view used to regex-parse `optimizedPlan`/`logicalPlan` STRINGS into a tree —
 * a second, drift-prone description of the same plan the golden corpus already pins via
 * `PlanShape`. This suite pins the replacement: the optimized tree renders from the
 * structured `planShape` contract only, and a missing/garbled one degrades to text
 * rather than a guessed or crashing tree. No jsdom in this project; `renderToStaticMarkup`
 * is enough for output assertions.
 */

// Mirrors PlanShapeTest.joinShape() (aran-operators/query-engine), byte-matched against the
// corpus golden at aran-operators/query-engine/src/test/resources/plan-goldens/two-class-join.txt:
// a two-class join over Artworks/IArtist optimizes to PROJECT(JOIN(SCAN, SCAN)) with the
// equi-key names shortened.
const TWO_CLASS_JOIN_SHAPE = JSON.stringify({
  kind: 'PROJECT',
  detail: 'title, artist',
  inputs: [
    {
      kind: 'JOIN',
      detail: 'inner, artist_id = id',
      inputs: [
        { kind: 'SCAN', detail: 'u_art_Artworks', inputs: [] },
        { kind: 'SCAN', detail: 'u_artist_IArtist', inputs: [] },
      ],
    },
  ],
})

function render(props: Partial<Parameters<typeof PlanTree>[0]> = {}): string {
  return renderToStaticMarkup(
    <PlanTree logicalPlan="" optimizedPlan="EnumerableProject(...)" {...props} />,
  )
}

describe('PlanTree — optimized view renders the structured planShape', () => {
  it('renders the right node kinds, nested under their parent', () => {
    const html = render({ planShape: TWO_CLASS_JOIN_SHAPE })

    expect(html).toContain('PROJECT')
    expect(html).toContain('JOIN')
    expect(html).toContain('u_art_Artworks')
    expect(html).toContain('u_artist_IArtist')
    // PROJECT wraps JOIN wraps two SCANs — two levels of nesting, so two pn-children wrappers.
    expect(html.match(/class="pn-children"/g)).toHaveLength(2)
    // and the JOIN text precedes its SCAN children in document order.
    expect(html.indexOf('JOIN')).toBeLessThan(html.indexOf('u_art_Artworks'))
  })

  it('gives a SCAN with access=MIXED a visually distinct chip', () => {
    const mixed = JSON.stringify({ kind: 'SCAN', detail: 'int_Artworks, access=MIXED', inputs: [] })
    const html = render({ planShape: mixed })

    expect(html).toContain('int_Artworks')
    expect(html).not.toContain('int_Artworks, access=MIXED') // the suffix is split out, not shown raw
    expect(html).toContain('class="pn-badge access access-mixed"')
    expect(html).toContain('MIXED')
  })

  it('gives a non-MIXED access mode the plain chip, not the distinct one', () => {
    const federated = JSON.stringify({ kind: 'SCAN', detail: 'u_art_Artworks, access=FEDERATED', inputs: [] })
    const html = render({ planShape: federated })

    expect(html).toContain('class="pn-badge access"')
    expect(html).not.toContain('access-mixed')
    expect(html).toContain('FEDERATED')
  })

  it('falls back to unstructured text, without crashing, when planShape is absent', () => {
    expect(() => render({ planShape: undefined, planShapeText: 'PROJECT[title]\n  SCAN[int_Artworks]' }))
      .not.toThrow()
    const html = render({ planShape: undefined, planShapeText: 'PROJECT[title]\n  SCAN[int_Artworks]' })

    expect(html).toContain('unstructured')
    expect(html).toContain('PROJECT[title]')
  })

  it('falls back to unstructured text, without crashing, when planShape is garbled', () => {
    expect(() => render({ planShape: '{not valid json', optimizedPlan: 'EnumerableProject(...)' }))
      .not.toThrow()
    const html = render({ planShape: '{not valid json', optimizedPlan: 'EnumerableProject(...)' })

    expect(html).toContain('unstructured')
    // no planShapeText given, so the raw optimizedPlan text is the last resort.
    expect(html).toContain('EnumerableProject(...)')
  })

  it('falls back to unstructured text when planShape parses but is not a plan-shaped object', () => {
    const html = render({ planShape: JSON.stringify({ notAKind: true }), optimizedPlan: 'EnumerableProject(...)' })

    expect(html).toContain('unstructured')
  })

  it('falls back to unstructured text, without crashing, when planShape is JSON-valid but shape-mismatched (missing detail/inputs)', () => {
    expect(() => render({ planShape: '{"kind":"SCAN"}', optimizedPlan: 'EnumerableTableScan(...)' }))
      .not.toThrow()
    const html = render({ planShape: '{"kind":"SCAN"}', optimizedPlan: 'EnumerableTableScan(...)' })

    expect(html).toContain('unstructured')
    expect(html).not.toContain('pn-root')
    expect(html).toContain('EnumerableTableScan(...)')
  })

  it('falls back to unstructured text, without crashing, when an inputs entry is null', () => {
    const withNullInput = JSON.stringify({ kind: 'PROJECT', detail: 'title', inputs: [null] })
    expect(() => render({ planShape: withNullInput, optimizedPlan: 'EnumerableProject(...)' }))
      .not.toThrow()
    const html = render({ planShape: withNullInput, optimizedPlan: 'EnumerableProject(...)' })

    expect(html).toContain('unstructured')
    expect(html).not.toContain('pn-root')
  })
})

describe('PlanTree — logical view is plain text, not a parsed tree', () => {
  it('renders logicalPlan verbatim in a <pre> when it is the only plan available', () => {
    // With no optimizedPlan, the initial view defaults to 'logical' (view is internal
    // state with no prop override) — the one way to observe its render without a click.
    const logicalPlan = 'LogicalProject(...)\n  LogicalJoin(...)\n    LogicalTableScan(int_IPaper)'
    const html = renderToStaticMarkup(
      <PlanTree logicalPlan={logicalPlan} optimizedPlan="" planShape={TWO_CLASS_JOIN_SHAPE} />,
    )

    expect(html).toContain('<pre class="plan-pre">')
    expect(html).toContain('LogicalJoin(...)')
    // It never builds a node tree for the logical view, even with a planShape available.
    expect(html).not.toContain('pn-root')
  })
})

describe('PlanTree — no plan at all', () => {
  it('shows a placeholder rather than an empty tree', () => {
    const html = render({ logicalPlan: '', optimizedPlan: '', planShape: undefined })
    expect(html).toContain('no plan available')
  })
})
