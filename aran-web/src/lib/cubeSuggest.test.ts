import { describe, it, expect } from 'vitest'
import {
  buildCubeDefinition,
  isTrustedTier,
  isValidCubeName,
  rankFactCandidates,
  suggestCalendar,
  suggestDimensions,
  suggestHierarchy,
  suggestMeasures,
  toCubeName,
} from './cubeSuggest'
import type { DimensionDraft, MeasureDraft } from './cubeSuggest'
import type {
  CubeEdgeTier,
  CubeEvidence,
  CubeEvidenceClass,
  CubeEvidenceEdge,
  CubeEvidenceProperty,
} from '../services/types'

/**
 * The suggestion rules behind the "shape as cube" wizard, tested where they live: pure
 * functions over an evidence payload, no DOM and no backend (this project has no jsdom, and
 * these need none). What is asserted here is deliberately the *judgement*, not the plumbing —
 * that a dimension-shaped class is demoted with a reason rather than hidden, that a
 * HYPOTHESIS edge is never pre-ticked, that a join key is offered-and-excluded rather than
 * silently dropped, and that two columns with similar distinct counts are not called a
 * hierarchy. Each of those is a way the wizard could quietly propose a wrong model.
 */

const iri = (n: string) => `http://aran.com/schema#${n}`

function prop(name: string, over: Partial<CubeEvidenceProperty> = {}): CubeEvidenceProperty {
  return { iri: iri(name), name, xsd: null, numeric: false, ndv: null, nullFraction: null, ...over }
}

function cls(
  name: string,
  properties: CubeEvidenceProperty[],
  rowCount: number | null = null,
): CubeEvidenceClass {
  return { iri: iri(name), name, rowCount, properties }
}

function edge(
  leftClass: string, leftCol: string,
  rightClass: string, rightCol: string,
  tier: CubeEdgeTier,
  propertyName = `has_${rightClass}`,
): CubeEvidenceEdge {
  return {
    propertyIri: iri(propertyName),
    propertyName,
    leftClassIri: iri(leftClass),
    rightClassIri: iri(rightClass),
    leftColIri: iri(leftCol),
    rightColIri: iri(rightCol),
    tier,
  }
}

/** Order (fact) → Customer, Product. Order is the only class with joins *out*. */
function starEvidence(): CubeEvidence {
  return {
    classes: [
      cls('Order', [
        prop('amount', { numeric: true }),
        prop('quantity', { numeric: true }),
        prop('unit_price', { numeric: true }),
        prop('customer_id', { numeric: true, nullFraction: 0.002 }),
        prop('product_id', { numeric: true, nullFraction: 0 }),
        prop('order_date', { xsd: 'http://www.w3.org/2001/XMLSchema#date' }),
      ], 2_100_000),
      cls('Customer', [
        prop('id', { numeric: true, ndv: 48_000 }),
        prop('country', { ndv: 12 }),
        prop('city', { ndv: 900 }),
      ], 48_000),
      cls('Product', [
        prop('id', { numeric: true, ndv: 3_000 }),
        prop('category', { ndv: 20 }),
      ], 3_000),
    ],
    edges: [
      edge('Order', 'customer_id', 'Customer', 'id', 'DECLARED', 'placed_by'),
      edge('Order', 'product_id', 'Product', 'id', 'HYPOTHESIS', 'of_product'),
    ],
  }
}

describe('cube names', () => {
  it('accepts only the server rule and coerces labels into it', () => {
    expect(isValidCubeName('orders_cube')).toBe(true)
    expect(isValidCubeName('Orders')).toBe(false)
    expect(isValidCubeName('9lives')).toBe(false)
    expect(isValidCubeName('a'.repeat(64))).toBe(false)

    expect(toCubeName('Order Line')).toBe('order_line')
    expect(toCubeName('orderDate')).toBe('order_date')
    // Nothing legal survives, so it returns nothing rather than inventing a name.
    expect(toCubeName('2026')).toBe('')
    expect(isValidCubeName(toCubeName('Customer — EU!'))).toBe(true)
  })
})

