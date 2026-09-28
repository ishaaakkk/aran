import type {
  Dataset,
  DatasetProfiles,
  StoredColumnProfile,
  DataProduct,
  DataRepository,
  IntegrationModelBundle,
  ModelConstraint,
  ModelMapping,
  ModelSource,
  Project,
  SchemaDiff,
  SchemaVersion,
} from '../types'

/**
 * The demo tenant's fixed scenario data: three sources (ACM Digital Library,
 * Semantic Scholar, Researcher Profiles) integrated into two classes (IPaper,
 * Int_researchers) joined on authorship — the exact walkthrough in `demo/seminar/DEMO.md`.
 *
 * Every id, IRI and row here is cross-referenced by `demoInterceptor.ts` across
 * many endpoints (a dataset's columns must match its profile, a mapping's
 * `sourceIri` must match a node in that dataset's source graph, and so on) —
 * change something here and grep for its id before assuming it is unused.
 */

export const DEMO_TENANT_ID = '00000000-0000-0000-0000-000000000002'
export const DEMO_PROJECT_ID = 'proj-demo-1'

export const DEMO_REPO = {
  acm: 'repo-acm',
  semantic: 'repo-semantic',
  researchers: 'repo-researchers',
} as const

export const DEMO_DATASET = {
  acm: 'ds-acm',
  semantic: 'ds-semantic',
  researchers: 'ds-researchers',
} as const

/** The real backend's own source-IRI scheme (`JoinDiscoveryOperator.columnIri`) — reused here so a
 *  demo mapping looks exactly like a real one, not an invented shape. */
const SOURCE_NS = 'http://www.essi.upc.edu/DTIM/NextiaDI/source'
export function sourceColumnIri(datasetId: string, datasetName: string, columnName: string): string {
  return `${SOURCE_NS}/${datasetId}/${datasetName}/${columnName}`
}
export function sourceClassIri(datasetId: string, datasetName: string): string {
  return `${SOURCE_NS}/${datasetId}/${datasetName}`
}

/** The mock's integrated-schema namespace — kept separate from the real NextiaDI namespace so a
 *  glance at an IRI tells you which side of the mediator it belongs to. */
export const GLOBAL_NS = 'http://aran.com/schema#'
export const IPAPER_IRI = `${GLOBAL_NS}IPaper`
export const INT_RESEARCHERS_IRI = `${GLOBAL_NS}Int_researchers`
export const HAS_AUTHOR_IRI = `${GLOBAL_NS}hasAuthor`

export const DEMO_PROJECT: Project = {
  id: DEMO_PROJECT_ID,
  tenantId: DEMO_TENANT_ID,
  name: 'Research Publications',
  description: 'Integrating ACM Digital Library, Semantic Scholar, and Researcher Profiles into one queryable schema.',
  color: '#3DCEA0',
  privacy: 'PUBLIC',
  status: 'ACTIVE',
  progress: 85,
  createdBy: 'demo',
  createdAt: '2026-07-02T09:14:00Z',
} as unknown as Project

export const DEMO_REPOSITORIES: DataRepository[] = [
  {
    id: DEMO_REPO.acm, projectId: DEMO_PROJECT_ID, tenantId: DEMO_TENANT_ID,
    name: 'ACM Digital Library', description: 'Conference paper metadata, exported quarterly as CSV.',
    type: 'FILE_SYSTEM', status: 'OK',
  },
  {
    id: DEMO_REPO.semantic, projectId: DEMO_PROJECT_ID, tenantId: DEMO_TENANT_ID,
    name: 'Semantic Scholar', description: 'Citation-graph extract with per-paper keyword arrays, as JSON.',
    type: 'FILE_SYSTEM', status: 'OK',
  },
  {
    id: DEMO_REPO.researchers, projectId: DEMO_PROJECT_ID, tenantId: DEMO_TENANT_ID,
    name: 'Researcher Profiles', description: "The university registry's internal researcher directory.",
    type: 'FILE_SYSTEM', status: 'OK',
  },
] as unknown as DataRepository[]

