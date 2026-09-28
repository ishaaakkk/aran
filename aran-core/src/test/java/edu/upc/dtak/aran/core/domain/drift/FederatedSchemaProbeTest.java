package edu.upc.dtak.aran.core.domain.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Probing a live federated table.
 *
 * <p>The shape logic is tested directly; the {@code DESCRIBE} column names the probe's parser
 * depends on are pinned against DuckDB itself (a local table needs no remote and no Docker). The
 * probe's own JDBC half is {@code LiveSchemaProbeTest}, in the bootstrapping operator.
 */
class FederatedSchemaProbeTest {

    private static final String DS = "11111111-1111-1111-1111-111111111111";
    private static final String CLS = "http://ex.org/source/" + DS + "/papers";

    @Test
    @DisplayName("columns become properties keyed on their real names")
    void buildsFingerprint() {
        SchemaFingerprint fingerprint = FederatedSchemaProbe.fingerprint(DS, CLS, "POSTGRESQL",
                List.of(new FederatedSchemaProbe.ProbedColumn("title", "VARCHAR"),
                        new FederatedSchemaProbe.ProbedColumn("year", "INTEGER")));

        assertThat(fingerprint.classes()).singleElement().satisfies(c -> {
            assertThat(c.iri()).isEqualTo(CLS);
            assertThat(c.iterator()).isEqualTo(FederatedSchemaProbe.LIVE_ITERATOR);
            assertThat(c.format()).isEqualTo("POSTGRESQL");
            assertThat(c.properties()).extracting(SchemaFingerprint.PropertyShape::sourceRef)
                    .containsExactly("title", "year");
        });
    }

    @Test
    @DisplayName("a column IRI is minted the way BootstrapEngine mints one")
    void iriMatchesBootstrapConvention() {
        // A probe fingerprint whose IRIs differ from the bootstrapped ones would never line up
        // with the mappings that reference the same source, so every impact would come back empty.
        SchemaFingerprint fingerprint = FederatedSchemaProbe.fingerprint(DS, CLS, "POSTGRESQL",
                List.of(new FederatedSchemaProbe.ProbedColumn("year of publication", "INTEGER")));

        assertThat(fingerprint.classes().get(0).properties().get(0).iri())
                .isEqualTo(CLS + "/year_of_publication");
    }

    @Test
    @DisplayName("a type change on a live column shows up as drift")
    void typeChangeIsVisible() {
        // The federated case the whole probe exists for: nobody re-ingested anything, but
        // MAX(year) just became lexicographic.
        SchemaFingerprint before = FederatedSchemaProbe.fingerprint(DS, CLS, "POSTGRESQL",
                List.of(new FederatedSchemaProbe.ProbedColumn("year", "INTEGER")));
        SchemaFingerprint after = FederatedSchemaProbe.fingerprint(DS, CLS, "POSTGRESQL",
                List.of(new FederatedSchemaProbe.ProbedColumn("year", "VARCHAR")));

        assertThat(SchemaDiff.between(before, after)).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(SchemaChangeKind.TYPE_CHANGED);
            assertThat(c.before()).isEqualTo("INTEGER");
            assertThat(c.after()).isEqualTo("VARCHAR");
        });
    }

    @Test
    @DisplayName("engine types are compared verbatim, not folded onto XSD")
    void keepsEngineTypes() {
        // Mapping INTEGER and BIGINT onto one XSD type would hide a narrowing that can start
        // truncating values — exactly the change worth catching.
        SchemaFingerprint big = FederatedSchemaProbe.fingerprint(DS, CLS, "POSTGRESQL",
                List.of(new FederatedSchemaProbe.ProbedColumn("id", "BIGINT")));
        SchemaFingerprint small = FederatedSchemaProbe.fingerprint(DS, CLS, "POSTGRESQL",
                List.of(new FederatedSchemaProbe.ProbedColumn("id", "INTEGER")));

        assertThat(SchemaDiff.between(big, small)).hasSize(1);
    }

    @Test
    @DisplayName("type comparison ignores case, so a dialect's casing is not reported as drift")
    void typeCaseIsNormalised() {
        SchemaFingerprint upper = FederatedSchemaProbe.fingerprint(DS, CLS, "POSTGRESQL",
                List.of(new FederatedSchemaProbe.ProbedColumn("year", "integer")));
        SchemaFingerprint lower = FederatedSchemaProbe.fingerprint(DS, CLS, "POSTGRESQL",
                List.of(new FederatedSchemaProbe.ProbedColumn("year", "INTEGER")));

        assertThat(SchemaDiff.between(upper, lower)).isEmpty();
    }

    @Test
    @DisplayName("DESCRIBE yields column_name/column_type — the names the parser reads")
    void describeParsesDuckDbColumns() throws Exception {
        // Runs against a plain DuckDB table rather than a federated scan: no remote, no Docker,
        // but the DESCRIBE result-set shape is identical, and that shape is what the parser
        // depends on. A DuckDB upgrade renaming these columns fails here.
        Class.forName("org.duckdb.DuckDBDriver");
        try (var conn = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE papers (title VARCHAR, year INTEGER)");
            try (var rs = stmt.executeQuery("DESCRIBE SELECT * FROM papers LIMIT 0")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("column_name")).isEqualTo("title");
                assertThat(rs.getString("column_type")).isEqualTo("VARCHAR");
            }
        }
    }

}