describe('rankFactCandidates', () => {
  it('returns nothing for empty evidence rather than throwing', () => {
    expect(rankFactCandidates({ classes: [], edges: [] })).toEqual([])
  })

  it('ranks the class with rows, measures and joins out above its dimensions', () => {
    const ranked = rankFactCandidates(starEvidence())

    expect(ranked.map((c) => c.cls.name)).toEqual(['Order', 'Customer', 'Product'])
    expect(ranked[0].numericCount).toBe(3) // amount, quantity, unit_price — not the two FKs
    expect(ranked[0].edgesOut).toBe(2)
    expect(ranked[0].demotedReason).toBeNull()
  })

  it('demotes an edge-target with the reason in words, and still lists it', () => {
    const ranked = rankFactCandidates(starEvidence())
    const customer = ranked.find((c) => c.cls.name === 'Customer')!

    expect(customer.edgesIn).toBe(1)
    expect(customer.demotedReason).toBe('referenced by 1 class — looks like a dimension')
    // Demoted, never hidden: a user who knows their model must be able to overrule this.
    expect(ranked).toHaveLength(3)
  })

  it('treats a null rowCount as no evidence, not as zero rows', () => {
    const unprofiled = cls('Unprofiled', [prop('n', { numeric: true })], null)
    const ranked = rankFactCandidates({ classes: [unprofiled], edges: [] })

    expect(ranked[0].rowCount).toBeNull()
    expect(Number.isFinite(ranked[0].score)).toBe(true)
  })

  it('breaks ties by name so the ranking is stable across reloads', () => {
    const evidence: CubeEvidence = {
      classes: [cls('Zeta', [], 10), cls('Alpha', [], 10), cls('Mid', [], 10)],
      edges: [],
    }
    expect(rankFactCandidates(evidence).map((c) => c.cls.name)).toEqual(['Alpha', 'Mid', 'Zeta'])
  })
})

describe('suggestMeasures', () => {
  const order = starEvidence().classes[0]
  const joinCols = [iri('customer_id'), iri('product_id')]

  it('pre-ticks plain numeric columns as SUM / ADDITIVE', () => {
    const amount = suggestMeasures(order, joinCols).find((m) => m.name === 'amount')!

    expect(amount).toMatchObject({ selected: true, aggregator: 'SUM', additivity: 'ADDITIVE' })
    expect(amount.reason).toBeNull()
  })

  it('offers a _price column as NON_ADDITIVE and unticked, with the trap named', () => {
    const price = suggestMeasures(order, joinCols).find((m) => m.name === 'unit_price')!

    expect(price.selected).toBe(false)
    expect(price.additivity).toBe('NON_ADDITIVE')
    expect(price.reason).toContain('post-aggregation expression')
  })

  it('applies the same rule to _pct and _rate', () => {
    const c = cls('M', [prop('discount_pct', { numeric: true }), prop('tax_rate', { numeric: true })])
    expect(suggestMeasures(c).every((m) => m.additivity === 'NON_ADDITIVE' && !m.selected)).toBe(true)
  })

  it('shows a join key excluded WITH the reason instead of omitting the column', () => {
    const key = suggestMeasures(order, joinCols).find((m) => m.name === 'customer_id')!

    expect(key.excluded).toBe(true)
    expect(key.selected).toBe(false)
    expect(key.reason).toContain('join key')
  })

  it('treats every numeric column as a candidate when the class has no edges', () => {
    expect(suggestMeasures(order).filter((m) => m.excluded)).toHaveLength(0)
  })

  // J6-F14 moved this pin. It used to read "never offers a non-numeric column as a measure",
  // which left a fact made only of CSV columns (bootstrap types every CSV column xsd:string,
  // V58/V62) with "No numeric columns" and Continue disabled — while the server accepts COUNT
  // over any column (CubeService.validate). The claim that still holds, and is asserted here:
  // a non-numeric column is never offered as anything BUT COUNT, and never pre-ticked.
  it('offers a non-numeric column only as COUNT, unticked, with the reason', () => {
    const date = suggestMeasures(order, joinCols).find((m) => m.columnIri === iri('order_date'))!

    expect(date).toMatchObject({ aggregator: 'COUNT', selected: false, excluded: false, name: 'order_date_count' })
    expect(date.reason).toContain('only COUNT')
  })

  it('offers a row count over a never-NULL column, pre-ticked only when nothing else is (J6-F14)', () => {
    const csvFact = cls('Paper', [
      prop('paper_id', { ndv: 1000, nullFraction: 0 }),
      prop('title', { ndv: 990, nullFraction: 0.01 }),
      prop('venue', { ndv: 12, nullFraction: 0 }),
    ], 1000)
    const drafts = suggestMeasures(csvFact)
    const rowCount = drafts.find((m) => m.name === 'row_count')!

    expect(rowCount).toMatchObject({ columnIri: iri('paper_id'), aggregator: 'COUNT', additivity: 'ADDITIVE', selected: true })
    expect(rowCount.reason).toContain('never NULL')
    expect(drafts.filter((m) => m.selected)).toHaveLength(1)
  })

  it('leaves the row count unticked when a real measure is already ticked', () => {
    const c = cls('Order', [
      prop('amount', { numeric: true, nullFraction: 0 }),
      prop('order_no', { ndv: 500, nullFraction: 0 }),
    ], 500)
    const drafts = suggestMeasures(c)

    expect(drafts.find((m) => m.name === 'amount')!.selected).toBe(true)
    expect(drafts.find((m) => m.name === 'row_count')).toMatchObject({ columnIri: iri('order_no'), selected: false })
  })

  it('never turns a plain numeric measure into the row count, and offers none when no column is provably never NULL', () => {
    const c = cls('M', [prop('amount', { numeric: true, nullFraction: 0 }), prop('note', { nullFraction: 0.3 })], 10)
    const drafts = suggestMeasures(c)

    expect(drafts.find((m) => m.name === 'row_count')).toBeUndefined()
    expect(drafts.find((m) => m.name === 'amount')).toMatchObject({ aggregator: 'SUM', selected: true })
  })

  it('does not pre-tick a SUM over the fact\'s own identifier (J6-F21)', () => {
    // ndv >= 0.95 x rows: an id numbered 1..N. SUM(id) is a number, and a meaningless one.
    const c = cls('Order', [
      prop('order_id', { numeric: true, ndv: 2_000, nullFraction: 0.1 }),
      prop('amount', { numeric: true }),
    ], 2_000)
    const id = suggestMeasures(c).find((m) => m.columnIri === iri('order_id'))!

    expect(id).toMatchObject({ aggregator: 'COUNT', selected: false, name: 'order_id_count' })
    expect(id.reason).toContain('identifier')
  })

  it('suffixes two columns that slug to one name instead of POSTing a duplicate', () => {
    // totalAmount and total_amount are different columns with one slug; the server rejects
    // duplicate measure names and the dialog has no name field to recover from.
    const c = cls('M', [
      prop('totalAmount', { numeric: true }),
      prop('total_amount', { numeric: true }),
    ])
    const names = suggestMeasures(c).map((m) => m.name)

    expect(names).toEqual(['total_amount', 'total_amount_2'])
    expect(new Set(names).size).toBe(2)
  })
})

