import type { GraphEdge, GraphNode } from '@/services/types';

/** One property of a bootstrapped source class, as the dataset Overview lists it. */
export interface SourceSchemaColumn {
  className: string;
  property: string;
  /** The recorded range: an `xsd:` type, `→ Class` for a nested document class, or null. */
  datatype: string | null;
  iri: string;
}

/**
 * The dataset's schema, read from its bootstrapped source graph (`GET /api/graph/source/{id}`).
 *
 * The Overview used to render this from `Dataset.columns`, a wire field the backend never sent
 * (J2-F13, S14-2), so every dataset read "No schema properties stored". The source graph is what
 * bootstrap actually writes: `SourceGraphSerializer` emits one edge per `rdfs:domain` /
 * `rdfs:range` triple, labelled `domain` / `range`. The demo tenant draws `class -hasProperty->
 * property` instead, which is read the same way.
 */
export function sourceSchemaColumns(graph: { nodes: GraphNode[]; edges: GraphEdge[] }): SourceSchemaColumn[] {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  const label = (id: string) => byId.get(id)?.label ?? id;
  const domainOf = new Map<string, string>();
  const rangeOf = new Map<string, string>();
  for (const e of graph.edges) {
    if (e.label === 'domain') domainOf.set(e.source, e.target);
    else if (e.label === 'hasProperty') domainOf.set(e.target, e.source);
    else if (e.label === 'range') rangeOf.set(e.source, e.target);
  }
  const rows: SourceSchemaColumn[] = [];
  for (const [prop, cls] of domainOf) {
    const range = rangeOf.get(prop);
    const rangeNode = range ? byId.get(range) : undefined;
    rows.push({
      className: label(cls),
      property: label(prop),
      datatype: !range ? null : rangeNode?.type === 'xsd' ? label(range) : `→ ${label(range)}`,
      iri: prop,
    });
  }
  return rows.sort((a, b) => a.className.localeCompare(b.className) || a.property.localeCompare(b.property));
}
