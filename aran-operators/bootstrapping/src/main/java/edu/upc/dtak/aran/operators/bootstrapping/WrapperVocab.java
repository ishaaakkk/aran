package edu.upc.dtak.aran.operators.bootstrapping;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.jspecify.annotations.NullMarked;

/**
 * Vocabulary for the source-wrapper metadata that bootstrapping attaches to each
 * source schema. The wrapper is what Algorithm 10 of Flores et al. (2024) reads
 * via {@code GetDataSourcePath}, {@code GetIterator} and {@code GetSourceReference}
 * when generating RML mappings — and what the query rewriter consults to push an
 * OQL query down onto the actual data.
 *
 * <p>Rather than carrying the wrapper as an opaque string the way old Aran did, we
 * encode it as RDF triples attached to the source classes and properties
 * themselves. This keeps the source graph self-describing, survives the
 * integration union without special handling, and stays queryable via SPARQL.
 *
 * <p>Triples attached per source class C:
 * <pre>
 *   C nextiadi:datasetId   "&lt;uuid&gt;"
 *   C nextiadi:snapshotId  "&lt;snapshot-id&gt;"
 *   C nextiadi:format      "JSON" | "CSV"
 *   C nextiadi:iterator    "&lt;path&gt;"
 *   C nextiadi:wrapperSql  "SELECT … FROM ${SNAPSHOT} …"    -- per-class wrapper
 * </pre>
 * For JSON, the iterator is a JSONPath that yields one record of C (e.g. {@code "$"} for
 * a single-document root, {@code "$[*]"} for an array root, or {@code "$.artworks[*]"} for a
 * nested array of objects). For CSV the iterator is the literal {@code "row"}.
 *
 * <p>Triples attached per source property P:
 * <pre>
 *   P nextiadi:sourceRef   "&lt;reference&gt;"
 * </pre>
 * For CSV this is the column name. For JSON it is the key relative to the
 * domain class's iterator (e.g. {@code "title"}, or {@code "tags[*]"} for an array
 * of primitives).
 *
 * <p><b>Snapshot placeholder.</b> The {@code wrapperSql} value uses the literal
 * string {@code ${SNAPSHOT}} in its FROM clause as a placeholder for the data
 * source. This is substituted by the consumer:
 * <ul>
 *   <li>The wrapper-preview endpoint substitutes
 *       {@code read_json_auto('<temp-file>')} or {@code read_csv_auto(...)}.</li>
 *   <li>The query executor substitutes a pre-created per-dataset DuckDB view name,
 *       e.g. {@code src_a1b2c3d4} — see {@link MappingCatalog}.</li>
 * </ul>
 */
@NullMarked
public final class WrapperVocab {

    public static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";

    public static final Property DATASET_ID  = ResourceFactory.createProperty(NS + "datasetId");
    public static final Property SNAPSHOT_ID = ResourceFactory.createProperty(NS + "snapshotId");
    public static final Property FORMAT      = ResourceFactory.createProperty(NS + "format");
    public static final Property ITERATOR    = ResourceFactory.createProperty(NS + "iterator");
    public static final Property SOURCE_REF  = ResourceFactory.createProperty(NS + "sourceRef");

    /**
     * Provenance: the dataset a source class originates from, recorded as a string
     * literal equal to the dataset id. This is the trace the mapping catalog
     * ({@code MappingCatalogOperator}) reads to resolve a source view's snapshot, and
     * the runbook's {@code nx:provenance}. We write it eagerly at bootstrap (rather than
     * only at integration time) so the value is a literal id — never a graph-URI resource —
     * and so it survives the integration union unchanged.
     */
    public static final Property PROVENANCE  = ResourceFactory.createProperty(NS + "provenance");

    /** SQL query (per-class) that produces tuples of the class, with columns aliased to property IRIs. */
    public static final Property WRAPPER_SQL = ResourceFactory.createProperty(NS + "wrapperSql");

    /**
     * The record shape of a source class — {@link Shape#FLAT} (one row, one column per property:
     * CSV, parquet, a relational table, an Iceberg table, an object read through DESCRIBE) or
     * {@link Shape#DOCUMENT} (a JSON document the iterator walks, whose wrapper reaches its
     * properties through struct-field access and UNNEST laterals).
     *
     * <p>Written explicitly since D1 (2026-09-07), and only ever {@code DOCUMENT} from
     * {@code JsonBootstrap}. Before this, {@code WrapperSqlBuilder} INFERRED the shape from
     * {@link #FORMAT} through a closed allowlist — CSV, PARQUET and JDBC were flat, everything else
     * was a document — so the eighth kind, Iceberg ("ICEBERG"), and an object-storage JSON object
     * were sent down the JSONPath branch and got wrappers that bound to nothing (S4-1, the same
     * defect V45 had already fixed once for JDBC by adding one more string to the allowlist). A
     * format names a reader; it does not say whether the rows are flat, and a kind nobody has
     * thought of yet must default to the shape that cannot invent struct paths.
     */
    public static final Property SHAPE = ResourceFactory.createProperty(NS + "shape");

    /** The two record shapes a wrapper can be built for. Stored as the enum name. */
    public enum Shape { FLAT, DOCUMENT }

    /** Placeholder used in {@link #WRAPPER_SQL} for the data-source FROM target. */
    public static final String SNAPSHOT_PLACEHOLDER = "${SNAPSHOT}";

    private WrapperVocab() {}

    /** Convenience: attach the full wrapper context to a source class (sans SQL — added separately). */
    public static void attachClassWrapper(Model m,
                                          Resource cls,
                                          String datasetId,
                                          String snapshotId,
                                          String format,
                                          String iterator,
                                          Shape shape) {
        m.add(cls, DATASET_ID, datasetId);
        m.add(cls, SNAPSHOT_ID, snapshotId);
        m.add(cls, FORMAT, format);
        m.add(cls, ITERATOR, iterator);
        m.add(cls, SHAPE, shape.name());
        // Provenance == datasetId, as a literal. The catalog resolves snapshots from this.
        m.add(cls, PROVENANCE, datasetId);
    }
}
