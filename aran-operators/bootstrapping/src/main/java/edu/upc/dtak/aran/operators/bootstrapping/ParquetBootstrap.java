package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/**
 * Bootstraps a Parquet snapshot into an RDFS-typed graph with wrapper metadata.
 *
 * <p>Mirrors {@link CsvBootstrap}: one {@code rdfs:Class} for the dataset, one
 * {@code rdf:Property} per column. Unlike CSV — where every cell starts as
 * {@code xsd:string} and type refinement is profiling's job — Parquet carries a
 * real column schema, so we read it via DuckDB {@code DESCRIBE SELECT * FROM
 * read_parquet(...)} and map each DuckDB type to its XSD counterpart up front.
 *
 * <p>Wrapper layer ({@link WrapperVocab}): the class carries {@code datasetId},
 * {@code snapshotId}, {@code format="PARQUET"} and iterator {@code "row"} (one
 * record per row, no unnesting). Each property carries its column name as
 * {@code sourceRef}. The per-class wrapper SQL uses the flat CSV-style
 * projection ({@code SELECT "col" AS "propIri" … FROM ${SNAPSHOT}}) — the
 * {@code ${SNAPSHOT}} placeholder is bound to a {@code read_parquet} view by the
 * query executor's resolver, never by the wrapper itself (which
 * {@code WrapperSqlValidator} forbids from naming {@code read_parquet}).
 */
@NullMarked
final class ParquetBootstrap {

    Model bootstrap(String datasetId, String datasetName, String snapshotId, byte[] bytes) throws IOException {
        Model model = ModelFactory.createDefaultModel();

        Path tmp = Files.createTempFile("aran-bootstrap-parquet-", ".parquet");
        try {
            Files.write(tmp, bytes);

            String classIri = BootstrapEngine.sourceClassIri(datasetId, datasetName);
            Resource cls = model.createResource(classIri);
            model.add(cls, RDF.type, RDFS.Class);
            model.add(cls, RDFS.label, datasetName);
            WrapperVocab.attachClassWrapper(model, cls, datasetId, snapshotId, "PARQUET", "row",
                    WrapperVocab.Shape.FLAT);

            String describeSql = "DESCRIBE SELECT * FROM read_parquet('"
                    + Names.sqlLiteral(tmp.toAbsolutePath().toString()) + "')";

            try (Connection conn = DuckDbSession.open();
                 Statement stmt = conn.createStatement()) {
                addColumnsFromDescribe(model, cls, datasetId, datasetName, stmt, describeSql);
            } catch (java.sql.SQLException e) {
                throw new IOException("Failed to read parquet schema via DuckDB: " + e.getMessage(), e);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // temp file cleanup is best-effort
            }
        }
        return model;
    }

    /**
     * Run {@code describeSql} on {@code stmt} and add one {@code rdf:Property} per described column —
     * typed via {@link #xsdRangeFor} — to {@code cls}. Shared with {@link ObjectStorageBootstrap} so
     * the DuckDB-type → XSD mapping and the property-emission shape live in exactly one place (a
     * second copy would drift: a source view whose columns are typed one way here
     * and another way at query time).
     *
     * <p>{@code DESCRIBE} returns only column metadata; it does not read the described query's rows.
     * For a parquet source that is the footer alone, so this stays metadata-only regardless of the
     * object's size — the property the object-storage introspection depends on.
     */
    static void addColumnsFromDescribe(Model model, Resource cls, String datasetId,
                                       String datasetName, Statement stmt, String describeSql)
            throws java.sql.SQLException {
        try (ResultSet rs = stmt.executeQuery(describeSql)) {
            while (rs.next()) {
                String column = rs.getString("column_name");
                String duckType = rs.getString("column_type");
                if (column == null || column.isBlank()) continue;
                String h = column.trim();
                String propIri = BootstrapEngine.sourcePropertyIri(datasetId, datasetName, h);
                Resource range = xsdRangeFor(duckType);
                Resource prop = model.createResource(propIri);
                model.add(prop, RDF.type, RDF.Property);
                model.add(prop, RDFS.label, h);
                model.add(prop, RDFS.domain, cls);
                model.add(prop, RDFS.range, range);
                model.add(prop, WrapperVocab.SOURCE_REF, h);
            }
        }
    }