// `wrapperSql` is demo-local: it supplies DEMO_MODEL_SOURCES below. It used to ride on
// Dataset.wrapper, which was deleted in V27 because nothing in the product read it.
export const DEMO_DATASETS: Record<string, Dataset & { repositoryId: string; wrapperSql?: string }> = {
  [DEMO_DATASET.acm]: {
    id: DEMO_DATASET.acm,
    repositoryId: DEMO_REPO.acm,
    name: 'acm_papers.csv',
    description: '20 ACM-indexed papers, one row per (paper, author) pair — 40 rows.',
    snapshotId: 'snap-acm-1',
    bootstrapStatus: 'READY',
    accessMode: 'SNAPSHOT',
    wrapperSql: 'SELECT paper_id, title, year, venue, author_id FROM read_csv_auto(\'${SNAPSHOT}\')',
  } as Dataset & { repositoryId: string },
  [DEMO_DATASET.semantic]: {
    id: DEMO_DATASET.semantic,
    repositoryId: DEMO_REPO.semantic,
    name: 'semantic_papers.json',
    description: '15 papers with citation counts and an `authors` array requiring UNNEST to flatten.',
    snapshotId: 'snap-semantic-1',
    bootstrapStatus: 'READY',
    accessMode: 'SNAPSHOT',
    wrapperSql: "SELECT doi, title, year, unnest(authors) AS author, citation_count FROM read_json_auto('${SNAPSHOT}')",
  } as Dataset & { repositoryId: string },
  [DEMO_DATASET.researchers]: {
    id: DEMO_DATASET.researchers,
    repositoryId: DEMO_REPO.researchers,
    name: 'researchers.json',
    description: '20 researcher profiles with affiliation, country and h-index.',
    snapshotId: 'snap-researchers-1',
    bootstrapStatus: 'READY',
    accessMode: 'SNAPSHOT',
    wrapperSql: "SELECT researcher_id, full_name, affiliation, country, h_index FROM read_json_auto('${SNAPSHOT}')",
  } as Dataset & { repositoryId: string },
}

/**
 * The stored SNAPSHOT profile of each demo dataset — what `GET …/datasets/{id}/profiles` answers
 * (J3-F21). Computed from the seminar files themselves (`demo/seminar/*`) with freyja's definitions
 * (`freyja/profiling/metrics.py`: values cleaned to lower-case trimmed text, Shannon entropy in
 * bits, frequencies over distinct values), so a demo visitor sees numbers the product would
 * compute rather than invented ones. Soundex signatures are left empty.
 */