describe('suggestDimensions', () => {
  it('pre-ticks DECLARED and leaves HYPOTHESIS unticked with the double-counting reason', () => {
    const dims = suggestDimensions(starEvidence(), iri('Order'))

    expect(dims.map((d) => d.tier)).toEqual(['DECLARED', 'HYPOTHESIS'])
    const [customer, product] = dims
    expect(customer.selected).toBe(true)
    expect(product.selected).toBe(false)
    expect(product.reason).toBe('not provably to-one — joining on it can double-count fact rows')
  })

  it('pre-ticks nothing when every edge is a HYPOTHESIS', () => {
    const evidence = starEvidence()
    evidence.edges = evidence.edges.map((e) => ({ ...e, tier: 'HYPOTHESIS' as CubeEdgeTier }))
    const dims = suggestDimensions(evidence, iri('Order'))

    expect(dims).toHaveLength(2)
    expect(dims.some((d) => d.selected)).toBe(false)
  })

  it('turns a NULL-bearing FK into an unknown member so totals still reconcile', () => {
    const dims = suggestDimensions(starEvidence(), iri('Order'))

    expect(dims[0].unknownMember).toBe(true)
    expect(dims[0].nullFraction).toBeCloseTo(0.002)
    // A 0-null FK needs no unknown member — the note would be noise.
    expect(dims[1].unknownMember).toBe(false)
  })

  it('reads an edge that points AT the fact, not only one that points away', () => {
    const evidence = starEvidence()
    evidence.edges = [edge('Customer', 'id', 'Order', 'customer_id', 'DECLARED', 'placed')]
    const dims = suggestDimensions(evidence, iri('Order'))

    expect(dims).toHaveLength(1)
    expect(dims[0].classIri).toBe(iri('Customer'))
    expect(dims[0].factColIri).toBe(iri('customer_id'))
    expect(dims[0].dimColIri).toBe(iri('id'))
  })

  it('leaves a trusted edge read from its REFERENCED side unticked — that direction is to-many', () => {
    // Customer holds the FK, so the edge is to-one read Customer → Order and to-many read back.
    // A DECLARED tier says nothing about which end you are standing on (S17-1).
    const evidence = starEvidence()
    evidence.edges = [edge('Customer', 'id', 'Order', 'customer_id', 'DECLARED', 'placed')]
    const dims = suggestDimensions(evidence, iri('Order'))

    expect(dims[0].provablyToOne).toBe(false)
    expect(dims[0].selected).toBe(false)
    expect(dims[0].reason).toContain('referenced side')
  })

  it('skips a self-join, which is a recursive hierarchy this wizard cannot express', () => {
    const evidence = starEvidence()
    evidence.edges = [edge('Order', 'parent_id', 'Order', 'id', 'DECLARED', 'parent')]
    expect(suggestDimensions(evidence, iri('Order'))).toEqual([])
  })

  it('names two joins to one class distinctly instead of colliding', () => {
    const evidence = starEvidence()
    evidence.edges = [
      edge('Order', 'customer_id', 'Customer', 'id', 'DECLARED', 'ship_to'),
      edge('Order', 'product_id', 'Customer', 'id', 'DECLARED', 'bill_to'),
    ]
    const names = suggestDimensions(evidence, iri('Order')).map((d) => d.name)

    expect(new Set(names).size).toBe(2)
    expect(names).toContain('customer')
    expect(names.every(isValidCubeName)).toBe(true)
  })

  it('fails closed on a tier it does not recognise, and says so in words', () => {
    // The server keeps tier an open string, so a seventh tier can appear without a redeploy.
    // An allowlist is what makes that arrive unticked instead of pre-trusted.
    const future = 'PROVEN_BY_ORACLE' as CubeEdgeTier
    expect(isTrustedTier(future)).toBe(false)

    const evidence = starEvidence()
    evidence.edges = [edge('Order', 'customer_id', 'Customer', 'id', future, 'placed_by')]
    const dims = suggestDimensions(evidence, iri('Order'))

    expect(dims).toHaveLength(1)
    expect(dims[0].selected).toBe(false)
    expect(dims[0].reason).toBe('unrecognized trust tier — treated as unverified')
  })

  it('works from whichever class is the fact, and returns nothing from empty evidence', () => {
    // Order is Customer's dimension when Customer is the one being measured — the edge is
    // read from the chosen fact's side, not from a fixed direction.
    expect(suggestDimensions(starEvidence(), iri('Customer')).map((d) => d.className))
      .toEqual(['Order'])
    expect(suggestDimensions({ classes: [], edges: [] }, iri('Order'))).toEqual([])
  })
})

