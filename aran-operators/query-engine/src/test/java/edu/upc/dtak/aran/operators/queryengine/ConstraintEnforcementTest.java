package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6 — enforced denial constraints filter the {@code int_} view and route the rejects into
 * the {@code q_} quarantine view. The constraint is authored against the class's global
 * property <em>local name</em> ({@code h_index}) while the {@code int_} view exposes columns
 * named by the full property IRI — so this also exercises the local-name → IRI rewrite.
 */
class ConstraintEnforcementTest {

    private static final String H_INDEX = "http://ex/prop/h_index";
    private static final String NAME = "http://ex/prop/name";

    @Test
    void enforcedConstraintFiltersIntViewAndFillsQuarantine() throws Exception {
        Path tempDir = Files.createTempDirectory("aran-m6-test");
        File researchers = tempDir.resolve("researchers.json").toFile();
        try (FileWriter fw = new FileWriter(researchers)) {
            // Ada is kept (h_index 5 ≥ 0); Neg is a reject (h_index -1 < 0).
            fw.write("[{\"name\":\"Ada\",\"h_index\":5},{\"name\":\"Neg\",\"h_index\":-1}]");
        }

        DuckDbExecutor.SnapshotResolver resolver = ds ->
                "read_json_auto('" + researchers.getAbsolutePath() + "')";

        Map<String, String> columnMap = Map.of(NAME, "name", H_INDEX, "h_index");
        MappingCatalog.SourceView src =
                new MappingCatalog.SourceView("researchers", "SELECT * FROM ${SNAPSHOT}", columnMap);
        MappingCatalog.IntegratedClass iResearcher =
                new MappingCatalog.IntegratedClass("urn:IResearcher", List.of(src));
        MappingCatalog catalog = new MappingCatalog(
                Map.of("urn:IResearcher", iResearcher), List.of(), List.of(), "v1");

        Map<String, List<String>> enforced = Map.of("urn:IResearcher", List.of("h_index >= 0"));

        // explain surface: the generated DDL must show the rewritten WHERE clause.
        List<String> ddl = DuckDbExecutor.buildDdl(catalog, resolver, enforced);
        assertThat(ddl).anySatisfy(s -> assertThat(s)
                .contains("CREATE OR REPLACE VIEW int_IResearcher")
                .contains("WHERE (\"" + H_INDEX + "\" >= 0)"));
        assertThat(ddl).anySatisfy(s -> assertThat(s)
                .contains("CREATE OR REPLACE VIEW q_IResearcher")
                .contains("WHERE NOT (")
                .contains("\"" + H_INDEX + "\" >= 0"));

        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, enforced)) {
            exec.prepare();

            // int_ view excludes the negative row.
            DuckDbExecutor.QueryResult kept =
                    exec.run("SELECT \"" + NAME + "\" AS n FROM int_IResearcher ORDER BY n");
            assertThat(kept.rows()).hasSize(1);
            assertThat(kept.rows().get(0)).containsExactly("Ada");

            // q_ view holds exactly the rejected row.
            DuckDbExecutor.QueryResult rejected =
                    exec.run("SELECT \"" + NAME + "\" AS n, \"" + H_INDEX + "\" AS h FROM q_IResearcher");
            assertThat(rejected.rows()).hasSize(1);
            // The quarantined value stays numeric. Which integer type DuckDB's JSON inference
            // picks is not stable across engine versions — 1.3 said BIGINT, 1.5 says HUGEINT —
            // so jsonSafe normalises both to a Long; see JsonSafeTypeTest.
            assertThat(rejected.rows().get(0)).containsExactly("Neg", -1L);
        }
    }

    @Test
    void noConstraintLeavesIntViewUnfilteredAndEmitsNoQuarantine() throws Exception {
        Path tempDir = Files.createTempDirectory("aran-m6-test-none");
        File researchers = tempDir.resolve("researchers.json").toFile();
        try (FileWriter fw = new FileWriter(researchers)) {
            fw.write("[{\"name\":\"Ada\",\"h_index\":5},{\"name\":\"Neg\",\"h_index\":-1}]");
        }
        DuckDbExecutor.SnapshotResolver resolver = ds ->
                "read_json_auto('" + researchers.getAbsolutePath() + "')";
        MappingCatalog.SourceView src = new MappingCatalog.SourceView(
                "researchers", "SELECT * FROM ${SNAPSHOT}", Map.of(NAME, "name", H_INDEX, "h_index"));
        MappingCatalog catalog = new MappingCatalog(
                Map.of("urn:IResearcher", new MappingCatalog.IntegratedClass("urn:IResearcher", List.of(src))),
                List.of(), List.of(), "v1");

        List<String> ddl = DuckDbExecutor.buildDdl(catalog, resolver, Map.of());
        assertThat(ddl).noneMatch(s -> s.contains("q_IResearcher"));

        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, Map.of())) {
            exec.prepare();
            assertThat(exec.run("SELECT * FROM int_IResearcher").rows()).hasSize(2);
        }
    }
}
