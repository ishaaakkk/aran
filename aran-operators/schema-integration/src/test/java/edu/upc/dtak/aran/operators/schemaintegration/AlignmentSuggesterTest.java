package edu.upc.dtak.aran.operators.schemaintegration;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.source.Harvest;
import edu.upc.dtak.aran.core.source.HarvestedColumnProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code AlignmentSuggester} decides which columns get proposed as alignments, so it
 * directly shapes the integrated schema a user ends up with — yet it had no tests at all.
 * Every method under test here is a pure function, so none of this needs a database.
 */
class AlignmentSuggesterTest {

    @Nested
    @DisplayName("jaroWinkler")
    class JaroWinkler {

        @Test
        @DisplayName("identical strings score 1.0 and disjoint strings score 0.0")
        void boundaries() {
            assertThat(AlignmentSuggester.jaroWinkler("title", "title")).isEqualTo(1.0);
            assertThat(AlignmentSuggester.jaroWinkler("abc", "xyz")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("an empty operand scores 0.0 rather than throwing")
        void emptyOperand() {
            assertThat(AlignmentSuggester.jaroWinkler("", "title")).isEqualTo(0.0);
            assertThat(AlignmentSuggester.jaroWinkler("title", "")).isEqualTo(0.0);
            assertThat(AlignmentSuggester.jaroWinkler("", "")).isEqualTo(1.0);
        }

        /**
         * Reference values from the standard Jaro-Winkler literature. These are the pairs
         * every published implementation is checked against, so they pin this hand-rolled
         * one to the actual algorithm rather than to its current behaviour.
         */
        @ParameterizedTest
        @DisplayName("matches published reference values")
        @CsvSource({
                "MARTHA,   MARHTA,   0.961",
                "DIXON,    DICKSONX, 0.813",
                "DWAYNE,   DUANE,    0.840",
                "TRATE,    TRACE,    0.906",
        })
        void referenceValues(String a, String b, double expected) {
            assertThat(AlignmentSuggester.jaroWinkler(a, b))
                    .isCloseTo(expected, within(0.001));
        }

        @Test
        @DisplayName("is symmetric")
        void symmetric() {
            assertThat(AlignmentSuggester.jaroWinkler("author_ref", "authorRef"))
                    .isCloseTo(AlignmentSuggester.jaroWinkler("authorRef", "author_ref"), within(1e-9));
        }

        @Test
        @DisplayName("always returns a value within [0,1]")
        void inRange() {
            List<String> samples = List.of("", "a", "title", "publication_year", "hIndex", "x9_-z");
            for (String a : samples) {
                for (String b : samples) {
                    assertThat(AlignmentSuggester.jaroWinkler(a, b)).isBetween(0.0, 1.0);
                }
            }
        }
    }

    @Nested
    @DisplayName("linguisticScore")
    class LinguisticScore {

        @Test
        @DisplayName("identical names score 1.0 — full similarity plus the shared-token bonus")
        void identical() {
            assertThat(AlignmentSuggester.linguisticScore("title", "title")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a shared meaningful token outscores raw string similarity alone")
        void sharedTokenBonus() {
            // Same token, different conventions: the 0.4 bonus should apply to both spellings.
            double withBonus = AlignmentSuggester.linguisticScore("publication_year", "publicationYear");
            double noBonus = AlignmentSuggester.linguisticScore("alpha", "omega");
            assertThat(withBonus).isGreaterThan(noBonus);
            assertThat(withBonus).isGreaterThanOrEqualTo(0.4);
        }

        @Test
        @DisplayName("tokens shorter than 3 chars do not earn the bonus — 'id' must not match everything")
        void shortTokensDoNotBond() {
            // Both contain the token "id", which is deliberately too short to count.
            // Without the bonus the score is 0.6 * jaroWinkler, so it stays below 0.6.
            double score = AlignmentSuggester.linguisticScore("user_id", "order_id");
            assertThat(score).isLessThan(0.6);
        }

        @Test
        @DisplayName("is symmetric and bounded")
        void symmetricAndBounded() {
            double ab = AlignmentSuggester.linguisticScore("author_ref", "authorRef");
            double ba = AlignmentSuggester.linguisticScore("authorRef", "author_ref");
            assertThat(ab).isCloseTo(ba, within(1e-9)).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("camelCase, snake_case and kebab-case tokenise equivalently")
        void tokenisationConventions() {
            double snake = AlignmentSuggester.linguisticScore("publication_year", "publicationYear");
            double kebab = AlignmentSuggester.linguisticScore("publication-year", "publicationYear");
            assertThat(snake).isCloseTo(kebab, within(1e-9));
        }
    }

    @Nested
    @DisplayName("valueOverlapScore")
    class ValueOverlap {

        @Test
        @DisplayName("identical value sets score 1.0")
        void identical() {
            List<String> v = List.of("alice", "bob", "carol");
            assertThat(AlignmentSuggester.valueOverlapScore(v, v)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("disjoint value sets score 0.0")
        void disjoint() {
            assertThat(AlignmentSuggester.valueOverlapScore(List.of("a", "b"), List.of("c", "d")))
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("an empty side scores 0.0 rather than dividing by zero")
        void emptySide() {
            assertThat(AlignmentSuggester.valueOverlapScore(List.of(), List.of("a"))).isEqualTo(0.0);
            assertThat(AlignmentSuggester.valueOverlapScore(List.of("a"), List.of())).isEqualTo(0.0);
        }

        @Test
        @DisplayName("computes Jaccard over the distinct values")
        void jaccard() {
            // {a,b,c} vs {b,c,d} -> intersection 2, union 4
            assertThat(AlignmentSuggester.valueOverlapScore(List.of("a", "b", "c"), List.of("b", "c", "d")))
                    .isCloseTo(0.5, within(1e-9));
        }

        @Test
        @DisplayName("comparison is case-insensitive and trims surrounding whitespace")
        void caseAndWhitespaceInsensitive() {
            assertThat(AlignmentSuggester.valueOverlapScore(List.of("Alice", " BOB "), List.of("alice", "bob")))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("duplicates do not inflate the score — it is set-based")
        void duplicatesIgnored() {
            assertThat(AlignmentSuggester.valueOverlapScore(
                    List.of("a", "a", "a", "b"), List.of("a", "b", "b")))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("is symmetric")
        void symmetric() {
            List<String> a = List.of("x", "y", "z");
            List<String> b = List.of("y", "z", "w");
            assertThat(AlignmentSuggester.valueOverlapScore(a, b))
                    .isCloseTo(AlignmentSuggester.valueOverlapScore(b, a), within(1e-9));
        }
    }

    @Nested
    @DisplayName("suggestedLabel")
    class SuggestedLabel {

        @Test
        @DisplayName("normalises away punctuation and case")
        void normalises() {
            assertThat(AlignmentSuggester.suggestedLabel("Publication_Year", "Publication_Year"))
                    .isEqualTo("publicationyear");
        }

        @Test
        @DisplayName("prefers the shorter of the two names as the more general label")
        void prefersShorter() {
            assertThat(AlignmentSuggester.suggestedLabel("publicationYear", "year")).isEqualTo("year");
            assertThat(AlignmentSuggester.suggestedLabel("year", "publicationYear")).isEqualTo("year");
        }

        @Test
        @DisplayName("never returns empty when at least one name has content")
        void neverEmptyWhenOneSideHasContent() {
            assertThat(AlignmentSuggester.suggestedLabel("title", "___")).isEqualTo("title");
            assertThat(AlignmentSuggester.suggestedLabel("___", "title")).isEqualTo("title");
        }
    }

    /**
     * The candidate list itself, which had no test at all — only the scoring helpers above did.
     *
     * <p>Flyway V36 (V40/V44) widened {@code column_profile}'s uniqueness key to include {@code
     * subject}, so a materialized relational dataset now carries freyja's SNAPSHOT profile AND the
     * catalog harvest's HARVESTED estimate for one physical column. Sixty per cent of the combined
     * score here is value overlap over {@code commonValues}, which a harvested row leaves empty, and
     * nothing deduplicates {@code (iriA, iriB)} — so one alignment came out up to four times with
     * three meaningless scores, and the {@code isEmpty()} guard did not stop it (that field asks
     * whether the COLUMN is empty; {@code HarvestedColumnProfiles} writes a flat {@code false}).
     */
    @Nested
    @DisplayName("suggest — harvested rows are estimates, not measurements")
    class SuggestWithHarvestedRows {

        private ColumnProfile measured(UUID datasetId, String column, List<String> commonValues) {
            return new ColumnProfile(
                    UUID.randomUUID(), datasetId, column, 0, "VARCHAR",
                    20, 3.0, 1.0, 0.0,
                    1, 1, 1, 0, 0,
                    0, 0, 0, 0,
                    10, 3, 6,
                    1, 1, 1, 1, 0,
                    null, null,
                    false, false,
                    commonValues, List.of());
        }

        /** The real writer, so the row's shape is whatever a harvest actually produces. */
        private ColumnProfile harvested(UUID datasetId, String table, String column) {
            return HarvestedColumnProfiles.forTable(
                            new Harvest(Map.of(table, 900L),
                                    Map.of(new Harvest.ColKey(table, column), 880L),
                                    List.of()),
                            datasetId, table)
                    .get(0);
        }

        private Dataset dataset(UUID id, String name, List<ColumnProfile> columns) {
            return new Dataset(id, UUID.randomUUID(), name, "", "snap", columns);
        }

        @Test
        @DisplayName("one measured pair yields exactly one candidate, keeping the value-overlap score")
        void coexistingHarvestedRowsDoNotDuplicateTheCandidate() {
            UUID idA = UUID.randomUUID();
            UUID idB = UUID.randomUUID();
            // Harvested first on both sides — the order a re-harvest after profiling produces.
            Dataset dsA = dataset(idA, "papers", List.of(
                    harvested(idA, "papers", "author_id"),
                    measured(idA, "author_id", List.of("alice", "bob"))));
            Dataset dsB = dataset(idB, "authors", List.of(
                    harvested(idB, "authors", "author_id"),
                    measured(idB, "author_id", List.of("alice", "bob"))));

            List<AlignmentSuggester.Candidate> candidates = AlignmentSuggester.suggest(
                    dsA, dsB,
                    Map.of("author_id", "urn:A/author_id"),
                    Map.of("author_id", "urn:B/author_id"),
                    0.1);

            assertThat(candidates)
                    .as("four candidates for one pair before the subject filter")
                    .hasSize(1);
            assertThat(candidates.get(0).iriA()).isEqualTo("urn:A/author_id");
            assertThat(candidates.get(0).iriB()).isEqualTo("urn:B/author_id");
            assertThat(candidates.get(0).valueScore())
                    .as("the surviving candidate is the measured one — identical value sets overlap fully")
                    .isEqualTo(1.0);
            assertThat(candidates.get(0).score()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a column with only a harvested row suggests nothing, however similar the names")
        void harvestedOnlyColumnsSuggestNothing() {
            // A federated dataset has no snapshot to profile, so a harvest is all it ever carries.
            // Identical names alone score 0.4 — above a plausible threshold — for two columns whose
            // contents were never compared. That is a suggestion made up rather than measured.
            UUID idA = UUID.randomUUID();
            UUID idB = UUID.randomUUID();
            Dataset dsA = dataset(idA, "papers", List.of(harvested(idA, "papers", "author_id")));
            Dataset dsB = dataset(idB, "authors", List.of(harvested(idB, "authors", "author_id")));

            assertThat(AlignmentSuggester.suggest(
                    dsA, dsB,
                    Map.of("author_id", "urn:A/author_id"),
                    Map.of("author_id", "urn:B/author_id"),
                    0.3)).isEmpty();
        }
    }
}