describe('suggestHierarchy', () => {
  const geo = cls('Customer', [
    prop('id', { ndv: 48_000 }),
    prop('country', { ndv: 12 }),
    prop('city', { ndv: 900 }),
    prop('revenue', { numeric: true, ndv: 40_000 }),
  ])

  it('orders levels coarsest first and never includes the join column', () => {
    const levels = suggestHierarchy(geo, iri('id'))

    expect(levels.map((l) => l.name)).toEqual(['country', 'city'])
    expect(levels.map((l) => l.iri)).not.toContain(iri('id'))
  })

  it('never offers a numeric column as a level', () => {
    expect(suggestHierarchy(geo, iri('id')).map((l) => l.name)).not.toContain('revenue')
  })

  it('proposes nothing when adjacent distinct counts are too close to be levels', () => {
    const flat = cls('Person', [prop('first_name', { ndv: 100 }), prop('last_name', { ndv: 120 })])
    expect(suggestHierarchy(flat, iri('id'))).toEqual([])
  })

  it('proposes nothing on an exact NDV tie', () => {
    const tied = cls('Person', [prop('city', { ndv: 100 }), prop('region', { ndv: 100 })])
    expect(suggestHierarchy(tied, iri('id'))).toEqual([])
  })

  it('proposes nothing from fewer than two candidate columns', () => {
    const lonely = cls('Thing', [prop('label', { ndv: 5 }), prop('id', { ndv: 900 })])
    expect(suggestHierarchy(lonely, iri('id'))).toEqual([])
  })

  it('stops the chain at the first column that is not 5x the one before it', () => {
    const c = cls('Geo', [
      prop('country', { ndv: 10 }),
      prop('city', { ndv: 500 }),
      prop('district', { ndv: 700 }),
      prop('street', { ndv: 90_000 }),
    ])
    expect(suggestHierarchy(c, iri('id')).map((l) => l.name)).toEqual(['country', 'city'])
  })

  it('ignores columns with no profile rather than guessing a position for them', () => {
    const c = cls('Geo', [prop('country', { ndv: 10 }), prop('city', { ndv: 500 }), prop('note')])
    expect(suggestHierarchy(c, iri('id')).map((l) => l.name)).toEqual(['country', 'city'])
  })
})

