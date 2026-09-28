package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M8 — the materialize path: {@code COPY (<final sql>) TO '<tmp>.parquet'} on the same
 * DuckDB connection that owns the prepared {@code int_} views. The fixture is larger
 * than the operator's {@code MAX_ROWS} (1000) to pin the property the materializer
 * exists for: <em>every</em> row lands in the parquet, not the workbench-capped subset.
 */
class MaterializeParquetTest {

    private static final String NAME = "http://ex/prop/name";
    private static final String IDX = "http://ex/prop/idx";

    @Test
    void copyToParquetWritesAllRowsPastTheWorkbenchCap() throws Exception {
        Path tempDir = Files.createTempDirectory("aran-m8-test");
        File data = tempDir.resolve("rows.json").toFile();
        int total = 1500; // > MAX_ROWS = 1000
        try (FileWriter fw = new FileWriter(data)) {
            fw.write("[");
            for (int i = 0; i < total; i++) {
                if (i > 0) fw.write(",");
                fw.write("{\"name\":\"row" + i + "\",\"idx\":" + i + "}");
            }
            fw.write("]");
        }

        DuckDbExecutor.SnapshotResolver resolver = ds ->
                "read_json_auto('" + data.getAbsolutePath() + "')";
        MappingCatalog.SourceView src = new MappingCatalog.SourceView(
                "rows", "SELECT * FROM ${SNAPSHOT}", Map.of(NAME, "name", IDX, "idx"));
        MappingCatalog catalog = new MappingCatalog(
                Map.of("urn:IRow", new MappingCatalog.IntegratedClass("urn:IRow", List.of(src))),
                List.of(), List.of(), "v1");

        Path parquet = tempDir.resolve("product.parquet");
        String target = parquet.toAbsolutePath().toString().replace("'", "''");
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver)) {
            exec.prepare();
            exec.execute("COPY (SELECT * FROM int_IRow) TO '" + target + "' (FORMAT PARQUET)");

            DuckDbExecutor.QueryResult count =
                    exec.run("SELECT count(*) FROM read_parquet('" + target + "')");
            // COUNT(*) is a BIGINT and now arrives as a Long rather than its toString().
            assertThat(count.rows().get(0).get(0)).isEqualTo((long) total);
        }

        assertThat(Files.size(parquet)).isPositive();
        Files.deleteIfExists(parquet);
    }
}
