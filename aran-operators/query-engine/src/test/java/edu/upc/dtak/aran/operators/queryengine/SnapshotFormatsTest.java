package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place a snapshot's format decides how it is read.
 *
 * <p>Written because there were three such places and they had already drifted — see the class
 * Javadoc. These tests exist less to check a switch statement than to make the next divergence
 * fail rather than ship.
 */
class SnapshotFormatsTest {

    @Test
    @DisplayName("TSV reads as delimited text, not as JSON — the drift this fold fixes")
    void tsv_reads_as_csv() {
        // SnapshotCache said read_csv_auto; both resolvers said read_json_auto. A TSV snapshot was
        // parsed one way when the cache had it and another way when it did not, so the same
        // dataset had two schemas depending on cache state.
        assertThat(SnapshotFormats.readerFor("TSV")).isEqualTo("read_csv_auto");
        assertThat(SnapshotFormats.fileSuffixFor("TSV")).isEqualTo(".tsv");
    }

    @Test
    @DisplayName("the known formats map to their readers")
    void known_formats() {
        assertThat(SnapshotFormats.readerFor("CSV")).isEqualTo("read_csv_auto");
        assertThat(SnapshotFormats.readerFor("PARQUET")).isEqualTo("read_parquet");
        assertThat(SnapshotFormats.readerFor("JSON")).isEqualTo("read_json_auto");
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "Csv", "cSV", "parquet", "Parquet", "tsv"})
    @DisplayName("format matching is case-insensitive, because the lake's content types are not normalised")
    void case_insensitive(String format) {
        assertThat(SnapshotFormats.readerFor(format))
                .isEqualTo(SnapshotFormats.readerFor(format.toUpperCase()));
    }

    @Test
    @DisplayName("an absent or unknown format degrades to a parse attempt, never to a failure")
    void unknown_format_falls_back() {
        // The lake records format from the upload's content type, which is absent often enough
        // that throwing here would break datasets that read perfectly well.
        assertThat(SnapshotFormats.readerFor(null)).isEqualTo("read_json_auto");
        assertThat(SnapshotFormats.readerFor("")).isEqualTo("read_json_auto");
        assertThat(SnapshotFormats.readerFor("application/octet-stream")).isEqualTo("read_json_auto");
        assertThat(SnapshotFormats.fileSuffixFor(null)).isEqualTo(".json");
    }

    @Test
    @DisplayName("reader and file suffix agree about what a format is, for every format")
    void reader_and_suffix_cannot_disagree() {
        // The pairing is the invariant: a file written with one format's suffix and read with
        // another's reader is the bug that keeping these apart produces.
        record Pair(String reader, String suffix) {}
        for (String fmt : new String[] {"CSV", "TSV", "PARQUET", "JSON", "", "nonsense"}) {
            Pair p = new Pair(SnapshotFormats.readerFor(fmt), SnapshotFormats.fileSuffixFor(fmt));
            boolean delimited = p.reader().equals("read_csv_auto");
            boolean parquet = p.reader().equals("read_parquet");
            if (delimited) {
                assertThat(p.suffix()).as("delimited format %s", fmt).isIn(".csv", ".tsv");
            } else if (parquet) {
                assertThat(p.suffix()).as("parquet format %s", fmt).isEqualTo(".parquet");
            } else {
                assertThat(p.suffix()).as("json fallback for %s", fmt).isEqualTo(".json");
            }
        }
    }

    @Test
    @DisplayName("a location's quotes are escaped, so a path cannot close the string literal")
    void read_expression_escapes_quotes() {
        assertThat(SnapshotFormats.readExpression("PARQUET", "/tmp/it's here.parquet"))
                .isEqualTo("read_parquet('/tmp/it''s here.parquet')");
    }

    @Test
    @DisplayName("read expressions work over URIs as well as paths")
    void read_expression_over_uri() {
        assertThat(SnapshotFormats.readExpression("CSV", "snapshot://abc-123"))
                .isEqualTo("read_csv_auto('snapshot://abc-123')");
    }
}
