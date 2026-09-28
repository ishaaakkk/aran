package edu.upc.dtak.aran.core.domain.drift;

import edu.upc.dtak.aran.core.util.Names;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;

/**
 * The shape of a <em>live</em> federated table, as a fingerprint the drift service can compare.
 *
 * <p>Federated datasets are the drift case with no ingest to hook: a {@code postgres_scan} or an
 * attached MySQL catalog reads the remote table at query time, so nothing ever re-bootstraps and
 * no snapshot is ever taken. A column that changes type there produces silently wrong aggregates
 * — {@code MAX(year)} over a string is lexicographic — with no error anywhere. The only way to
 * notice is to go and look.
 *
 * <p><b>Metadata only.</b> The looking — a {@code DESCRIBE} over the {@code FederatedScan}, which
 * asks the remote server for column names and types without scanning a single row — lives in the
 * bootstrapping operator ({@code LiveSchemaProbe}), which owns the DuckDB session. This class
 * keeps the part that is pure.
 *
 * <p>A fingerprint captured this way records less than a real bootstrap does — there is no
 * iterator or file format to speak of — which is exactly why
 * {@link StoredSchemaFingerprint.CaptureMethod} exists: a probe is only ever compared against
 * another probe, never against a snapshot introspection.
 */
@NullMarked
public final class FederatedSchemaProbe {

    /** The iterator recorded for a probed class. Live tables are row-shaped, like CSV. */
    public static final String LIVE_ITERATOR = "row";

    /** One column as the remote server currently declares it. */
    public record ProbedColumn(String name, String type) {}

    private FederatedSchemaProbe() {}

    /**
     * Build the fingerprint for a set of probed columns.
     *
     * <p>Separated from the JDBC call so the shape logic is testable without a live remote — the
     * IRIs must be minted exactly as {@code BootstrapEngine} would, or a probe fingerprint would
     * never line up with the mappings that reference the same source.
     *
     * @param classIri the source class IRI, minted by the caller the same way bootstrapping does
     */
    public static SchemaFingerprint fingerprint(String datasetId, String classIri, String engine,
                                                List<ProbedColumn> columns) {
        List<SchemaFingerprint.PropertyShape> properties = new ArrayList<>(columns.size());
        for (ProbedColumn column : columns) {
            properties.add(new SchemaFingerprint.PropertyShape(
                    classIri + "/" + Names.iriFragment(column.name()),
                    column.name(),
                    column.name(),
                    // The remote engine's own type name, normalised for case only. Not mapped onto
                    // XSD: the point is to notice it CHANGED, and mapping INTEGER and BIGINT onto
                    // one XSD type would hide exactly the narrowing worth noticing.
                    column.type().toUpperCase(Locale.ROOT)));
        }
        return new SchemaFingerprint(datasetId, List.of(
                new SchemaFingerprint.ClassShape(classIri, engine.toUpperCase(Locale.ROOT),
                        LIVE_ITERATOR, properties)));
    }

    /** The remote could not be read. Distinct from "the remote changed". */
    public static class ProbeUnavailableException extends RuntimeException {
        public ProbeUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        /** For a cause whose message must not travel, e.g. a failed credential-bearing statement. */
        public ProbeUnavailableException(String message) {
            super(message);
        }
    }
}