export const DEMO_PROFILES: Record<string, DatasetProfiles> = {
  [DEMO_DATASET.acm]: {
    datasetId: DEMO_DATASET.acm,
    groups: [{ subject: 'SNAPSHOT', classIri: null, keyEvidence: true, columns: [
    {"columnName": "paper_id", "columnIndex": 0, "sourceType": "VARCHAR", "cardinality": 20, "entropy": 4.3219, "uniqueness": 0.5, "incompleteness": 0.0, "frequencyAvg": 2.0, "frequencyMin": 2, "frequencyMax": 2, "frequencySd": 0.0, "frequencyIqr": 0.0, "valPctMin": 0.05, "valPctMax": 0.05, "valPctStd": 0.0, "constancy": 0.05, "lenMaxWord": 6, "lenMinWord": 6, "lenAvgWord": 6.0, "numberWords": 40, "wordsCntMax": 1, "wordsCntMin": 1, "wordsCntAvg": 1.0, "wordsCntSd": 0.0, "firstWord": "acm001", "lastWord": "acm020", "emptyColumn": false, "binaryColumn": false, "commonValues": ["acm001", "acm002", "acm003", "acm004", "acm005", "acm006", "acm007", "acm008", "acm009", "acm010"], "commonValuesSoundex": []},
    {"columnName": "title", "columnIndex": 1, "sourceType": "VARCHAR", "cardinality": 20, "entropy": 4.3219, "uniqueness": 0.5, "incompleteness": 0.0, "frequencyAvg": 2.0, "frequencyMin": 2, "frequencyMax": 2, "frequencySd": 0.0, "frequencyIqr": 0.0, "valPctMin": 0.05, "valPctMax": 0.05, "valPctStd": 0.0, "constancy": 0.05, "lenMaxWord": 17, "lenMinWord": 1, "lenAvgWord": 7.0524, "numberWords": 248, "wordsCntMax": 7, "wordsCntMin": 5, "wordsCntAvg": 6.2, "wordsCntSd": 0.6, "firstWord": "approximate entity matching with probabilistic models", "lastWord": "towards explainable knowledge graph embeddings", "emptyColumn": false, "binaryColumn": false, "commonValues": ["approximate entity matching with probabilistic models", "automatic wrapper generation for semi-structured sources", "constraint-driven data quality assessment in data lakes", "declarative data integration with denial constraints", "deep learning for multi-source entity resolution", "efficient sparql query optimisation via cost models", "federated query processing over heterogeneous rdf graphs", "global-as-view mediation for semantic data sources", "knowledge base construction from web tables", "knowledge graph completion with path-based reasoning"], "commonValuesSoundex": []},
    {"columnName": "year", "columnIndex": 2, "sourceType": "BIGINT", "cardinality": 5, "entropy": 2.261, "uniqueness": 0.125, "incompleteness": 0.0, "frequencyAvg": 8.0, "frequencyMin": 4, "frequencyMax": 10, "frequencySd": 2.1909, "frequencyIqr": 0.05, "valPctMin": 0.1, "valPctMax": 0.25, "valPctStd": 0.0548, "constancy": 0.25, "lenMaxWord": 4, "lenMinWord": 4, "lenAvgWord": 4.0, "numberWords": 40, "wordsCntMax": 1, "wordsCntMin": 1, "wordsCntAvg": 1.0, "wordsCntSd": 0.0, "firstWord": "2020", "lastWord": "2024", "emptyColumn": false, "binaryColumn": false, "commonValues": ["2022", "2023", "2021", "2024", "2020"], "commonValuesSoundex": []},
    {"columnName": "venue", "columnIndex": 3, "sourceType": "VARCHAR", "cardinality": 7, "entropy": 2.6842, "uniqueness": 0.175, "incompleteness": 0.0, "frequencyAvg": 5.7143, "frequencyMin": 2, "frequencyMax": 8, "frequencySd": 2.2497, "frequencyIqr": 0.1, "valPctMin": 0.05, "valPctMax": 0.2, "valPctStd": 0.0562, "constancy": 0.2, "lenMaxWord": 6, "lenMinWord": 3, "lenAvgWord": 4.15, "numberWords": 40, "wordsCntMax": 1, "wordsCntMin": 1, "wordsCntAvg": 1.0, "wordsCntSd": 0.0, "firstWord": "eswc", "lastWord": "www", "emptyColumn": false, "binaryColumn": false, "commonValues": ["iswc", "sigmod", "vldb", "www", "kdd", "pods", "eswc"], "commonValuesSoundex": []},
    {"columnName": "author_id", "columnIndex": 4, "sourceType": "VARCHAR", "cardinality": 20, "entropy": 4.1464, "uniqueness": 0.5, "incompleteness": 0.0, "frequencyAvg": 2.0, "frequencyMin": 1, "frequencyMax": 4, "frequencySd": 1.0, "frequencyIqr": 0.05, "valPctMin": 0.025, "valPctMax": 0.1, "valPctStd": 0.025, "constancy": 0.1, "lenMaxWord": 4, "lenMinWord": 4, "lenAvgWord": 4.0, "numberWords": 40, "wordsCntMax": 1, "wordsCntMin": 1, "wordsCntAvg": 1.0, "wordsCntSd": 0.0, "firstWord": "r001", "lastWord": "r020", "emptyColumn": false, "binaryColumn": false, "commonValues": ["r001", "r005", "r002", "r003", "r008", "r009", "r004", "r006", "r007", "r013"], "commonValuesSoundex": []},
    ] as StoredColumnProfile[] }],
  },
  [DEMO_DATASET.semantic]: {
    datasetId: DEMO_DATASET.semantic,
    groups: [{ subject: 'SNAPSHOT', classIri: null, keyEvidence: true, columns: [
    {"columnName": "doi", "columnIndex": 0, "sourceType": "VARCHAR", "cardinality": 15, "entropy": 3.9069, "uniqueness": 1.0, "incompleteness": 0.0, "frequencyAvg": 1.0, "frequencyMin": 1, "frequencyMax": 1, "frequencySd": 0.0, "frequencyIqr": 0.0, "valPctMin": 0.0667, "valPctMax": 0.0667, "valPctStd": 0.0, "constancy": 0.0667, "lenMaxWord": 13, "lenMinWord": 13, "lenAvgWord": 13.0, "numberWords": 15, "wordsCntMax": 1, "wordsCntMin": 1, "wordsCntAvg": 1.0, "wordsCntSd": 0.0, "firstWord": "10.1145/ss001", "lastWord": "10.1145/ss015", "emptyColumn": false, "binaryColumn": false, "commonValues": ["10.1145/ss001", "10.1145/ss002", "10.1145/ss003", "10.1145/ss004", "10.1145/ss005", "10.1145/ss006", "10.1145/ss007", "10.1145/ss008", "10.1145/ss009", "10.1145/ss010"], "commonValuesSoundex": []},
    {"columnName": "title", "columnIndex": 1, "sourceType": "VARCHAR", "cardinality": 15, "entropy": 3.9069, "uniqueness": 1.0, "incompleteness": 0.0, "frequencyAvg": 1.0, "frequencyMin": 1, "frequencyMax": 1, "frequencySd": 0.0, "frequencyIqr": 0.0, "valPctMin": 0.0667, "valPctMax": 0.0667, "valPctStd": 0.0, "constancy": 0.0667, "lenMaxWord": 15, "lenMinWord": 1, "lenAvgWord": 7.3818, "numberWords": 92, "wordsCntMax": 8, "wordsCntMin": 5, "wordsCntAvg": 6.1333, "wordsCntSd": 0.8055, "firstWord": "approximate query answering over integrated sources", "lastWord": "scalable sparql querying with adaptive join ordering", "emptyColumn": false, "binaryColumn": false, "commonValues": ["approximate query answering over integrated sources", "benchmark for heterogeneous graph integration systems", "conflict resolution in heterogeneous knowledge bases", "cross-lingual ontology alignment using multilingual embeddings", "data profiling for automated integration", "denial constraint discovery in relational data", "efficient provenance tracking for etl pipelines", "entity alignment across knowledge graphs via structural embeddings", "gav rewriting for incomplete databases", "incremental view maintenance for rdf graphs"], "commonValuesSoundex": []},
    {"columnName": "year", "columnIndex": 2, "sourceType": "BIGINT", "cardinality": 5, "entropy": 2.2566, "uniqueness": 0.3333, "incompleteness": 0.0, "frequencyAvg": 3.0, "frequencyMin": 2, "frequencyMax": 4, "frequencySd": 0.8944, "frequencyIqr": 0.1333, "valPctMin": 0.1333, "valPctMax": 0.2667, "valPctStd": 0.0596, "constancy": 0.2667, "lenMaxWord": 4, "lenMinWord": 4, "lenAvgWord": 4.0, "numberWords": 15, "wordsCntMax": 1, "wordsCntMin": 1, "wordsCntAvg": 1.0, "wordsCntSd": 0.0, "firstWord": "2020", "lastWord": "2024", "emptyColumn": false, "binaryColumn": false, "commonValues": ["2022", "2023", "2021", "2020", "2024"], "commonValuesSoundex": []},
    {"columnName": "authors", "columnIndex": 3, "sourceType": "VARCHAR[]", "cardinality": 15, "entropy": 3.9069, "uniqueness": 1.0, "incompleteness": 0.0, "frequencyAvg": 1.0, "frequencyMin": 1, "frequencyMax": 1, "frequencySd": 0.0, "frequencyIqr": 0.0, "valPctMin": 0.0667, "valPctMax": 0.0667, "valPctStd": 0.0, "constancy": 0.0667, "lenMaxWord": 6, "lenMinWord": 5, "lenAvgWord": 5.3556, "numberWords": 44, "wordsCntMax": 4, "wordsCntMin": 2, "wordsCntAvg": 2.9333, "wordsCntSd": 0.5735, "firstWord": "[r001, r006, r015]", "lastWord": "[r020, r009, r015]", "emptyColumn": false, "binaryColumn": false, "commonValues": ["[r001, r006, r015]", "[r001, r012, r018]", "[r001, r014, r007]", "[r002, r006, r014, r017]", "[r002, r008, r012]", "[r003, r011, r013, r018]", "[r003, r020]", "[r004, r007]", "[r005, r009, r013]", "[r008, r010]"], "commonValuesSoundex": []},
    {"columnName": "citation_count", "columnIndex": 4, "sourceType": "BIGINT", "cardinality": 15, "entropy": 3.9069, "uniqueness": 1.0, "incompleteness": 0.0, "frequencyAvg": 1.0, "frequencyMin": 1, "frequencyMax": 1, "frequencySd": 0.0, "frequencyIqr": 0.0, "valPctMin": 0.0667, "valPctMax": 0.0667, "valPctStd": 0.0, "constancy": 0.0667, "lenMaxWord": 3, "lenMinWord": 2, "lenAvgWord": 2.1333, "numberWords": 15, "wordsCntMax": 1, "wordsCntMin": 1, "wordsCntAvg": 1.0, "wordsCntSd": 0.0, "firstWord": "11", "lastWord": "89", "emptyColumn": false, "binaryColumn": false, "commonValues": ["11", "114", "143", "19", "29", "34", "38", "42", "45", "52"], "commonValuesSoundex": []},
    ] as StoredColumnProfile[] }],
  },
  [DEMO_DATASET.researchers]: {
    datasetId: DEMO_DATASET.researchers,
    groups: [{ subject: 'SNAPSHOT', classIri: null, keyEvidence: true, columns: [
    {"columnName": "researcher_id", "columnIndex": 0, "sourceType": "VARCHAR", "cardinality": 20, "entropy": 4.3219, "uniqueness": 1.0, "incompleteness": 0.0, "frequencyAvg": 1.0, "frequencyMin": 1, "frequencyMax": 1, "frequencySd": 0.0, "frequencyIqr": 0.0, "valPctMin": 0.05, "valPctMax": 0.05, "valPctStd": 0.0, "constancy": 0.05, "lenMaxWord": 4, "lenMinWord": 4, "lenAvgWord": 4.0, "numberWords": 20, "wordsCntMax": 1, "wordsCntMin": 1, "wordsCntAvg": 1.0, "wordsCntSd": 0.0, "firstWord": "r001", "lastWord": "r020", "emptyColumn": false, "binaryColumn": false, "commonValues": ["r001", "r002", "r003", "r004", "r005", "r006", "r007", "r008", "r009", "r010"], "commonValuesSoundex": []},
    {"columnName": "full_name", "columnIndex": 1, "sourceType": "VARCHAR", "cardinality": 20, "entropy": 4.3219, "uniqueness": 1.0, "incompleteness": 0.0, "frequencyAvg": 1.0, "frequencyMin": 1, "frequencyMax": 1, "frequencySd": 0.0, "frequencyIqr": 0.0, "valPctMin": 0.05, "valPctMax": 0.05, "valPctStd": 0.0, "constancy": 0.05, "lenMaxWord": 9, "lenMinWord": 3, "lenAvgWord": 5.325, "numberWords": 40, "wordsCntMax": 2, "wordsCntMin": 2, "wordsCntAvg": 2.0, "wordsCntSd": 0.0, "firstWord": "alex turner", "lastWord": "yuki nakamura", "emptyColumn": false, "binaryColumn": false, "commonValues": ["alex turner", "ana silva", "carlos ruiz", "claire dupont", "david kim", "emma wilson", "fatima al-hassan", "hiroshi tanaka", "isabela fonseca", "james chen"], "commonValuesSoundex": []},
    {"columnName": "affiliation", "columnIndex": 2, "sourceType": "VARCHAR", "cardinality": 14, "entropy": 3.6842, "uniqueness": 0.7, "incompleteness": 0.0, "frequencyAvg": 1.4286, "frequencyMin": 1, "frequencyMax": 3, "frequencySd": 0.6227, "frequencyIqr": 0.05, "valPctMin": 0.05, "valPctMax": 0.15, "valPctStd": 0.0311, "constancy": 0.15, "lenMaxWord": 11, "lenMinWord": 2, "lenAvgWord": 4.7, "numberWords": 29, "wordsCntMax": 3, "wordsCntMin": 1, "wordsCntAvg": 1.45, "wordsCntSd": 0.669, "firstWord": "cmu", "lastWord": "upc", "emptyColumn": false, "binaryColumn": false, "commonValues": ["inesc", "eth zurich", "iit bombay", "oxford", "upc", "cmu", "inria", "kaust", "kth", "mit"], "commonValuesSoundex": []},
    {"columnName": "country", "columnIndex": 3, "sourceType": "VARCHAR", "cardinality": 12, "entropy": 3.4842, "uniqueness": 0.6, "incompleteness": 0.0, "frequencyAvg": 1.6667, "frequencyMin": 1, "frequencyMax": 3, "frequencySd": 0.6236, "frequencyIqr": 0.05, "valPctMin": 0.05, "valPctMax": 0.15, "valPctStd": 0.0312, "constancy": 0.15, "lenMaxWord": 11, "lenMinWord": 2, "lenAvgWord": 5.675, "numberWords": 21, "wordsCntMax": 2, "wordsCntMin": 1, "wordsCntAvg": 1.05, "wordsCntSd": 0.2179, "firstWord": "china", "lastWord": "usa", "emptyColumn": false, "binaryColumn": false, "commonValues": ["portugal", "india", "japan", "spain", "switzerland", "uk", "usa", "china", "france", "italy"], "commonValuesSoundex": []},
    {"columnName": "h_index", "columnIndex": 4, "sourceType": "BIGINT", "cardinality": 19, "entropy": 4.2219, "uniqueness": 0.95, "incompleteness": 0.0, "frequencyAvg": 1.0526, "frequencyMin": 1, "frequencyMax": 2, "frequencySd": 0.2233, "frequencyIqr": 0.0, "valPctMin": 0.05, "valPctMax": 0.1, "valPctStd": 0.0112, "constancy": 0.1, "lenMaxWord": 2, "lenMinWord": 1, "lenAvgWord": 1.65, "numberWords": 20, "wordsCntMax": 1, "wordsCntMin": 1, "wordsCntAvg": 1.0, "wordsCntSd": 0.0, "firstWord": "10", "lastWord": "9", "emptyColumn": false, "binaryColumn": false, "commonValues": ["11", "10", "12", "13", "14", "15", "16", "17", "18", "19"], "commonValuesSoundex": []},
    ] as StoredColumnProfile[] }],
  },
}

