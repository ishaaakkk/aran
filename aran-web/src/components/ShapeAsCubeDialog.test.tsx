import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import {
  FactStep,
  HierarchiesStep,
  MeasuresStep,
  ReviewStep,
  ShapeAsCubeDialog,
} from './ShapeAsCubeDialog'
import { CALENDAR_PRESELECTED, rankFactCandidates, suggestDimensions, suggestMeasures } from '../lib/cubeSuggest'
import type { CubeDto, CubeEvidence } from '../services/types'

/**
 * What the "shape as cube" wizard SAYS — the half of it that is a modelling claim rather
 * than a layout.
 *
 * No jsdom in this project; `renderToStaticMarkup` is enough for output assertions (see
 * SaveAsProductDialog.test.tsx for the same idiom, including the MemoryRouter that satisfies
 * the saved state's `<Link>`). Effects do not run under SSR, which is exactly why each step
 * is its own presentational component: the container's first frame is the evidence fetch, and
 * nothing here could click through to step 3.
 *
 * The assertions are about honesty, not pixels: a HYPOTHESIS dimension must arrive unticked
 * and say why, a join key must be visibly excluded rather than missing, an unknown member
 * must be stated where the NULLs are, a flat dimension must be offered as legitimate, and the
 * as-is caveat must be on the screen where the person commits.
 */

const iri = (n: string) => `http://aran.com/schema#${n}`

const EVIDENCE: CubeEvidence = {
  classes: [
    {
      iri: iri('Order'), name: 'Order', rowCount: 2_100_000,
      properties: [
        { iri: iri('amount'), name: 'amount', xsd: 'xsd:decimal', numeric: true, ndv: null, nullFraction: 0 },
        { iri: iri('unit_price'), name: 'unit_price', xsd: 'xsd:decimal', numeric: true, ndv: null, nullFraction: 0 },
        { iri: iri('customer_id'), name: 'customer_id', xsd: 'xsd:long', numeric: true, ndv: null, nullFraction: 0.002 },
        { iri: iri('channel_code'), name: 'channel_code', xsd: 'xsd:string', numeric: false, ndv: 4, nullFraction: 0 },
        { iri: iri('order_date'), name: 'order_date', xsd: 'xsd:date', numeric: false, ndv: 1200, nullFraction: 0 },
      ],
    },
    {
      iri: iri('Customer'), name: 'Customer', rowCount: 48_000,
      properties: [
        { iri: iri('id'), name: 'id', xsd: 'xsd:long', numeric: true, ndv: 48_000, nullFraction: 0 },
        { iri: iri('country'), name: 'country', xsd: 'xsd:string', numeric: false, ndv: 12, nullFraction: 0 },
        { iri: iri('city'), name: 'city', xsd: 'xsd:string', numeric: false, ndv: 900, nullFraction: 0 },
      ],
    },
    {
      iri: iri('Channel'), name: 'Channel', rowCount: 4,
      properties: [{ iri: iri('code'), name: 'code', xsd: 'xsd:string', numeric: false, ndv: 4, nullFraction: 0 }],
    },
  ],
  edges: [
    {
      propertyIri: iri('placed_by'), propertyName: 'placed_by',
      leftClassIri: iri('Order'), rightClassIri: iri('Customer'),
      leftColIri: iri('customer_id'), rightColIri: iri('id'), tier: 'DECLARED',
    },
    {
      propertyIri: iri('via_channel'), propertyName: 'via_channel',
      leftClassIri: iri('Order'), rightClassIri: iri('Channel'),
      leftColIri: iri('channel_code'), rightColIri: iri('code'), tier: 'HYPOTHESIS',
    },
  ],
}

const CUBE: CubeDto = {
  id: 'cube-1',
  name: 'order_cube',
  description: null,
  factClassIri: iri('Order'),
  createdAt: '2026-08-29T10:00:00Z',
  products: [{ id: 'prod-1', role: 'FACT', name: 'order_cube_fact' }],
  definition: { factClassIri: iri('Order'), measures: [], dimensions: [], calendar: null },
  jobIds: ['job-1'],
}

const measures = () => suggestMeasures(EVIDENCE.classes[0], [iri('customer_id'), iri('channel_code')])
const dimensions = () => suggestDimensions(EVIDENCE, iri('Order'))

