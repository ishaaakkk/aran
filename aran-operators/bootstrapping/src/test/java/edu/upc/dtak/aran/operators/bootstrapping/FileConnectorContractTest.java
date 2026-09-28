package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.source.SourceConnector;
import edu.upc.dtak.aran.core.source.SourceConnectorContract;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Nested;

/**
 * The three file connectors, against the shared contract.
 *
 * <p>Each nested class is four lines. That ratio is the point of the kit: a connector's own tests
 * cover what is peculiar to its source — delimiter sniffing, nested-object flattening, parquet
 * logical types — and everything the rest of Aran relies on is asserted here without anyone
 * writing it again, or forgetting to.
 */
class FileConnectorContractTest {

    @Nested
    class Csv extends SourceConnectorContract {
        @Override protected SourceConnector connector() {
            return new CsvConnector();
        }

        @Override protected SourceDescriptor validSource() {
            return SourceDescriptor.ofFile("d1", "venues", "snap-1", "text/csv", "venues.csv", null,
                    "id,name,city\n1,Palau,Barcelona\n2,Liceu,Barcelona\n"
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nested
    class Parquet extends SourceConnectorContract {
        @Override protected SourceConnector connector() {
            return new ParquetConnector();
        }

        @Override protected SourceDescriptor validSource() {
            return SourceDescriptor.ofFile("d3", "people", "snap-3",
                    "application/vnd.apache.parquet", "people.parquet", null, sampleParquet());
        }

        /** Written by DuckDB in-process — the same trick ParquetBootstrapTest uses. */
        private static byte[] sampleParquet() {
            try {
                Class.forName("org.duckdb.DuckDBDriver");
                Path tmp = Files.createTempFile("aran-contract-", ".parquet");
                try {
                    String path = tmp.toAbsolutePath().toString().replace("'", "''");
                    try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
                         Statement stmt = conn.createStatement()) {
                        stmt.execute("COPY (SELECT * FROM (VALUES (1, 'ada', 4.5), (2, 'grace', 9.1))"
                                + " AS t(id, name, score)) TO '" + path + "' (FORMAT PARQUET)");
                    }
                    return Files.readAllBytes(tmp);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (Exception e) {
                throw new IllegalStateException("could not build the parquet fixture", e);
            }
        }
    }

    @Nested
    class Json extends SourceConnectorContract {
        @Override protected SourceConnector connector() {
            return new JsonConnector();
        }
        /** The one document kind: its wrapper walks the iterator, every other kind's is flat. */
        @Override protected String expectedShape() {
            return "DOCUMENT";
        }

        @Override protected SourceDescriptor validSource() {
            return SourceDescriptor.ofFile("d2", "authors", "snap-2", "application/json",
                    "authors.json", null,
                    "[{\"id\":1,\"name\":\"Ada\"},{\"id\":2,\"name\":\"Grace\"}]"
                            .getBytes(StandardCharsets.UTF_8));
        }
    }
}
