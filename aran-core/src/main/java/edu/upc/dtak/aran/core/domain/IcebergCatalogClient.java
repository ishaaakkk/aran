package edu.upc.dtak.aran.core.domain;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/**
 * Speaks the Apache Iceberg REST catalog protocol's read subset to one
 * {@link IcebergRestRepository} — the control-plane half of the consume path (Gap 2).
 *
 * <p>A port, for the same reason {@link LakeClient} is one: three modules need it
 * ({@code DataRepositoryService} for test/scan, the bootstrapping connector for introspection, the
 * query engine's resolver for the fresh metadata location at query time) and none of them may own
 * an HTTP client or a JSON parser for it. The implementation lives in
 * {@code aran-adapters/aran-adapter-iceberg}.
 *
 * <p>Deliberately <b>read-only and metadata-only</b>: nothing here moves a data row. The rows are
 * the data plane's business — DuckDB's {@code iceberg} extension reads them straight from the
 * object store, authenticated by the repository's S3-style credentials, never through this client.
 *
 * <h2>Identifiers</h2>
 *
 * <p>A table is addressed as {@code namespace.table}, with multi-level namespaces joined by
 * {@code .} as well ({@code ns1.ns2.table}); the last dot splits namespace from table. A namespace
 * or table whose <em>own name</em> contains a literal dot is therefore not addressable — an honest
 * v1 limit carried by this contract rather than discovered as a wrong split downstream.
 */
@NullMarked
public interface IcebergCatalogClient {

    /** One field of a table's current schema, with the Iceberg type rendered as its JSON string
     * (e.g. {@code "long"}, {@code "string"}, {@code "decimal(38,9)"}; nested types render as
     * {@code "struct"} / {@code "list"} / {@code "map"}). */
    record IcebergField(String name, String type, boolean required) {}

    /**
     * A table as the catalog currently serves it.
     *
     * @param metadataLocation where the table's current {@code metadata.json} lives — the value
     *                         {@code iceberg_scan} takes. Re-fetched at query time, never stored:
     *                         every commit to the table moves it.
     * @param fields           the current schema's top-level fields.
     */
    record IcebergTable(String metadataLocation, List<IcebergField> fields) {}

    /**
     * {@code GET /v1/config} — the reachability and auth probe. Returns the catalog's merged
     * defaults/overrides map (possibly empty); throws with the catalog's own diagnostics when the
     * catalog is unreachable or refuses the token.
     */
    Map<String, String> config(IcebergRestRepository repository);

    /**
     * Every table the catalog offers, as {@code namespace.table} identifiers, across all
     * namespaces (nested namespaces walked recursively). This is what a repository scan lists and
     * what dataset registration checks membership against.
     */
    List<String> listTables(IcebergRestRepository repository);

    /** {@code GET /v1/…/tables/{table}} for one {@code namespace.table} identifier. */
    IcebergTable loadTable(IcebergRestRepository repository, String tableIdentifier);
}
