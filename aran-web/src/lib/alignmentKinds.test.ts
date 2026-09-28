import { describe, it, expect } from 'vitest'
import { alignmentKindsFor, mappingsNamedIn } from './alignmentKinds'

describe('alignmentKindsFor — which alignments a pair of nodes admits (J4-F6)', () => {
  it('a class aligns only with a class, as a class', () => {
    expect(alignmentKindsFor('class', 'class')).toEqual({ kinds: ['class'], reason: null })
  })

  it('a property aligns with a property, as datatype, object or join', () => {
    expect(alignmentKindsFor('datatype', 'datatype').kinds).toEqual(['datatype', 'object', 'join'])
    expect(alignmentKindsFor('object', 'datatype').kinds).toEqual(['datatype', 'object', 'join'])
  })

  it('refuses a class with a property, and says why — every rebuild failed on such a row', () => {
    const r = alignmentKindsFor('class', 'datatype')
    expect(r.kinds).toEqual([])
    expect(r.reason).toMatch(/class aligns only with a class/i)
  })

  it('refuses an xsd datatype node as either end', () => {
    const r = alignmentKindsFor('xsd', 'datatype')
    expect(r.kinds).toEqual([])
    expect(r.reason).toMatch(/xsd/)
  })

  it('reads an integrated class as a class', () => {
    expect(alignmentKindsFor('integrated', 'class').kinds).toEqual(['class'])
  })

  it('offers nothing and says nothing until both ends are picked', () => {
    expect(alignmentKindsFor(undefined, 'class')).toEqual({ kinds: [], reason: null })
  })
})

describe('mappingsNamedIn — the rows a compile failure is about (J4-F6)', () => {
  const m = (id: string, sourceIri: string, targetIri: string) => ({ id, sourceIri, targetIri })

  it('finds every accepted row whose end the INVALID_ALIGNMENT message names by IRI', () => {
    const msg = 'Alignment doi -> doi (DATATYPE) is invalid: http://x/source/ds1/acm is not an rdf:Property'
    const rows = [m('a', 'http://x/source/ds1/acm', 'http://x/source/ds2/doi'), m('b', 'http://x/source/ds1/acm/title', 'http://x/y')]
    expect([...mappingsNamedIn(msg, rows)]).toEqual(['a'])
  })

  it('does not match an IRI that is only a prefix of a named one', () => {
    const msg = 'http://x/source/ds1/acm/doi is not an rdfs:Class'
    expect([...mappingsNamedIn(msg, [m('a', 'http://x/source/ds1/acm', 'http://z')])]).toEqual([])
  })

  it('is empty without a failure', () => {
    expect(mappingsNamedIn(null, [m('a', 'x', 'y')]).size).toBe(0)
  })
})