describe('ShapeAsCubeDialog shell', () => {
  it('opens into the evidence fetch rather than an empty candidate list', () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <ShapeAsCubeDialog open projectId="proj-1" onClose={() => {}} />
      </MemoryRouter>,
    )

    expect(html).toContain('shape-as-cube-dialog')
    expect(html).toContain('shape as cube')
    expect(html).toContain('loading evidence…')
    // The promise that the wizard is safe to explore, on the very first frame.
    expect(html).toContain('Nothing is written until the last step.')
  })

  it('renders nothing at all while closed', () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <ShapeAsCubeDialog open={false} projectId="proj-1" onClose={() => {}} />
      </MemoryRouter>,
    )

    expect(html).toBe('')
  })
})

describe('step 1 — fact', () => {
  it('lists ranked candidates with their evidence chips', () => {
    const html = renderToStaticMarkup(
      <FactStep candidates={rankFactCandidates(EVIDENCE)} selectedIri={null} onSelect={() => {}} />,
    )

    expect(html).toContain('cube-fact-Order')
    expect(html).toContain('2,100,000 rows')
    expect(html).toContain('2 joins out')
  })

  it('demotes a dimension-shaped class with the reason, instead of hiding it', () => {
    const html = renderToStaticMarkup(
      <FactStep candidates={rankFactCandidates(EVIDENCE)} selectedIri={null} onSelect={() => {}} />,
    )

    expect(html).toContain('cube-fact-Customer')
    expect(html).toContain('referenced by 1 class — looks like a dimension')
  })

  it('says what to do next when there is nothing to shape', () => {
    const html = renderToStaticMarkup(
      <FactStep candidates={[]} selectedIri={null} onSelect={() => {}} />,
    )

    expect(html).toContain('Compile and promote a schema version first')
  })
})

describe('step 2 — measures & dimensions', () => {
  // The calendar starts OFF (J6-F21): it generates no table in v1, so a pre-ticked one is a
  // default that produces nothing. The container's initial state is CALENDAR_PRESELECTED.
  function render(calendarOn = CALENDAR_PRESELECTED) {
    return renderToStaticMarkup(
      <MeasuresStep
        measures={measures()}
        dimensions={dimensions()}
        onToggleMeasure={() => {}}
        onAggregator={() => {}}
        onAdditivity={() => {}}
        onToggleDimension={() => {}}
        calendar={{ fromColumnIri: iri('order_date'), levels: ['year', 'quarter', 'month', 'day'] }}
        calendarOn={calendarOn}
        onToggleCalendar={() => {}}
      />
    )
  }

  /** Pulls one `<input .../>` tag out of the markup so a `checked` assertion does not depend
   *  on JSX attribute-emission order (same helper as SaveAsProductDialog.test.tsx). */
  function inputIn(html: string, testId: string): string {
    const block = html.split(`data-testid="${testId}"`)[1] ?? ''
    return block.match(/<input[^>]*>/)?.[0] ?? ''
  }

  it('shows an additivity chip on every measure, not just the awkward ones', () => {
    const html = render()

    expect(html).toContain('additive')
    expect(html).toContain('non-additive')
  })

  it('leaves a ratio-shaped column unticked and names the trap', () => {
    const html = render()

    expect(inputIn(html, 'cube-measure-amount')).toContain('checked')
    expect(inputIn(html, 'cube-measure-unit_price')).not.toContain('checked')
    expect(html).toContain('a SUM is meaningless and an AVG averages averages')
  })

  it('shows the join key excluded with its reason rather than dropping the column', () => {
    const html = render()

    expect(html).toContain('cube-measure-customer_id')
    expect(html).toContain('join key — summing it would add identifiers, not amounts')
  })

  it('groups dimensions under their trust tier', () => {
    const html = render()

    expect(html).toContain('DECLARED')
    expect(html).toContain('HYPOTHESIS')
  })

  it('ships a HYPOTHESIS dimension unticked, labelled "not provably to-one"', () => {
    const html = render()

    expect(inputIn(html, 'cube-dimension-customer')).toContain('checked')
    expect(inputIn(html, 'cube-dimension-channel')).not.toContain('checked')
    expect(html).toContain('not provably to-one — joining on it can double-count fact rows')
  })

  it('states the unknown member where the NULL FKs are', () => {
    const html = render()

    expect(html).toContain('0.20% of fact rows have no')
    expect(html).toContain('v1 does not materialize the member yet')
  })

  // Pin moved by J6-F21: the offer used to arrive ticked. It still renders both states, and the
  // default is now the unticked one, labelled definition-only.
  it('offers the generated calendar explicitly, unticked by default and labelled definition-only', () => {
    const html = render()

    expect(CALENDAR_PRESELECTED).toBe(false)
    expect(html).toContain('cube-calendar-offer')
    expect(html).toContain('from order_date')
    expect(html).toContain('v1 generates no calendar table')
    expect(html).toContain('definition only')
    expect(inputIn(html, 'cube-calendar-offer')).not.toContain('checked')
    expect(inputIn(render(true), 'cube-calendar-offer')).toContain('checked')
  })

  it('does not pre-tick anything over the fact\'s identifier, and offers a row count (J6-F14/J6-F21)', () => {
    const html = render()

    // order_date is never NULL and not a measure — it carries the row count, unticked here
    // because amount is already a ticked SUM.
    expect(html).toContain('cube-measure-row_count')
    expect(inputIn(html, 'cube-measure-row_count')).not.toContain('checked')
    expect(html).not.toContain('No numeric columns')
  })

  it('marks the wizard as a Preview — it has never run end to end against a live stack (V62)', () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <ShapeAsCubeDialog open projectId="proj-1" onClose={() => {}} />
      </MemoryRouter>,
    )
    expect(html).toContain('data-testid="cube-preview-badge"')
    // What Preview means is on screen, in words, not in a hover-only title naming a tracking ID.
    expect(html).toContain('data-testid="cube-preview-note"')
    expect(html).toMatch(/check the products it creates before relying on them/)
    expect(html).not.toMatch(/\bV62\b/)
  })
})

