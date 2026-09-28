/** Shared shapes between AlignmentsView and its extracted panels. */

export interface SchemaNode {
  id: string;
  label?: string;
  iri: string;
  type: string;
}

export interface SchemaEdge {
  source: string;
  target: string;
  label: string;
}

export interface GraphData {
  datasetId: string;
  nodes: SchemaNode[];
  edges: SchemaEdge[];
}

/** A candidate join from `join-discovery` — defined with the rest of its result in lib/joinDiscovery. */
export type { JoinCandidate } from '@/lib/joinDiscovery';

/** What happened to one candidate an accept sent (J3-F4): added, or refused with the server's reason. */
export interface CandidateOutcome {
  state: 'added' | 'failed';
  message?: string;
  /** A failure a plain retry may fix (network, 5xx); a 4xx is not — see `candidateAccept`. */
  retryable?: boolean;
  /** The label the refused request carried: a deterministic refusal is re-sent only with another. */
  sentLabel?: string;
}

/** The compiled draft schema returned by a `model:compile` job. */
export interface IntegrationResult {
  draftVersionId: string;
  draftVersionNumber: string;
  tripleCount: string;
  unusedAlignments: string;
  turtle: string;
  /** The run that produced this draft. Promotion MUST go through it. */
  integrationRunId: string;
  /** Whether this draft came from a full rebuild or an incremental add, for the panel copy. */
  mode: 'rebuild' | 'incremental';
  /** Restored from the model after a reload (J4-F2): the draft is intact, but the compile job's
   *  own report — triples, Turtle, unused alignments — belonged to the session that ran it. */
  restored?: boolean;
}