/** Row samples behind `previewDatasetById` / `previewWrapper` — small, but real-shaped. */
export const DEMO_PREVIEWS: Record<string, { headers: string[]; rows: unknown[][] }> = {
  [DEMO_DATASET.acm]: {
    headers: ['paper_id', 'title', 'year', 'venue', 'author_id'],
    rows: [
      ['ACM001', 'Federated Query Processing over Heterogeneous RDF Graphs', 2022, 'ISWC', 'R001'],
      ['ACM001', 'Federated Query Processing over Heterogeneous RDF Graphs', 2022, 'ISWC', 'R006'],
      ['ACM002', 'Open Data Integration at Scale', 2024, 'VLDB', 'R002'],
      ['ACM003', 'Efficient SPARQL Query Optimisation via Cost Models', 2021, 'SIGMOD', 'R001'],
    ],
  },
  [DEMO_DATASET.semantic]: {
    headers: ['doi', 'title', 'year', 'authors', 'citation_count'],
    rows: [
      ['10.1145/SS001', 'Ontology-Based Data Integration: A Decade of Progress', 2020, '["R002","R008","R012"]', 143],
      ['10.1145/SS002', 'Reasoning over Federated Knowledge Graphs', 2021, '["R001","R006","R015"]', 89],
      ['10.1145/SS003', 'Neural Schema Matching with Pre-Trained Language Models', 2022, '["R003","R020"]', 67],
    ],
  },
  [DEMO_DATASET.researchers]: {
    headers: ['researcher_id', 'full_name', 'affiliation', 'country', 'h_index'],
    rows: [
      ['R001', 'Maria Garcia', 'UPC', 'Spain', 18],
      ['R002', 'James Chen', 'MIT', 'USA', 24],
      ['R003', 'Ana Silva', 'INESC', 'Portugal', 12],
      ['R004', 'Luca Rossi', 'Politecnico di Milano', 'Italy', 9],
    ],
  },
}

