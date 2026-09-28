package edu.upc.dtak.aran.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SourceConnectors registry")
class SourceConnectorsTest {

    /** A fake connector that claims descriptors whose contentType contains its kind. */
    private static SourceConnector fake(String kind) {
        return new SourceConnector() {
            @Override public String sourceKind() { return kind; }
            @Override public boolean supports(SourceDescriptor s) {
                return s.contentType() != null && s.contentType().contains(kind);
            }
            @Override public SourceSchema introspect(SourceDescriptor s) {
                return new SourceSchema(ModelFactory.createDefaultModel());
            }
        };
    }

    private static SourceDescriptor desc(String contentType, String explicitKind) {
        return SourceDescriptor.ofFile("ds", "name", "snap", contentType, null, explicitKind, new byte[0]);
    }

    @Test
    @DisplayName("routes by supports() when no explicit kind is given")
    void routes_by_supports() {
        var reg = new SourceConnectors(List.of(fake("csv"), fake("json")));
        assertThat(reg.forSource(desc("text/csv", null)).sourceKind()).isEqualTo("csv");
        assertThat(reg.forSource(desc("application/json", null)).sourceKind()).isEqualTo("json");
    }

    @Test
    @DisplayName("explicit kind wins over content-type detection (case-insensitive)")
    void explicit_kind_wins() {
        var reg = new SourceConnectors(List.of(fake("csv"), fake("json")));
        // contentType says csv, but the caller forces json
        assertThat(reg.forSource(desc("text/csv", "JSON")).sourceKind()).isEqualTo("json");
    }

    @Test
    @DisplayName("unknown source throws UnsupportedSourceException")
    void unknown_throws() {
        var reg = new SourceConnectors(List.of(fake("csv")));
        assertThatThrownBy(() -> reg.forSource(desc("application/octet-stream", null)))
                .isInstanceOf(UnsupportedSourceException.class);
    }

    @Test
    @DisplayName("explicit kind that names no connector throws")
    void explicit_unknown_throws() {
        var reg = new SourceConnectors(List.of(fake("csv")));
        assertThatThrownBy(() -> reg.forSource(desc("text/csv", "parquet")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ambiguous match (two connectors claim one descriptor) throws")
    void ambiguous_throws() {
        // Both fakes claim a descriptor whose contentType contains both keys.
        var reg = new SourceConnectors(List.of(fake("a"), fake("b")));
        assertThatThrownBy(() -> reg.forSource(desc("type/a-b", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous");
    }

    @Test
    @DisplayName("duplicate source kinds fail fast at construction")
    void duplicate_kind_fails_fast() {
        assertThatThrownBy(() -> new SourceConnectors(List.of(fake("csv"), fake("csv"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }
}