    /**
     * Map a DuckDB {@code column_type} to its XSD datatype. DuckDB decorates some
     * types (e.g. {@code DECIMAL(18,2)}, {@code VARCHAR[]}) so we match on the
     * leading keyword. Anything unrecognised falls back to {@code xsd:string}.
     *
     * <p>Package-private, not private: {@link ObjectStorageBootstrap} reuses it so parquet read from
     * the lake and parquet read from an object store type their columns identically.
     */
    static Resource xsdRangeFor(String duckType) {
        String t = duckType == null ? "" : duckType.toUpperCase(Locale.ROOT).trim();
        if (t.startsWith("VARCHAR") || t.startsWith("CHAR") || t.startsWith("TEXT") || t.startsWith("STRING")) {
            return XSD.xstring;
        }
        // INTERVAL before the integer family, and the integer family no longer claims a bare "INT"
        // prefix. `t.startsWith("INT")` matched INTERVAL, so a duration column was declared
        // xsd:integer and the int_ view's TRY_CAST to BIGINT turned every row of it into NULL —
        // no error, no empty result, just a column that is silently blank (S4-8). DuckDB's own
        // integer aliases end there or continue with a digit (INT, INT1/2/4/8), which is what the
        // test below distinguishes them by.
        if (t.startsWith("INTERVAL")) {
            // xsd:string, not a narrower range, because nothing narrower survives the round trip:
            // XsdTypes has no duration or interval case, so any other IRI would fall through to
            // VARCHAR there anyway — while telling every other reader of the graph (alignment
            // comparability, the JDBC map, a future harvest) something the engine cannot honour.
            // VARCHAR is also what DuckDB casts an INTERVAL to losslessly.
            return XSD.xstring;
        }
        if (t.startsWith("BIGINT") || t.startsWith("INTEGER") || isIntAlias(t)
                || t.startsWith("SMALLINT") || t.startsWith("TINYINT") || t.startsWith("HUGEINT")
                || t.startsWith("UBIGINT") || t.startsWith("UINTEGER") || t.startsWith("USMALLINT")
                || t.startsWith("UTINYINT")) {
            return XSD.integer;
        }
        // DOUBLE/FLOAT/REAL are IEEE floating point, not fixed-precision — matches
        // JdbcBootstrap.xsdFor. xsd:decimal's DuckDB target is DECIMAL(38,9): TRY_CAST-ing a
        // float through it silently nulls NaN/Inf/|x|>=1e29 and rounds past 9 fractional
        // digits, which is correct for a genuine NUMERIC/DECIMAL source but lossy for a value
        // that was never fixed-precision to begin with.
        if (t.startsWith("DOUBLE") || t.startsWith("FLOAT") || t.startsWith("REAL")) {
            return XSD.xdouble;
        }
        if (t.startsWith("DECIMAL") || t.startsWith("NUMERIC")) {
            return XSD.decimal;
        }
        if (t.startsWith("BOOLEAN") || t.startsWith("BOOL")) {
            return XSD.xboolean;
        }
        if (t.startsWith("TIMESTAMP") || t.startsWith("DATETIME")) {
            return XSD.dateTime;
        }
        if (t.startsWith("DATE")) {
            return XSD.date;
        }
        return XSD.xstring;
    }

    /**
     * Whether the type is DuckDB's {@code INT} or one of its width aliases {@code INT1}, {@code
     * INT2}, {@code INT4}, {@code INT8} — as opposed to any other type whose name merely starts
     * with those three letters. A decoration may follow ({@code INT4[]}), so the test is on what
     * comes immediately after the alias, not on the whole string.
     */
    private static boolean isIntAlias(String t) {
        if (!t.startsWith("INT")) {
            return false;
        }
        return t.length() == 3 || Character.isDigit(t.charAt(3));
    }
}