// ── Integration model (Alignments view) ───────────────────────────────────────

const acmAuthorId = sourceColumnIri(DEMO_DATASET.acm, 'acm_papers', 'author_id')
const semanticAuthors = sourceColumnIri(DEMO_DATASET.semantic, 'semantic_papers', 'authors')
const researcherId = sourceColumnIri(DEMO_DATASET.researchers, 'researchers', 'researcher_id')
const acmVenue = sourceColumnIri(DEMO_DATASET.acm, 'acm_papers', 'venue')
const researcherAffiliation = sourceColumnIri(DEMO_DATASET.researchers, 'researchers', 'affiliation')

export const DEMO_MODEL_SOURCES: ModelSource[] = [
  { id: 'msrc-acm', datasetId: DEMO_DATASET.acm, classIri: IPAPER_IRI, wrapperSql: DEMO_DATASETS[DEMO_DATASET.acm].wrapperSql ?? null },
  { id: 'msrc-semantic', datasetId: DEMO_DATASET.semantic, classIri: IPAPER_IRI, wrapperSql: DEMO_DATASETS[DEMO_DATASET.semantic].wrapperSql ?? null },
  { id: 'msrc-researchers', datasetId: DEMO_DATASET.researchers, classIri: INT_RESEARCHERS_IRI, wrapperSql: DEMO_DATASETS[DEMO_DATASET.researchers].wrapperSql ?? null },
]

