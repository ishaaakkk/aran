package edu.upc.dtak.aran.operators.bootstrapping;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.domain.drift.SchemaFingerprint;
import edu.upc.dtak.aran.core.domain.drift.SourceGraphFingerprinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The drift fingerprinter reads the vocabulary this module writes, but lives in {@code aran-core}
 * — which must not depend on an operator module, so it holds its own copy of the namespace.
 *
 * <p>This module can see both, so it owns the check. Without it, changing {@link WrapperVocab#NS}
 * would leave the fingerprinter matching nothing: every SPARQL pattern would fail silently,
 * every fingerprint would come back empty, and drift detection would report "no changes" forever
 * — the worst possible failure for a safety feature, because it looks like success.
 */
class WrapperVocabMatchesCoreTest {

    @Test
    @DisplayName("the fingerprinter's namespace copy still matches the vocabulary it reads")
    void namespacesAgree() {
        assertThat(SourceGraphFingerprinter.NX_NS).isEqualTo(WrapperVocab.NS);
    }

    @Test
    @DisplayName("a real bootstrapped CSV graph fingerprints to its columns")
    void fingerprintsRealBootstrapOutput() throws Exception {
        // End-to-end over the actual bootstrapper rather than hand-built triples: this is what
        // catches a change to the shape CsvBootstrap emits, not just to the namespace string.
        byte[] csv = "title,venue,year\nA,VLDB,2020\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var graph = new CsvBootstrap().bootstrap(
                "11111111-1111-1111-1111-111111111111", "papers", "snap-1", csv);

        SchemaFingerprint fingerprint = SourceGraphFingerprinter.of(
                graph, "11111111-1111-1111-1111-111111111111");

        assertThat(fingerprint.classes()).hasSize(1);
        assertThat(fingerprint.classes().get(0).iterator()).isEqualTo("row");
        assertThat(fingerprint.classes().get(0).format()).isEqualTo("CSV");
        assertThat(fingerprint.classes().get(0).properties())
                .extracting(SchemaFingerprint.PropertyShape::sourceRef)
                .containsExactlyInAnyOrder("title", "venue", "year");
    }

    @Test
    @DisplayName("re-bootstrapping identical bytes produces an identical hash")
    void identicalBytesAreStable() {
        // The whole gate rests on this: if bootstrapping were nondeterministic, every re-bootstrap
        // would report drift against itself and the DRIFTED status would be permanent noise.
        byte[] csv = "title,venue\nA,VLDB\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String id = "22222222-2222-2222-2222-222222222222";

        String first = fingerprintOf(csv, id);
        String second = fingerprintOf(csv, id);

        assertThat(first).isEqualTo(second);
    }

    private static String fingerprintOf(byte[] csv, String datasetId) {
        try {
            return SourceGraphFingerprinter.of(
                    new CsvBootstrap().bootstrap(datasetId, "papers", "snap-1", csv),
                    datasetId).hash();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
