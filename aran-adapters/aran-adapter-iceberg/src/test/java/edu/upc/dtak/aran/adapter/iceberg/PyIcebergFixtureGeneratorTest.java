package edu.upc.dtak.aran.adapter.iceberg;

import edu.upc.dtak.aran.core.domain.ProductColumn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Writes the table {@code aran-client/tests/test_iceberg_readback.py} reads with PyIceberg: the
 * generator's real output over a DuckDB-written parquet file (no Iceberg field ids — the case the
 * name mapping exists for), checked in so the Python side needs no JVM.
 *
 * <p>Off unless asked for, because it writes into the source tree:
 * <pre>{@code ./gradlew :aran-adapters:aran-adapter-iceberg:test --tests '*PyIcebergFixture*' \
 *     -Daran.iceberg.fixture.out=$PWD/aran-client/tests/fixtures/iceberg_product}</pre>
 * Re-run it whenever {@link IcebergProductTable} changes what it writes; the Python test then
 * reads the new bytes.
 */
@EnabledIfSystemProperty(named = "aran.iceberg.fixture.out", matches = ".+")
@DisplayName("PyIceberg read-back fixture — regenerated on demand")
class PyIcebergFixtureGeneratorTest {

    /**
     * Rooted at {@code /} on purpose: the Python test serves {@code file:///} out of its
     * {@code fixtures} directory, so the fixture carries no path from the machine that wrote it.
     */
    static final String BASE = "file:///iceberg_product";

    @Test
    @DisplayName("write the generated metadata, manifests and a DuckDB-written parquet")
    void writeFixture() throws Exception {
        Path out = Path.of(System.getProperty("aran.iceberg.fixture.out"));
        Path parquet = out.resolve("data").resolve(IcebergProductTable.DATA_FILE);
        Files.createDirectories(parquet.getParent());
        Files.deleteIfExists(parquet);
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = duck.createStatement()) {
            st.execute("SET TimeZone='UTC'");
            st.execute("""
                    COPY (SELECT * FROM (VALUES
                        (2021::BIGINT, 'Aran', DATE '2021-03-01', 1.5::DECIMAL(38,9),
                         TIMESTAMP '2021-03-01 10:00:00'),
                        (2022::BIGINT, 'Nextia', DATE '2022-07-15', 0.000000001::DECIMAL(38,9),
                         TIMESTAMP '2022-07-15 23:59:59.123456'),
                        (2023::BIGINT, NULL, NULL, NULL, NULL))
                      AS t(year, title, published, score, updated_at))
                    TO '%s' (FORMAT PARQUET)""".formatted(parquet.toAbsolutePath()));
        }

        IcebergProductTable.Artifacts artifacts = IcebergProductTable.build(
                new IcebergProductTable.ProductSpec(
                        "papers_per_year",
                        "fixture-snapshot",
                        List.of(
                                new ProductColumn("year", "BIGINT", java.sql.Types.BIGINT),
                                new ProductColumn("title", "VARCHAR", java.sql.Types.VARCHAR),
                                new ProductColumn("published", "DATE", java.sql.Types.DATE),
                                new ProductColumn("score", "DECIMAL(38,9)", java.sql.Types.DECIMAL),
                                new ProductColumn("updated_at", "TIMESTAMP",
                                        java.sql.Types.TIMESTAMP)),
                        3L,
                        Files.size(parquet),
                        1_756_400_000_000L,
                        BASE));

        Path metadataDir = out.resolve("metadata");
        if (Files.isDirectory(metadataDir)) {
            try (var stale = Files.list(metadataDir)) {
                for (Path p : stale.toList()) {
                    Files.delete(p);
                }
            }
        }
        for (Map.Entry<String, byte[]> file : artifacts.files().entrySet()) {
            Path target = out.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
    }
}
