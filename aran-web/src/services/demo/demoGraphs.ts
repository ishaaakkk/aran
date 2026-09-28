import type { GraphEdge, GraphNode } from '../types'
import {
  DEMO_DATASET,
  GLOBAL_NS,
  IPAPER_IRI,
  INT_RESEARCHERS_IRI,
  sourceClassIri,
  sourceColumnIri,
} from './demoWorld'

interface DemoGraph {
  datasetId: string
  graphUri: string
  tripleCount: number
  empty: boolean
  nodes: GraphNode[]
  edges: GraphEdge[]
}

/** One source graph per bootstrapped dataset — what `GET /graph/source/{id}` returns, and what
 *  the Alignments view's left/right node pickers render for selection. */
function sourceGraph(datasetId: string, datasetName: string, className: string, columns: string[]): DemoGraph {
  const classIri = sourceClassIri(datasetId, datasetName)
  const nodes: GraphNode[] = [
    { id: classIri, iri: classIri, label: className, type: 'class' },
    ...columns.map((c): GraphNode => ({
      id: sourceColumnIri(datasetId, datasetName, c), iri: sourceColumnIri(datasetId, datasetName, c), label: c, type: 'datatype',
    })),
  ]
  const edges: GraphEdge[] = columns.map((c) => ({
    source: classIri, target: sourceColumnIri(datasetId, datasetName, c), label: 'hasProperty',
  }))
  return {
    datasetId, graphUri: `urn:aran:source:${datasetId}`, tripleCount: nodes.length + edges.length,
    empty: false, nodes, edges,
  }
}

export const DEMO_SOURCE_GRAPHS: Record<string, DemoGraph> = {
  [DEMO_DATASET.acm]: sourceGraph(DEMO_DATASET.acm, 'acm_papers', 'Paper', ['paper_id', 'title', 'year', 'venue', 'author_id']),
  [DEMO_DATASET.semantic]: sourceGraph(DEMO_DATASET.semantic, 'semantic_papers', 'Paper', ['doi', 'title', 'year', 'authors', 'citation_count']),
  [DEMO_DATASET.researchers]: sourceGraph(DEMO_DATASET.researchers, 'researchers', 'Researcher', ['researcher_id', 'full_name', 'affiliation', 'country', 'h_index']),
}

/** The promoted integrated schema graph — `GET /graph/schema/{versionId}`, shown after a
 *  compile/promote and behind the Versions view's history inspection. */
export const DEMO_SCHEMA_GRAPH = (() => {
  const paperProps = ['pid', 'title', 'year', 'venue', 'authorId', 'citation_count']
  const researcherProps = ['researcherId', 'name', 'affiliation', 'country', 'hIndex']
  const nodes: GraphNode[] = [
    { id: IPAPER_IRI, iri: IPAPER_IRI, label: 'IPaper', type: 'integrated' },
    { id: INT_RESEARCHERS_IRI, iri: INT_RESEARCHERS_IRI, label: 'Int_researchers', type: 'integrated' },
    ...paperProps.map((p): GraphNode => ({ id: `${GLOBAL_NS}${p}`, iri: `${GLOBAL_NS}${p}`, label: p, type: 'datatype' })),
    ...researcherProps.map((p): GraphNode => ({ id: `${GLOBAL_NS}${p}`, iri: `${GLOBAL_NS}${p}`, label: p, type: 'datatype' })),
  ]
  const edges: GraphEdge[] = [
    ...paperProps.map((p) => ({ source: IPAPER_IRI, target: `${GLOBAL_NS}${p}`, label: 'hasProperty' })),
    ...researcherProps.map((p) => ({ source: INT_RESEARCHERS_IRI, target: `${GLOBAL_NS}${p}`, label: 'hasProperty' })),
    { source: IPAPER_IRI, target: INT_RESEARCHERS_IRI, label: 'hasAuthor' },
  ]
  return {
    versionId: 'v2', graphUri: 'urn:aran:schema', tripleCount: nodes.length + edges.length,
    empty: false, nodes, edges,
  }
})()