export const DEMO_MODEL_MAPPINGS: ModelMapping[] = [
  {
    id: 'map-class-paper', kind: 'class',
    sourceIri: sourceClassIri(DEMO_DATASET.acm, 'acm_papers'),
    targetIri: sourceClassIri(DEMO_DATASET.semantic, 'semantic_papers'),
    label: 'IPaper', status: 'ACCEPTED', author: 'demo', comment: null,
    evidence: { score: 0.88, linguisticScore: 0.91, valueScore: 0.85 },
    createdAt: '2026-07-02T09:20:00Z',
  },
  {
    id: 'map-title', kind: 'datatype',
    sourceIri: sourceColumnIri(DEMO_DATASET.acm, 'acm_papers', 'title'),
    targetIri: sourceColumnIri(DEMO_DATASET.semantic, 'semantic_papers', 'title'),
    label: 'title', status: 'ACCEPTED', author: 'demo', comment: null,
    evidence: { score: 0.97, linguisticScore: 1.0, valueScore: 0.94, columnA: 'title', columnB: 'title' },
    createdAt: '2026-07-02T09:21:00Z',
  },
  {
    id: 'map-year', kind: 'datatype',
    sourceIri: sourceColumnIri(DEMO_DATASET.acm, 'acm_papers', 'year'),
    targetIri: sourceColumnIri(DEMO_DATASET.semantic, 'semantic_papers', 'year'),
    label: 'year', status: 'ACCEPTED', author: 'demo', comment: null,
    evidence: { score: 0.95, linguisticScore: 1.0, valueScore: 0.9, columnA: 'year', columnB: 'year' },
    createdAt: '2026-07-02T09:21:30Z',
  },
  {
    id: 'map-pid', kind: 'datatype',
    sourceIri: sourceColumnIri(DEMO_DATASET.acm, 'acm_papers', 'paper_id'),
    targetIri: sourceColumnIri(DEMO_DATASET.semantic, 'semantic_papers', 'doi'),
    label: 'pid', status: 'ACCEPTED', author: 'demo', comment: 'Both are the paper’s natural key, different formats.',
    evidence: { score: 0.71, linguisticScore: 0.42, valueScore: 0.99, columnA: 'paper_id', columnB: 'doi' },
    createdAt: '2026-07-02T09:22:00Z',
  },
  {
    id: 'map-join-author', kind: 'join',
    sourceIri: acmAuthorId,
    targetIri: researcherId,
    label: 'hasAuthor', status: 'ACCEPTED', author: 'demo',
    comment: 'Discovered automatically (exact score 0.49) — see the join-discovery tour step.',
    evidence: { score: 0.49, linguisticScore: 0.8, valueScore: 0.98, columnA: 'author_id', columnB: 'researcher_id' },
    createdAt: '2026-07-02T09:25:00Z',
  },
  {
    id: 'map-join-author-semantic', kind: 'join',
    sourceIri: semanticAuthors,
    targetIri: researcherId,
    label: 'hasAuthor', status: 'ACCEPTED', author: 'demo', comment: null,
    evidence: { score: 0.49, linguisticScore: 0.75, valueScore: 0.95, columnA: 'authors', columnB: 'researcher_id' },
    createdAt: '2026-07-02T09:25:30Z',
  },
  // A still-pending suggestion — genuinely low-confidence, and left unresolved on
  // purpose so the accept/reject review queue has something real to demonstrate.
  {
    id: 'map-suggest-venue-affiliation', kind: 'datatype',
    sourceIri: acmVenue,
    targetIri: researcherAffiliation,
    label: 'venue↔affiliation?', status: 'SUGGESTED', author: null,
    comment: 'Both are short organisation-like strings — low confidence, worth a human look.',
    evidence: { score: 0.41, linguisticScore: 0.35, valueScore: 0.47, columnA: 'venue', columnB: 'affiliation' },
    createdAt: '2026-07-05T11:00:00Z',
  },
]

