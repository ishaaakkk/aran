/**
 * Types for `schema-graph.js`, the untyped UMD force-graph bundle.
 *
 * Hand-written rather than generated: the module is plain JS with no build step,
 * so this file is the contract, and it declares only the surface the app calls.
 * Nodes and edges are the same shapes the graph API returns, so they are reused
 * from the wire types rather than duplicated here.
 */
import type { GraphNode, GraphEdge } from '@/services/types'

export interface SchemaGraphTheme {
  [token: string]: string | number
}

export interface SchemaGraphOptions {
  /** `sublabel` renders as a second, muted line — the F18 source qualifier. */
  nodes: Array<GraphNode & { sublabel?: string }>
  /** The renderer wants edges as [source, target, label] tuples. */
  edges: Array<[string, string, string]>
  theme?: SchemaGraphTheme
  /** Id of the node to select on mount. */
  selected?: string | null
  onSelect?: (node: GraphNode | null) => void
  linkDistance?: number
  leafLinkDistance?: number
  /** The graph's accessible name, e.g. "Schema of researchers.csv". */
  ariaLabel?: string
}

/** A mounted graph. Held in a ref and torn down on unmount. */
export interface SchemaGraphInstance {
  /** Select a node by id, or pass null to clear the selection. */
  select(id: string | null): void
  /** The selected node's **id**, not the node itself. */
  getSelected(): string | null
  getNodes(): GraphNode[]
  fit(): void
  zoomBy(factor: number): void
  resize(): void
  releaseAllPinned(): void
  destroy(): void
}

/** What `autoFit` needs of a d3 force simulation: its event registry. */
export interface SchemaGraphSimulationEvents {
  on(typenames: string, listener: (() => void) | null): unknown
}

/** The canvas's fit-on-settle policy, returned by `autoFit`. */
export interface SchemaGraphAutoFit {
  /** The person panned or zoomed: stop fitting over their view. */
  userMoved(): void
  dispose(): void
}

export interface SchemaGraphModule {
  create(container: HTMLElement, options: SchemaGraphOptions): SchemaGraphInstance
  autoFit(sim: SchemaGraphSimulationEvents, fit: () => void, settleMs: number): SchemaGraphAutoFit
  /** A node's accessible name: its kind, label and, when it has one, its source (J4-F3). */
  nodeAccessibleName(node: Pick<GraphNode, 'id' | 'type'> & { label?: string; sublabel?: string }): string
  /** Whether a key activates a focused node (Enter or Space). */
  activatesNode(key: string): boolean
  DARK_THEME: SchemaGraphTheme
  LIGHT_THEME: SchemaGraphTheme
}

declare const AranSchemaGraph: SchemaGraphModule
export default AranSchemaGraph

declare global {
  interface Window {
    /** The UMD bundle reads d3 off the window, and registers itself there. */
    d3?: unknown
    AranSchemaGraph?: SchemaGraphModule
  }
}

export type { GraphNode, GraphEdge }
