package edu.upc.dtak.aran.operators.bootstrapping;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ParquetBootstrap}. A real parquet file is produced with
 * DuckDB {@code COPY} (typed columns, incl. a NULL) so we exercise the actual
 * {@code DESCRIBE read_parquet} type introspection and DuckDB→XSD mapping.
 */
@DisplayName("ParquetBootstrap")
class ParquetBootstrapTest {

    private static final String DATASET_ID = "pq-dataset-id";
    private static final String SNAPSHOT_ID = "snap-pq-001";
    private static final String DS_NAME = "people";

    private final ParquetBootstrap engine = new ParquetBootstrap();

    /** Build a small parquet snapshot with typed columns via DuckDB COPY. */
    private static byte[] sampleParquet() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        Path tmp = Files.createTempFile("aran-test-parquet-", ".parquet");
        try {
            String path = tmp.toAbsolutePath().toString().replace("'", "''");
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
                 Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "COPY (SELECT * FROM (VALUES "
                        + "(1, 'ada', 12, 4.5, true, 4.5::DOUBLE), "
                        + "(2, 'grace', NULL, 9.1, false, 9.1::DOUBLE)) "
                        + "AS t(id, name, h_index, score, active, measurement)) "
                        + "TO '" + path + "' (FORMAT PARQUET)");
            }
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("produces one rdfs:Class and one typed property per parquet column")
    void typed_columns() throws Exception {
        Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, sampleParquet());

        String classIri = BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME);
        assertThat(m.contains(m.createResource(classIri), RDF.type, RDFS.Class)).isTrue();

        assertRange(m, "id", XSD.integer.getURI());
        assertRange(m, "name", XSD.xstring.getURI());
        assertRange(m, "h_index", XSD.integer.getURI());
        assertRange(m, "score", XSD.decimal.getURI());
        assertRange(m, "active", XSD.xboolean.getURI());
        // DuckDB DOUBLE must map to xsd:double, not xsd:decimal: the view body TRY_CASTs into
        // xsd:decimal's DECIMAL(38,9), which silently nulls NaN/Inf/|x|>=1e29 and rounds past 9
        // fractional digits — lossy for a value that was never a fixed-precision NUMERIC.
        assertRange(m, "measurement", XSD.xdouble.getURI());
    }

    @Test
    @DisplayName("wrapper vocab triples record PARQUET format and 'row' iterator")
    void wrapper_triples() throws Exception {
        Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, sampleParquet());

        var cls = m.createResource(BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME));
        assertThat(m.contains(cls, WrapperVocab.DATASET_ID, DATASET_ID)).isTrue();
        assertThat(m.contains(cls, WrapperVocab.SNAPSHOT_ID, SNAPSHOT_ID)).isTrue();
        assertThat(m.contains(cls, WrapperVocab.FORMAT, "PARQUET")).isTrue();
        assertThat(m.contains(cls, WrapperVocab.ITERATOR, "row")).isTrue();
    }

    @Test
    @DisplayName("per-class wrapper SQL is a flat SELECT over the ${SNAPSHOT} placeholder")
    void flat_wrapper_sql() throws Exception {
        Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID, sampleParquet());
        WrapperGenerator.attachPerClassWrappers(m);

        var cls = m.createResource(BootstrapEngine.sourceClassIri(DATASET_ID, DS_NAME));
        var sqlStmt = m.getProperty(cls, WrapperVocab.WRAPPER_SQL);
        assertThat(sqlStmt).isNotNull();
        String sql = sqlStmt.getString();
        assertThat(sql).startsWith("SELECT ");
        assertThat(sql).contains("FROM " + WrapperVocab.SNAPSHOT_PLACEHOLDER);
        // Must NOT wrap the placeholder in read_parquet — WrapperSqlValidator forbids it.
        assertThat(sql).doesNotContain("read_parquet");
    }

    // ── S4-8: the INTERVAL prefix collision ──────────────────────────────────

    @Test
    @DisplayName("an INTERVAL column is typed xsd:string, not xsd:integer (S4-8)")
    void interval_is_not_an_integer() throws Exception {
        Path parquet = intervalParquet();
        try {
            Model m = engine.bootstrap(DATASET_ID, DS_NAME, SNAPSHOT_ID,
                    Files.readAllBytes(parquet));

            assertRange(m, "elapsed", XSD.xstring.getURI());

            // The consequence, executed rather than argued. The int_ view TRY_CASTs each column
            // into the DuckDB type its XSD range maps to (XsdTypes.duckType — BIGINT for
            // xsd:integer, VARCHAR for xsd:string), and TRY_CAST turns a value it cannot convert
            // into NULL rather than failing. So mistyping this column does not produce an error a
            // user could act on; it empties the column, silently, for every row.
            assertThat(nonNullAfterCast(parquet, "BIGINT"))
                    .as("xsd:integer's TRY_CAST target keeps nothing — the failure this test is about")
                    .isZero();
            assertThat(nonNullAfterCast(parquet, "VARCHAR"))
                    .as("xsd:string's target keeps every row")
                    .isEqualTo(2);
        } finally {
            Files.deleteIfExists(parquet);
        }
    }

    @Test
    @DisplayName("the integer family still matches by name, prefix collisions and all")
    void integer_family_still_matches() {
        // The bug was `t.startsWith("INT")`, which claims every type whose name begins INT.
        // These must keep their ranges, or the fix has traded one mistyping for another.
        assertThat(ParquetBootstrap.xsdRangeFor("INTEGER")).isEqualTo(XSD.integer);
        assertThat(ParquetBootstrap.xsdRangeFor("INT")).isEqualTo(XSD.integer);
        assertThat(ParquetBootstrap.xsdRangeFor("INT4")).isEqualTo(XSD.integer);
        assertThat(ParquetBootstrap.xsdRangeFor("INT8")).isEqualTo(XSD.integer);
        assertThat(ParquetBootstrap.xsdRangeFor("BIGINT")).isEqualTo(XSD.integer);
        assertThat(ParquetBootstrap.xsdRangeFor("HUGEINT")).isEqualTo(XSD.integer);
        assertThat(ParquetBootstrap.xsdRangeFor("UINTEGER")).isEqualTo(XSD.integer);
        assertThat(ParquetBootstrap.xsdRangeFor("TINYINT")).isEqualTo(XSD.integer);

        assertThat(ParquetBootstrap.xsdRangeFor("INTERVAL")).isEqualTo(XSD.xstring);
    }

    /** A parquet file with two rows and one INTERVAL column, written by DuckDB itself. */
    private static Path intervalParquet() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        Path tmp = Files.createTempFile("aran-test-interval-", ".parquet");
        String path = tmp.toAbsolutePath().toString().replace("'", "''");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "COPY (SELECT * FROM (VALUES "
                    + "(1, INTERVAL 3 DAY), (2, INTERVAL 90 MINUTE)) AS t(id, elapsed)) "
                    + "TO '" + path + "' (FORMAT PARQUET)");
        }
        return tmp;
    }

    /** How many rows survive {@code TRY_CAST(elapsed AS <type>)} — i.e. are not nulled. */
    private static long nonNullAfterCast(Path parquet, String duckType) throws Exception {
        String path = parquet.toAbsolutePath().toString().replace("'", "''");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM read_parquet('" + path + "') "
                     + "WHERE TRY_CAST(elapsed AS " + duckType + ") IS NOT NULL")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void assertRange(Model m, String key, String rangeIri) {
        String propIri = BootstrapEngine.sourcePropertyIri(DATASET_ID, DS_NAME, key);
        assertThat(m.contains(m.createResource(propIri), RDFS.range, m.createResource(rangeIri)))
                .as("Expected property <%s> rdfs:range <%s>", propIri, rangeIri)
                .isTrue();
    }
}