describe('suggestCalendar', () => {
  it('offers the first date-typed column, with the four standard levels', () => {
    const calendar = suggestCalendar(starEvidence().classes[0])

    expect(calendar).toEqual({
      fromColumnIri: iri('order_date'),
      levels: ['year', 'quarter', 'month', 'day'],
    })
  })

  it('recognises dateTime, and the prefixed spelling of either', () => {
    const c = cls('E', [prop('seen_at', { xsd: 'xsd:dateTime' })])
    expect(suggestCalendar(c)?.fromColumnIri).toBe(iri('seen_at'))
  })

  it('offers nothing when the fact has no date column', () => {
    expect(suggestCalendar(cls('E', [prop('n', { numeric: true, xsd: 'xsd:integer' })]))).toBeNull()
    expect(suggestCalendar(cls('E', [prop('n')]))).toBeNull()
  })
})

describe('buildCubeDefinition', () => {
  const measures: MeasureDraft[] = [
    { columnIri: iri('amount'), name: 'amount', aggregator: 'SUM', additivity: 'ADDITIVE', selected: true, excluded: false, reason: null },
    { columnIri: iri('unit_price'), name: 'unit_price', aggregator: 'AVG', additivity: 'NON_ADDITIVE', selected: false, excluded: false, reason: 'ratio-shaped' },
  ]

  it('keeps only what the person ticked, on both halves of the star', () => {
    const dims = suggestDimensions(starEvidence(), iri('Order'))
    const def = buildCubeDefinition(iri('Order'), measures, dims, null)

    expect(def.measures.map((m) => m.name)).toEqual(['amount'])
    expect(def.measures[0]).toMatchObject({ columnIri: iri('amount'), expr: null, aggregator: 'SUM' })
    expect(def.dimensions.map((d) => d.name)).toEqual(['customer'])
    expect(def.calendar).toBeNull()
  })

  it('sets allowUnverified only for a ticked HYPOTHESIS row — ticking the warned row IS the consent', () => {
    const dims = suggestDimensions(starEvidence(), iri('Order'))
      .map((d) => ({ ...d, selected: true }) as DimensionDraft)
    const def = buildCubeDefinition(iri('Order'), measures, dims, null)

    // DECLARED never sends the flag; a ticked HYPOTHESIS row records the gesture as true.
    expect(def.dimensions.map((d) => d.allowUnverified)).toEqual([false, true])
  })

  it('sets allowUnverified for a ticked reversed edge too, whatever its tier says', () => {
    // The server refuses this join for the same fan-out reason it refuses a HYPOTHESIS one, so
    // ticking it has to carry the same consent — otherwise the wizard POSTs a guaranteed 400.
    const evidence = starEvidence()
    evidence.edges = [edge('Customer', 'id', 'Order', 'customer_id', 'DECLARED', 'placed')]
    const dims = suggestDimensions(evidence, iri('Order'))
      .map((d) => ({ ...d, selected: true }) as DimensionDraft)
    const def = buildCubeDefinition(iri('Order'), measures, dims, null)

    expect(def.dimensions.map((d) => d.allowUnverified)).toEqual([true])
  })

  it('copies the hierarchy rather than aliasing the draft the wizard keeps editing', () => {
    const dims = suggestDimensions(starEvidence(), iri('Order'))
    const def = buildCubeDefinition(iri('Order'), measures, dims, null)
    dims[0].hierarchy.push(iri('smuggled'))

    expect(def.dimensions[0].hierarchy).not.toContain(iri('smuggled'))
  })
})
