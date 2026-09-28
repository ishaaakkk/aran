package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.domain.SourceCatalog.SourceRef;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The naming contract behind the {@code sources} tier.
 *
 * <p>The point of this class is that a SQL author can type the name. The views it stands for are
 * {@code w_3fa85f64_5717_4562_b3fc_2c963f66afa6_acm}; what is asserted here is that nobody has to
 * know that.
 */
class SourceCatalogTest {

    private static final String DS_ACM = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
    private static final String DS_SEMANTIC = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private static String iri(String datasetId, String path) {
        return "http://www.essi.upc.edu/DTIM/NextiaDI/source/" + datasetId + "/" + path;
    }

    @Nested
    @DisplayName("the name comes from the dataset name already inside the source class IRI")
    class Derivation {

        @Test
        @DisplayName("the seminar demo's three sources name themselves")
        void seminarDemo() {
            SourceCatalog catalog = SourceCatalog.of(List.of(
                    new SourceRef(DS_ACM, iri(DS_ACM, "acm")),
                    new SourceRef(DS_SEMANTIC, iri(DS_SEMANTIC, "semantic"))));

            assertThat(catalog.tables()).extracting(SourceCatalog.SourceTable::sqlName)
                    .containsExactly("acm", "semantic");
        }

        @Test
        @DisplayName("a nested JSON class keeps its path, with dots sanitised")
        void nestedPath() {
            // JsonBootstrap mints child classes as `<parent>.<child>`, and Names.iriFragment keeps
            // the dot. It is not legal unquoted in SQL, so sqlName maps it to an underscore.
            SourceCatalog catalog = SourceCatalog.of(List.of(
                    new SourceRef(DS_ACM, iri(DS_ACM, "researchers.affiliations"))));

            assertThat(catalog.tables()).singleElement()
                    .extracting(SourceCatalog.SourceTable::sqlName)
                    .isEqualTo("researchers_affiliations");
        }

        @Test
        @DisplayName("a name that would not be a legal identifier is made into one")
        void illegalIdentifiers() {
            SourceCatalog catalog = SourceCatalog.of(List.of(
                    new SourceRef(DS_ACM, iri(DS_ACM, "2024")),
                    new SourceRef(DS_SEMANTIC, iri(DS_SEMANTIC, "---"))));

            assertThat(catalog.tables()).extracting(SourceCatalog.SourceTable::sqlName)
                    .containsExactlyInAnyOrder("s_2024", "source");
        }
    }

    @Nested
    @DisplayName("stability — the property the whole design exists for")
    class Stability {

        @Test
        @DisplayName("the supplied order does not change the names")
        void orderIndependent() {
            List<SourceRef> refs = List.of(
                    new SourceRef(DS_ACM, iri(DS_ACM, "acm")),
                    new SourceRef(DS_SEMANTIC, iri(DS_SEMANTIC, "acm")));

            List<String> forwards = SourceCatalog.of(refs).tables().stream()
                    .map(SourceCatalog.SourceTable::sqlName).toList();
            List<String> backwards = SourceCatalog.of(refs.reversed()).tables().stream()
                    .map(SourceCatalog.SourceTable::sqlName).toList();

            assertThat(forwards).isEqualTo(backwards);
        }

        @Test
        @DisplayName("adding a source does not rename an existing one")
        void addingDoesNotRename() {
            String before = SourceCatalog.of(List.of(new SourceRef(DS_ACM, iri(DS_ACM, "acm"))))
                    .tables().getFirst().sqlName();

            SourceCatalog after = SourceCatalog.of(List.of(
                    new SourceRef(DS_ACM, iri(DS_ACM, "acm")),
                    new SourceRef(DS_SEMANTIC, iri(DS_SEMANTIC, "semantic"))));

            assertThat(after.byName(before)).isPresent()
                    .get().extracting(SourceCatalog.SourceTable::datasetId).isEqualTo(DS_ACM);
        }

        @Test
        @DisplayName("re-bootstrapping after a rename is the one thing that does move a name")
        void reBootstrapAfterRenameMovesTheName() {
            // The honest boundary of the no-persistence decision, asserted rather than claimed.
            //
            // A rename alone cannot reach this catalog: the source class IRI is minted once, at
            // bootstrap, from the name the dataset had then, and nothing here reads a dataset name.
            // That is why there is no stored name table.
            //
            // Re-bootstrapping mints a new IRI from the new name, and the table name follows it.
            // The mapping is keyed on that IRI, so this is the identity moving, not a name drifting
            // away from a stable one — but a saved query does break, which is worth pinning.
            String before = SourceCatalog.of(List.of(new SourceRef(DS_ACM, iri(DS_ACM, "acm"))))
                    .tables().getFirst().sqlName();
            String after = SourceCatalog.of(List.of(new SourceRef(DS_ACM, iri(DS_ACM, "acm_2024"))))
                    .tables().getFirst().sqlName();

            assertThat(before).isEqualTo("acm");
            assertThat(after).isEqualTo("acm_2024").isNotEqualTo(before);
        }
    }

    @Nested
    @DisplayName("collisions")
    class Collisions {

        @Test
        @DisplayName("two datasets of the same name stay distinct, one taking a dataset-id suffix")
        void sameDatasetName() {
            SourceCatalog catalog = SourceCatalog.of(List.of(
                    new SourceRef(DS_ACM, iri(DS_ACM, "acm")),
                    new SourceRef(DS_SEMANTIC, iri(DS_SEMANTIC, "acm"))));

            List<String> names = catalog.tables().stream()
                    .map(SourceCatalog.SourceTable::sqlName).toList();

            assertThat(names).hasSize(2).doesNotHaveDuplicates().contains("acm");
            assertThat(names).anyMatch(n -> n.startsWith("acm_"));
        }

        @Test
        @DisplayName("the same source arriving twice is one table, not a collision with itself")
        void duplicateRefIsNotACollision() {
            SourceRef ref = new SourceRef(DS_ACM, iri(DS_ACM, "acm"));

            // One source class commonly feeds several integrated classes, so the caller may well
            // hand us the same pair more than once. That is still one wrapper view.
            assertThat(SourceCatalog.of(List.of(ref, ref)).tables())
                    .singleElement()
                    .extracting(SourceCatalog.SourceTable::sqlName).isEqualTo("acm");
        }
    }

    @Test
    @DisplayName("lookup is case-insensitive, because SQL identifiers are")
    void lookupIsCaseInsensitive() {
        SourceCatalog catalog = SourceCatalog.of(List.of(new SourceRef(DS_ACM, iri(DS_ACM, "acm"))));

        assertThat(catalog.byName("ACM")).isPresent();
        assertThat(catalog.byName("AcM")).isPresent();
        assertThat(catalog.byName("nope")).isEmpty();
    }
}
