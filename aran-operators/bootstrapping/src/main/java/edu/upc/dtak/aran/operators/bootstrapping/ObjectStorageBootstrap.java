package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.source.DuckDbS3Config;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.jspecify.annotations.NullMarked;

/**
 * Bootstraps one object-store object ({@code s3://bucket/key}) into an RDFS-typed graph, reading its
 * schema in place over DuckDB's {@code httpfs} — never copying the object into the lake.
 *
 * <p>It is the object-store twin of {@link ParquetBootstrap}: one {@code rdfs:Class} for the
 * dataset, one {@code rdf:Property} per column, each column typed from the real schema DuckDB
 * reports. The two share the DESCRIBE→property loop and the DuckDB-type → XSD map
 * ({@link ParquetBootstrap#addColumnsFromDescribe}, {@link ParquetBootstrap#xsdRangeFor}) so a
 * parquet column is typed identically whether it arrives as an upload or as an S3 object.
 *
 * <p><b>Metadata-only.</b> The only statement issued against the object is
 * {@code DESCRIBE SELECT * FROM read_parquet('s3://…') LIMIT 0} (or {@code read_csv_auto}), which
 * returns column names and types and reads no rows — the parquet footer, or DuckDB's bounded CSV
 * sniff sample, never the whole object.
 *
 * <p><b>Credential hygiene.</b> The {@code CREATE SECRET} statement from {@link DuckDbS3Config}
 * carries the secret key; it is executed on a scratch connection and discarded, and is never logged
 * nor placed in an exception message. If binding the secret fails, the thrown message names the
 * bucket only, not the statement.
 */
@NullMarked
final class ObjectStorageBootstrap {

    Model bootstrap(SourceDescriptor.ObjectStorage obj, String datasetId, String datasetName)
            throws IOException {
        Model model = ModelFactory.createDefaultModel();

        String classIri = BootstrapEngine.sourceClassIri(datasetId, datasetName);
        Resource cls = model.createResource(classIri);
        model.add(cls, RDF.type, RDFS.Class);
        model.add(cls, RDFS.label, datasetName);

        String format = normalizeFormat(obj.format());
        // No lake snapshot — nothing is copied in, the object is read live over httpfs. The object
        // key goes in the iterator slot to locate the object within the bucket, exactly as
        // JdbcBootstrap puts the table name there: for a non-file source the iterator is the
        // specific relation to read, not a record shape.
        // FLAT whatever the object's format: the columns come from DESCRIBE over the reader, one
        // per top-level field, so even a JSON object is read as a relation here (S4-1).
        WrapperVocab.attachClassWrapper(model, cls, datasetId, "", format, obj.objectKey(),
                WrapperVocab.Shape.FLAT);

        DuckDbS3Config s3 = obj.toS3Config();
        String describeSql =
                "DESCRIBE SELECT * FROM " + readExpression(format, obj.s3Url()) + " LIMIT 0";

        try (Connection conn = DuckDbSession.open();
             Statement stmt = conn.createStatement()) {

            for (String ext : s3.installAndLoadHttpfs()) {
                stmt.execute(ext);
            }
            try {
                stmt.execute(s3.createSecretSql());
            } catch (SQLException e) {
                // The statement holds the secret and a DuckDB secret error can echo option values,
                // so neither the SQL nor e's message is surfaced — only the bucket name.
                throw new IOException("failed to bind S3 credentials for bucket '" + obj.bucket() + "'");
            }
            try {
                ParquetBootstrap.addColumnsFromDescribe(model, cls, datasetId, datasetName, stmt, describeSql);
            } catch (SQLException e) {
                // describeSql names read_parquet('s3://bucket/key') only — no secret — so echoing
                // its message is safe and is the most useful thing when a key, region or key-name is
                // wrong.
                throw new IOException("failed to introspect object '" + obj.s3Url() + "': "
                        + e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new IOException("DuckDB error introspecting object storage: " + e.getMessage(), e);
        }
        return model;
    }

    /**
     * The DuckDB read expression for {@code location} by object format. Mirrors the smallest slice of
     * {@code SnapshotFormats.readExpression} (query-engine) — bootstrapping cannot depend on
     * query-engine (a source connector must not pull in the Calcite query planner), and
     * {@code SnapshotFormats} is that module's canonical authority; keep the two in step. Same
     * approach, and same reason, as {@code ConstraintDiscoveryOperator.readExpressionFor}.
     */
    static String readExpression(String format, String location) {
        String reader = switch (format.toUpperCase(Locale.ROOT)) {
            case "CSV", "TSV" -> "read_csv_auto";
            case "PARQUET" -> "read_parquet";
            default -> "read_json_auto";
        };
        return reader + "('" + Names.sqlLiteral(location) + "')";
    }

    /**
     * Uppercase the declared format; blank degrades to PARQUET, the object store's common case.
     * Shared with {@link DuckDbObjectStorageExtractor} so introspection's reader choice and the
     * MATERIALIZED copy's cannot disagree on the same object.
     */
    static String normalizeFormat(String format) {
        return format == null || format.isBlank() ? "PARQUET" : format.toUpperCase(Locale.ROOT);
    }
}
