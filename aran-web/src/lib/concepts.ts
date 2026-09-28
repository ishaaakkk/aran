/**
 * One-paragraph explanations of Aran's load-bearing concepts, surfaced as
 * `InfoTip`s next to the UI that first confronts a user with each term.
 *
 * One module on purpose: the same concept appears on several screens
 * (federation on the repository form and the repository list; virtual vs
 * materialized on the product list and the save dialog), and two hand-written
 * copies of an explanation drift apart with nothing to catch it. Tests assert
 * against these strings, not against per-screen prose.
 *
 * Plain strings, not JSX — the tour registry (`tours/tourRegistry.ts`) set the
 * precedent that user-facing teaching copy stays importable without React.
 */
export const CONCEPTS = {
  bootstrapping:
    'When a dataset is registered, Aran derives a source schema from it — one class per record ' +
    'type, one property per field, plus the SQL that reads it: from its lake snapshot for an ' +
    'upload or import, or live from the source for a table read in place. That derived schema is ' +
    'the raw material you later map into the integrated schema; nothing is written back to your ' +
    'source.',

  profiling:
    'Statistics computed per column over a snapshot — distinct values, uniqueness, null ' +
    'fraction, value domains. Profiles power join discovery (two columns with overlapping ' +
    'value domains suggest a join) and the cost model the query optimizer plans with.',

  repository:
    'Where your data comes from — a database connection, an Iceberg REST catalog, a Singer tap, ' +
    'or a place to hold the files you upload (object storage is connect-only for now: its ' +
    'datasets are uploads too). Aran reads from it and never takes ownership: registering a ' +
    'repository changes nothing at the source.',

  federation:
    'A federated repository is queried live, in place: Aran reads the source database at query ' +
    'time instead of a snapshot in the lake. Answers are as fresh as the source, but query ' +
    'latency and load now depend on it. Importing a table from it also keeps a lake copy, which ' +
    'queries use only once that copy has an interval refresh and is fresh enough. Without ' +
    'federation, queries run on lake snapshots and the source is only touched at refresh time.',

  wrapper:
    'The SQL that turns a raw snapshot into the rows a source class exposes — every source ' +
    'class has one, generated at bootstrap. Edit it to rename, reshape, or filter before ' +
    'integration; a saved edit reaches queries after the next Rebuild and Promote. A verified ' +
    'wrapper ("Optimizable") lets the optimizer plan through the SQL, except while its class has ' +
    'an enforced denial constraint or a DECIMAL or TIMESTAMP column; anything else runs opaque, ' +
    'exactly as written.',

  denialConstraint:
    'A rule stating what must never hold in the data (e.g. a rating above 5). Enforcing one ' +
    'filters violating rows out of the integrated views every query reads — the rows land in a ' +
    'quarantine view instead of silently disappearing. Discovered candidates are display-only ' +
    'suggestions until a person authors the constraint by hand.',

  alignment:
    'A mapping from a source property onto a global property of an integrated class. Two ' +
    'sources mapped onto the same class are unioned; a join property links two different ' +
    'classes. The set of accepted alignments is what compiles into the integrated schema.',

  joinDiscovery:
    'Aran compares column profiles across datasets and proposes joins wherever two value ' +
    'domains overlap, each with a confidence score. An accepted suggestion becomes a join ' +
    'hypothesis between classes — an estimate the optimizer can use, never a uniqueness ' +
    'guarantee.',

  schemaVersion:
    'Compiling the current alignments produces an immutable draft schema version; promoting a ' +
    'draft makes it ACTIVE — the version all queries and new products plan against. Old ' +
    'versions can be compared and restored; only the ACTIVE one is queried. After a promotion, ' +
    'MATERIALIZED products keep serving their snapshot and turn STALE until refreshed; VIRTUAL ' +
    'products follow the active version on their next read.',

  dataProduct:
    'A named, curated query result: an OQL query saved with the schema version it was defined ' +
    'against and served to consumers over JDBC, to MCP agents and — materialized ones only — ' +
    'through the read-only Iceberg catalog. Materialized products snapshot their rows; virtual ' +
    'products recompute on every read.',

  accessMode:
    'Materialized: computed once into a snapshot — reads are fast and cheap, but only as fresh ' +
    'as the last refresh. Virtual: no snapshot at all — every read re-plans the OQL against ' +
    'the active schema and recomputes it from the sources that schema points at (a live source ' +
    'as it is now, an uploaded file as of its last upload or refresh), so enforced constraints ' +
    'apply immediately. The price: the query runs on every read, it is not served over Iceberg, ' +
    'an agent read returns up to 1,000 rows, and a read fails if a schema change breaks the query.',

  oql:
    "Aran's query language over the integrated schema: SELECT properties FROM one class, " +
    'following join properties with -[property]-> hops. You never name a physical table — the ' +
    'engine rewrites the query into SQL over whichever sources back each class.',
} as const;

export type ConceptKey = keyof typeof CONCEPTS;