export const DEMO_MODEL_CONSTRAINTS: ModelConstraint[] = [
  { id: 'con-year', classIri: IPAPER_IRI, expression: 'year <= 2026', enforced: true, createdAt: '2026-07-02T09:30:00Z' },
  { id: 'con-citations', classIri: IPAPER_IRI, expression: 'citation_count >= 0', enforced: true, createdAt: '2026-07-02T09:30:30Z' },
  { id: 'con-hindex', classIri: INT_RESEARCHERS_IRI, expression: 'h_index >= 0', enforced: true, createdAt: '2026-07-02T09:31:00Z' },
]

export const DEMO_INTEGRATION_RUN_ID = 'run-demo-1'

export const DEMO_MODEL_BUNDLE: IntegrationModelBundle = {
  model: {
    id: 'model-demo-1',
    projectId: DEMO_PROJECT_ID,
    createdAt: '2026-07-02T09:14:00Z',
    updatedAt: '2026-07-10T16:02:00Z',
    activeIntegrationRunId: DEMO_INTEGRATION_RUN_ID,
    rebuildRequired: false,
  },
  sources: DEMO_MODEL_SOURCES,
  mappings: DEMO_MODEL_MAPPINGS,
  constraints: DEMO_MODEL_CONSTRAINTS,
  integratedDatasetIds: [DEMO_DATASET.acm, DEMO_DATASET.semantic, DEMO_DATASET.researchers],
}

// ── Schema versions (Versions view) ────────────────────────────────────────────

