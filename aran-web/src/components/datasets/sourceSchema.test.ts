import { describe, it, expect } from 'vitest'
import { sourceSchemaColumns } from './sourceSchema'
import type { GraphEdge, GraphNode } from '@/services/types'

const NS = 'http://www.essi.upc.edu/DTIM/NextiaDI/source/ds-1/acm'

describe('sourceSchemaColumns — the Overview schema, read from the bootstrapped source graph (J2-F13)', () => {
  it('reads the real shape: property -domain-> class, property -range-> xsd type', () => {
    // SourceGraphSerializer emits a node per class and property and one edge per rdfs:domain /
    // rdfs:range triple, labelled with the predicate's local name.
    const nodes: GraphNode[] = [
      { id: NS, iri: NS, label: 'acm', type: 'class' },
      { id: `${NS}/title`, iri: `${NS}/title`, label: 'title', type: 'datatype' },
      { id: `${NS}/year`, iri: `${NS}/year`, label: 'year', type: 'datatype' },
      { id: 'http://www.w3.org/2001/XMLSchema#string', iri: 'http://www.w3.org/2001/XMLSchema#string', label: 'xsd:string', type: 'xsd' },
      { id: 'http://www.w3.org/2001/XMLSchema#long', iri: 'http://www.w3.org/2001/XMLSchema#long', label: 'xsd:long', type: 'xsd' },
    ]
    const edges: GraphEdge[] = [
      { source: `${NS}/year`, target: NS, label: 'domain' },
      { source: `${NS}/year`, target: 'http://www.w3.org/2001/XMLSchema#long', label: 'range' },
      { source: `${NS}/title`, target: NS, label: 'domain' },
      { source: `${NS}/title`, target: 'http://www.w3.org/2001/XMLSchema#string', label: 'range' },
    ]
    expect(sourceSchemaColumns({ nodes, edges })).toEqual([
      { className: 'acm', property: 'title', datatype: 'xsd:string', iri: `${NS}/title` },
      { className: 'acm', property: 'year', datatype: 'xsd:long', iri: `${NS}/year` },
    ])
  })

  it('names a nested document class as the range of an object property', () => {
    const nodes: GraphNode[] = [
      { id: 'c:Paper', iri: 'c:Paper', label: 'Paper', type: 'class' },
      { id: 'c:Author', iri: 'c:Author', label: 'Author', type: 'class' },
      { id: 'p:authors', iri: 'p:authors', label: 'authors', type: 'object' },
    ]
    const edges: GraphEdge[] = [
      { source: 'p:authors', target: 'c:Paper', label: 'domain' },
      { source: 'p:authors', target: 'c:Author', label: 'range' },
    ]
    expect(sourceSchemaColumns({ nodes, edges })).toEqual([
      { className: 'Paper', property: 'authors', datatype: '→ Author', iri: 'p:authors' },
    ])
  })

  it('tolerates a property with no recorded type, rather than dropping it', () => {
    const nodes: GraphNode[] = [
      { id: 'c:R', iri: 'c:R', label: 'Researcher', type: 'class' },
      { id: 'p:rid', iri: 'p:rid', label: 'rid', type: 'datatype' },
    ]
    // The demo tenant draws its graphs class -hasProperty-> property, with no range.
    const edges: GraphEdge[] = [{ source: 'c:R', target: 'p:rid', label: 'hasProperty' }]
    expect(sourceSchemaColumns({ nodes, edges })).toEqual([
      { className: 'Researcher', property: 'rid', datatype: null, iri: 'p:rid' },
    ])
  })

  it('is empty for an empty graph', () => {
    expect(sourceSchemaColumns({ nodes: [], edges: [] })).toEqual([])
  })
})
