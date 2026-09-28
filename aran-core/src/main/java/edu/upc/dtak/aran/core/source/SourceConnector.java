package edu.upc.dtak.aran.core.source;

import org.jspecify.annotations.NullMarked;

/**
 * One implementation per source kind: how a descriptor is claimed and how its schema is learned.
 * Implementations are discovered via Spring component scan and collected by
 * {@link SourceConnectors}.
 *
 * <p>The contract deliberately separates <em>learning the schema</em> ({@link #introspect}) from
 * <em>moving the data</em>: {@code introspect} must not pull a source's rows into memory for
 * large/live systems — it returns an RDFS schema graph and nothing else.
 *
 * <p>This is the whole SPI. How a dataset is read at query time (live scan or lake copy), which
 * DuckDB extensions that needs, and how it refreshes are decided per repository kind by
 * {@link SourceAccess}, {@code FederatedScanExpression}, the query engine's resolver and
 * {@code DatasetService}. A capability half once declared those here (accessModes, federatedScan,
 * duckDbExtensions, refreshPolicies, plus a {@code BindingStrategy} on the schema) and nothing
 * read it, so the declarations were true of the connector and false of the system (S3-1). D1's
 * {@code ReadBinding}/handler work is what makes such a declaration load-bearing; until it lands,
 * there is none to trust.
 */
@NullMarked
public interface SourceConnector {

    /**
     * Stable identifier for this source kind, e.g. {@code "CSV"}, {@code "JSON"},
     * {@code "PARQUET"}, later {@code "POSTGRESQL"} / {@code "SALESFORCE"}. Used to honour a
     * caller's explicit {@code format}/kind override and to label the operator's output.
     * Must be unique across connectors.
     */
    String sourceKind();

    /**
     * Whether this connector handles the given descriptor. For file connectors this mirrors
     * the old MIME/extension detection. Implementations must be mutually exclusive so at most
     * one connector claims any descriptor (enforced at startup by {@link SourceConnectors}).
     */
    boolean supports(SourceDescriptor source);

    /**
     * Learn the dataset's schema and emit the RDFS graph + wrapper metadata. For file sources this
     * may read the (small, already in-lake) bytes on the descriptor; for DB/API sources it must
     * use the catalog/describe API only and never materialize rows.
     *
     * @throws java.io.IOException if the source cannot be read/introspected.
     */
    SourceSchema introspect(SourceDescriptor source) throws java.io.IOException;
}