export const DEMO_SCHEMA_VERSIONS: SchemaVersion[] = [
  {
    id: 'v1', versionNumber: 1, status: 'HISTORICAL',
    createdAt: '2026-07-02T09:35:00Z', createdBy: 'demo',
    promotedAt: '2026-07-02T09:36:00Z', notes: 'Initial integration: ACM + Semantic Scholar → IPaper.',
  },
  {
    id: 'v2', versionNumber: 2, status: 'ACTIVE',
    createdAt: '2026-07-10T15:58:00Z', createdBy: 'demo',
    promotedAt: '2026-07-10T16:02:00Z', notes: 'Added Researcher Profiles — Int_researchers joined via hasAuthor.',
  },
]

export const DEMO_SCHEMA_DIFF: SchemaDiff = {
  addedClasses: [INT_RESEARCHERS_IRI],
  removedClasses: [],
  changedClasses: [
    {
      classIri: IPAPER_IRI,
      addedProperties: [`${GLOBAL_NS}authorId`],
      removedProperties: [],
      wrapperChanged: false,
    },
  ],
}

// ── Data products ──────────────────────────────────────────────────────────────

export const DEMO_PRODUCTS: DataProduct[] = [
  {
    id: 'prod-demo-1',
    projectId: DEMO_PROJECT_ID,
    name: 'High-impact papers by country',
    description: 'Papers by researchers with h-index ≥ 15, one row per (paper, author).',
    oql: 'SELECT IPaper.title, IPaper.year, Int_researchers.name, Int_researchers.hIndex FROM IPaper -[hasAuthor]-> Int_researchers WHERE Int_researchers.hIndex >= 15',
    schemaVersionId: 'v2',
    status: 'READY',
    snapshotId: 'snap-product-1',
    rowCount: 6,
    materializedAt: '2026-07-11T08:00:00Z',
    lastError: null,
    createdBy: 'demo',
  } as DataProduct,
  {
    id: 'prod-demo-2',
    projectId: DEMO_PROJECT_ID,
    name: 'UPC publications, 2023–2024',
    description: 'Recent papers with at least one UPC-affiliated author.',
    oql: "SELECT IPaper.title, Int_researchers.name, Int_researchers.affiliation FROM IPaper -[hasAuthor]-> Int_researchers WHERE Int_researchers.affiliation = 'UPC' AND IPaper.year >= 2023",
    schemaVersionId: 'v2',
    status: 'READY',
    snapshotId: 'snap-product-2',
    rowCount: 2,
    materializedAt: '2026-07-11T08:05:00Z',
    lastError: null,
    createdBy: 'demo',
  } as DataProduct,
]

// ── Join discovery (Alignments → "Auto-Infer Join Alignments") ─────────────────

export interface DemoJoinCandidate {
  iriA: string
  iriB: string
  columnA: string
  columnB: string
  score: number
  label: string
}

export function demoJoinCandidates(
  datasetIdA: string, datasetIdB: string,
  method: 'ml' | 'exact' = 'exact', threshold = 0,
): DemoJoinCandidate[] {
  // Per method, because the two score on different scales (lib/joinScore.ts): 'exact' tops out
  // near 0.5 (S13-2) — 0.49 is what FREYJA really scores both pairs over the seminar files, every
  // author id being a researcher id — while the ML model spans 0–1. Filtered by the threshold the
  // way the operator filters (J3-F21: the demo used to ignore both).
  const scores: Record<'ml' | 'exact', { strong: number; weak: number }> = {
    exact: { strong: 0.49, weak: 0.05 },
    ml: { strong: 0.93, weak: 0.12 },
  }
  const sc = scores[method] ?? scores.exact
  const pairs: [string, string, DemoJoinCandidate[]][] = [
    [DEMO_DATASET.acm, DEMO_DATASET.researchers, [
      { iriA: acmAuthorId, iriB: researcherId, columnA: 'author_id', columnB: 'researcher_id', score: sc.strong, label: 'join_author_id_researcher_id' },
      { iriA: acmVenue, iriB: researcherAffiliation, columnA: 'venue', columnB: 'affiliation', score: sc.weak, label: 'join_venue_affiliation' },
    ]],
    [DEMO_DATASET.semantic, DEMO_DATASET.researchers, [
      { iriA: semanticAuthors, iriB: researcherId, columnA: 'authors', columnB: 'researcher_id', score: sc.strong, label: 'join_authors_researcher_id' },
    ]],
  ]
  for (const [a, b, candidates] of pairs) {
    if ((datasetIdA === a && datasetIdB === b) || (datasetIdA === b && datasetIdB === a)) {
      return candidates.filter((c) => c.score >= threshold)
    }
  }
  return []
}
