package edu.upc.dtak.aran.operators.bootstrapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.upc.dtak.aran.core.source.SourceConnectors;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks the file connectors' {@link edu.upc.dtak.aran.core.source.SourceConnector#supports}
 * detection — this is the production replacement for the former
 * {@code BootstrapEngine.detectFormat}, so it must match those cases exactly.
 */
@DisplayName("File connector detection (supports)")
class SourceConnectorDetectionTest {

    private final CsvConnector csv = new CsvConnector();
    private final JsonConnector json = new JsonConnector();
    private final ParquetConnector parquet = new ParquetConnector();

    private static SourceDescriptor of(String contentType, String filename) {
        return SourceDescriptor.ofFile("ds", "name", "snap", contentType, filename, null, new byte[0]);
    }

    @Test
    @DisplayName("content-type detection matches the old detectFormat cases")
    void by_content_type() {
        assertThat(json.supports(of("application/json", null))).isTrue();
        assertThat(csv.supports(of("text/csv", null))).isTrue();
        assertThat(csv.supports(of("text/tab-separated-values", null))).isTrue();
        assertThat(csv.supports(of("application/x-tsv", null))).isTrue();
        assertThat(parquet.supports(of("application/vnd.apache.parquet", null))).isTrue();
    }

    @Test
    @DisplayName("filename-extension fallback matches the old detectFormat cases")
    void by_extension() {
        assertThat(json.supports(of("application/octet-stream", "data.json"))).isTrue();
        assertThat(csv.supports(of("application/octet-stream", "report.csv"))).isTrue();
        assertThat(csv.supports(of("application/octet-stream", "export.tsv"))).isTrue();
        assertThat(parquet.supports(of("application/octet-stream", "export.parquet"))).isTrue();
    }

    @Test
    @DisplayName("connectors are mutually exclusive and reject opaque input")
    void mutually_exclusive_and_opaque_rejected() {
        // A CSV descriptor is claimed only by the CSV connector.
        SourceDescriptor csvDesc = of("text/csv", "report.csv");
        assertThat(csv.supports(csvDesc)).isTrue();
        assertThat(json.supports(csvDesc)).isFalse();
        assertThat(parquet.supports(csvDesc)).isFalse();

        // Opaque MIME + unknown extension: none claim it (→ UNKNOWN_FORMAT at the operator).
        SourceDescriptor opaque = of("application/octet-stream", "archive.zip");
        assertThat(csv.supports(opaque)).isFalse();
        assertThat(json.supports(opaque)).isFalse();
        assertThat(parquet.supports(opaque)).isFalse();
    }

    @Test
    @DisplayName("a materialized object's copy resolves to exactly one connector (S4-9)")
    void materialized_object_copy_resolves_to_one_connector() {
        // DatasetService.materializeObject uploads the DuckDB-produced parquet under the parquet
        // content type. What bootstrap is TOLD the file is called therefore has to describe that
        // output — because detection is N independent boolean claims, not an ordered decision, so
        // a content type and an extension naming different formats is a hard failure rather than a
        // precedence question.
        SourceConnectors connectors = new SourceConnectors(List.of(csv, json, parquet));

        assertThat(connectors.forSource(of(PARQUET_CONTENT_TYPE, "raw/people.parquet")))
                .as("the hint the fixed pipeline passes")
                .isSameAs(parquet);

        assertThatThrownBy(() -> connectors.forSource(of(PARQUET_CONTENT_TYPE, "raw/people.csv")))
                .as("the hint it used to pass: CSV claims the extension, PARQUET the content type")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous source");
    }

    private static final String PARQUET_CONTENT_TYPE = "application/vnd.apache.parquet";
}
