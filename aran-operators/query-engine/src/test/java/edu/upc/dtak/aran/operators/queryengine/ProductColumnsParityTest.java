package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import edu.upc.dtak.aran.operators.queryengine.plan.OqlPlanner;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanExecutor;
import edu.upc.dtak.aran.operators.queryengine.plan.ProductColumns;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.calcite.rel.RelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 2's mandatory parity oracle: a VIRTUAL product's {@code output_schema} — derived from
 * planning its OQL to a {@link org.apache.calcite.rel.type.RelDataType}, no execution — must
 * describe the same table a MATERIALIZED product would, whose columns come from the real DuckDB
 * driver reading the parquet header {@code materialize-oql} writes ({@code
 * ProductSqlExecutor#describeParquet}, via {@code DataProductService.runMaterialization}).
 *
 * <p>This runs both halves for real against the identical optimized SQL: the Calcite row type on
 * one side, an actual DuckDB {@code COPY ... TO parquet} + header read on the other. It asserts
 * NAME and {@code jdbcType} equality for a representative multi-column, multi-typed OQL (VARCHAR,
 * BIGINT, DECIMAL — the widened types {@code XsdTypes} can produce for a plain projection).
 * {@code typeName} spelling is deliberately NOT asserted equal: {@link ProductColumns}' javadoc
 * documents why (DECIMAL is the one type in this set where DuckDB's driver reports precision/
 * scale and Calcite's bare enum name does not) — that is a caveat, not a defect.
 */
class ProductColumnsParityTest {

    private static final String NS = "http://example.com/";
    private static final String PAPER = NS + "Paper";
    private static final String TITLE = NS + "title";
    private static final String YEAR = NS + "year";
    private static final String RATING = NS + "rating";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private static MappingCatalog catalog() {
        Map<String, String> columnMap = Map.of(TITLE, "title", YEAR, "year", RATING, "rating");
        Map<String, String> columnTypes = Map.of(
                TITLE, XSD + "string", YEAR, XSD + "integer", RATING, XSD + "decimal");
        var paper = new MappingCatalog.IntegratedClass(PAPER, List.of(
                new MappingCatalog.SourceView("ds", "SELECT * FROM ${SNAPSHOT}",
                        columnMap, 0L, List.of(), "", columnTypes)));
        return new MappingCatalog(Map.of(PAPER, paper), List.of(), List.of(), "v1");
    }

    @Test
    @DisplayName("a virtual product's Calcite-derived columns match a materialized product's "
            + "real parquet-header columns: same names, same jdbcType, for the same OQL")
    void virtualColumnsMatchMaterializedColumns() throws Exception {
        assertParity("SELECT Paper.title, Paper.year, Paper.rating FROM Paper");
    }

    /**
     * AVG is DOUBLE in DuckDB for every numeric argument; Calcite's default type system types it
     * as the argument's own type, so a VIRTUAL product averaging an integer column declared
     * BIGINT for a column whose every value is fractional.
     */
    @Test
    @DisplayName("AVG over an integer column is DOUBLE on both paths, beside COUNT(*)")
    void aggregateColumnsMatch() throws Exception {
        assertParity("SELECT Paper.title, AVG(Paper.year) AS avg_year, COUNT(*) AS n FROM Paper "
                + "GROUP BY Paper.title");
    }

    private static void assertParity(String oql) throws Exception {
        RelNode logical = new OqlPlanner(catalog()).lower(OqlParser.parse(oql));
        RelNode optimized = PlanExecutor.optimize(logical);

        // The VIRTUAL path: rewrite-oql's exact derivation, no execution.
        List<ProductColumn> virtualColumns = ProductColumns.of(optimized.getRowType());

        // The MATERIALIZED path: the identical optimized SQL, actually run through DuckDB and
        // COPYd to parquet exactly as materialize-oql does, then described off that file's own
        // header — byte-for-byte how DataProductService.runMaterialization records outputSchema
        // today (ProductSqlExecutor.describeParquet is the shared helper both callers use).
        String sql = PlanExecutor.toSqlFromOptimized(optimized);
        Path fixture = Files.createTempFile("aran-parity-", ".json");
        Path target = Files.createTempFile("aran-parity-", ".parquet");
        Files.deleteIfExists(target);
        try {
            Files.writeString(fixture, "{\"title\": \"Guernica\", \"year\": 1937, \"rating\": 9.5}\n");
            DuckDbExecutor.SnapshotResolver resolver =
                    d -> "read_json_auto('" + fixture.toAbsolutePath() + "')";
            try (DuckDbExecutor exec = new DuckDbExecutor(catalog(), resolver)) {
                exec.onlyClassesReferencedBy(sql).prepare();
                exec.execute("COPY (" + sql + ") TO '"
                        + target.toAbsolutePath().toString().replace("'", "''")
                        + "' (FORMAT PARQUET)");
            }
            List<ProductColumn> materializedColumns = ProductSqlExecutor.describeParquet(target);

            assertThat(virtualColumns).extracting(ProductColumn::name)
                    .as("field order and names must match: %s", materializedColumns)
                    .containsExactlyElementsOf(
                            materializedColumns.stream().map(ProductColumn::name).toList());
            assertThat(virtualColumns).extracting(ProductColumn::jdbcType)
                    .as("jdbcType must match per column: %s", materializedColumns)
                    .containsExactlyElementsOf(
                            materializedColumns.stream().map(ProductColumn::jdbcType).toList());
        } finally {
            Files.deleteIfExists(fixture);
            Files.deleteIfExists(target);
        }
    }
}
