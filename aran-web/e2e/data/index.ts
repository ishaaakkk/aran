import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const DATA_DIR = path.dirname(fileURLToPath(import.meta.url));

export function fixturePath(file: string): string {
  return path.join(DATA_DIR, file);
}

/**
 * Row-level truths the specs assert against. Kept here so a fixture edit and
 * the expectations that depend on it move together.
 *
 * Value domains are engineered for join discovery: author_ref covers the
 * researcher keys exactly, venue_code uses a disjoint alphabet so it can never
 * be mistaken for one, and reviewer_ref is the deliberate near-miss at roughly
 * 20% containment against rid.
 *
 * `acm_papers.csv` has ten rows, not three. P-004..P-010 exist to carry the
 * reviewer_ref near-miss signal — two R- matches out of ten rows is the ~20%
 * containment the discovery scenarios need, and three rows cannot express it.
 * They reuse existing author_ref values, so the researcher-key domain is
 * undisturbed.
 *
 * This table describes the corpus COMPLETELY, and must keep doing so. An
 * earlier draft listed only the three "canonical" papers, which would have
 * quietly broken every later spec that asserts a full result set or a row
 * count: an unfiltered `SELECT Paper.title` returns all ten ACM rows, a join
 * to Researcher returns thirteen pairs, not six. If you add a fixture row,
 * add it here too — corpus.spec.ts cross-derives authorPairs from the
 * fixtures and will fail if the two drift apart.
 */
/**
 * `curated_papers.csv`: the denial-constraint fixture (V34), not part of
 * `EXPECTED` below: it carries no join-discovery signal and no title corpus, so folding it into
 * the seven join sources would only make every count there wrong.
 *
 * Sixty rows, thirty of them `curated = 'yes'`. Two facts are load-bearing, and
 * `v34-constraint-discovery.spec.ts` re-derives BOTH from this file rather than trusting this
 * comment — the spec's whole claim is that a constraint discovered over the wrapper's output
 * could not have come from the raw snapshot:
 *
 *  - **journal_code → impact_band is VIOLATED over the whole file.** The `curated = 'no'` rows
 *    map each journal to a different band than the `yes` rows do (J-01 low/high, J-02 mid/low,
 *    J-03 high/mid), so the FD cannot be discovered from the raw snapshot.
 *  - **journal_code → impact_band HOLDS over `curated = 'yes'` alone** (J-01 high, J-02 low,
 *    J-03 mid). A wrapper whose `WHERE` keeps only those rows restores it.
 *
 * `author_ref` draws from `researchers.csv`'s `rid` domain (R-001..R-003) so the source can be
 * joined the same way every other paper source is, and is decorrelated from `journal_code`
 * (period 7 against period 3) so it determines neither the journal nor the band even on the
 * curated subset — the discovered FD is about the two columns the fixture is built around, not
 * an accident of how the author column happens to cycle. `paper_id` is unique across all sixty.
 */
export const EXPECTED = {
  acmTitles: [
    'Federated Query Processing',
    'Schema Integration Revisited',
    'Wrappers and Denial Constraints',
    'Provenance-Aware Query Rewriting',
    'Approximate Join Cardinality Estimation',
    'Incremental View Maintenance at Scale',
    'Denial Constraints for Data Cleaning Pipelines',
    'Cost Models for Federated Wrappers',
    'Streaming Schema Matching at Scale',
    'Benchmarking Join Discovery Algorithms',
  ],
  semanticTitles: [
    'Ontology-Based Data Integration',
    'Reasoning over Knowledge Graphs',
  ],
  dblpTitles: ['Cost-Based Optimization for Federated Plans'],

  get paperTitles(): string[] {
    return [...this.acmTitles, ...this.semanticTitles, ...this.dblpTitles];
  },

  /**
   * (paper title, researcher name) across every paper and researcher source —
   * all thirteen, so a join's row count can be asserted against `.length`.
   * Several researchers author more than one paper; that many-to-one shape is
   * deliberate and makes the join result realistic.
   */
  authorPairs: [
    ['Federated Query Processing', 'Maria Garcia'],
    ['Schema Integration Revisited', 'James Chen'],
    ['Wrappers and Denial Constraints', 'Ana Silva'],
    ['Provenance-Aware Query Rewriting', 'Maria Garcia'],
    ['Approximate Join Cardinality Estimation', 'James Chen'],
    ['Incremental View Maintenance at Scale', 'Ana Silva'],
    ['Denial Constraints for Data Cleaning Pipelines', 'Maria Garcia'],
    ['Cost Models for Federated Wrappers', 'James Chen'],
    ['Streaming Schema Matching at Scale', 'Ana Silva'],
    ['Benchmarking Join Discovery Algorithms', 'Maria Garcia'],
    ['Ontology-Based Data Integration', 'Elena Rossi'],
    ['Reasoning over Knowledge Graphs', 'Tomas Novak'],
    ['Cost-Based Optimization for Federated Plans', 'Priya Nair'],
  ] as Array<[string, string]>,

  /** (researcher name, institution) — the third hop. */
  affiliationPairs: [
    ['Maria Garcia', 'Universitat Politecnica de Catalunya'],
    ['James Chen', 'Massachusetts Institute of Technology'],
    ['Ana Silva', 'Universitat Politecnica de Catalunya'],
    ['Elena Rossi', 'Massachusetts Institute of Technology'],
    ['Tomas Novak', 'Universitat Politecnica de Catalunya'],
    ['Priya Nair', 'Massachusetts Institute of Technology'],
  ] as Array<[string, string]>,
} as const;