describe('step 3 — hierarchies', () => {
  it('offers the suggested levels with their distinct counts, and a way to keep flat', () => {
    const html = renderToStaticMarkup(
      <HierarchiesStep
        dimensions={dimensions().filter((d) => d.selected)}
        onSetHierarchy={() => {}}
      />,
    )

    expect(html).toContain('1. country')
    expect(html).toContain('2. city')
    expect(html).toContain('900 distinct')
    expect(html).toContain('keep flat')
    // Sample evidence is not a proven functional dependency, and the step says so.
    expect(html).toContain('not a proven functional dependency')
  })

  it('explains a dimension with no level chain instead of showing an empty card', () => {
    const flat = dimensions().map((d) => ({ ...d, selected: true, levelOptions: [], hierarchy: [] }))
    const html = renderToStaticMarkup(<HierarchiesStep dimensions={flat} onSetHierarchy={() => {}} />)

    expect(html).toContain('No level chain stands out')
  })

  it('says there is nothing to do when no dimension was accepted', () => {
    const html = renderToStaticMarkup(<HierarchiesStep dimensions={[]} onSetHierarchy={() => {}} />)

    expect(html).toContain('No dimensions accepted')
  })
})

describe('step 4 — review & create', () => {
  function render(over: { name?: string; created?: CubeDto | null; error?: string | null } = {}) {
    return renderToStaticMarkup(
      <MemoryRouter>
        <ReviewStep
          factName="Order"
          measureCount={1}
          dimensionCount={1}
          hierarchyCount={1}
          calendarOn
          name={over.name ?? 'order_cube'}
          onNameChange={() => {}}
          description=""
          onDescriptionChange={() => {}}
          error={over.error ?? null}
          created={over.created ?? null}
          onClose={() => {}}
        />
      </MemoryRouter>,
    )
  }

  it('summarises the star and carries the as-is caveat where the person commits', () => {
    const html = render()

    expect(html).toContain('Order')
    expect(html).toContain('1 measure')
    expect(html).toContain('1 dimension')
    expect(html).toContain('generated calendar')
    expect(html).toContain('cube-as-is-note')
    expect(html).toContain('current values, not history')
  })

  it('states the server name rule before the write rather than after a 400', () => {
    expect(render({ name: 'Order Cube' })).toContain('starting with a letter')
    expect(render({ name: 'order_cube' })).not.toContain('starting with a letter')
  })

  it('surfaces a failed create instead of a silently unchanged form', () => {
    expect(render({ error: 'fact class has no active schema' })).toContain('fact class has no active schema')
  })

  it('swaps the form for a confirmation once the cube is created', () => {
    const html = render({ created: CUBE })

    expect(html).toContain('cube created')
    expect(html).toContain('1 product written')
    expect(html).toContain('open data products')
    expect(html).not.toContain('cube-as-is-note')
  })
})
