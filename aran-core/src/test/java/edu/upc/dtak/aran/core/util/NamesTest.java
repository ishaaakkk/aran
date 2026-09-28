package edu.upc.dtak.aran.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link Names} is the repo's single home for name and literal normalisation, and had no tests.
 *
 * <p>{@code sqlLiteral} matters most: it replaced eleven open-coded copies of
 * {@code replace("'", "''")} across four modules, and a missed copy there is a SQL injection
 * rather than a cosmetic inconsistency.
 */
class NamesTest {

    @Nested
    @DisplayName("sqlLiteral — escaping a value for a single-quoted literal")
    class SqlLiteral {

        @Test
        @DisplayName("doubles a quote, so the literal cannot be closed early")
        void doublesQuotes() {
            assertThat(Names.sqlLiteral("O'Brien")).isEqualTo("O''Brien");
            assertThat(Names.sqlLiteral("/data/tenant's snapshot.parquet"))
                    .isEqualTo("/data/tenant''s snapshot.parquet");
        }

        @Test
        @DisplayName("neutralises the injection an unescaped path would allow")
        void neutralisesTheInjection() {
            // The shape that motivated the helper: a filename that closes the literal and
            // appends its own SQL. After escaping there is no unpaired quote left to close it.
            String hostile = "x.parquet'); DROP TABLE snapshot; --";
            String escaped = Names.sqlLiteral(hostile);

            assertThat(escaped).isEqualTo("x.parquet''); DROP TABLE snapshot; --");

            String sql = "SELECT * FROM read_parquet('" + escaped + "')";

            // Not `doesNotContain("');")` — the escaped form still contains that substring, as
            // the second half of the doubled pair, and asserting otherwise fails against
            // correct output. What actually distinguishes safe from unsafe is that no quote is
            // left unpaired, so the literal never terminates early: the two delimiters plus one
            // doubled pair is four, and every embedded apostrophe adds two more.
            assertThat(sql.chars().filter(c -> c == '\'').count()).isEven();
            assertThat(Names.sqlLiteral("a'b'c").chars().filter(c -> c == '\'').count()).isEqualTo(4);
        }

        @Test
        @DisplayName("leaves an ordinary path untouched")
        void leavesOrdinaryPathsAlone() {
            assertThat(Names.sqlLiteral("/var/lib/aran/snap-42.parquet"))
                    .isEqualTo("/var/lib/aran/snap-42.parquet");
            assertThat(Names.sqlLiteral("")).isEmpty();
        }

        @Test
        @DisplayName("escapes a value, and does not pretend to sanitise an identifier")
        void isNotAnIdentifierSanitiser() {
            // Doubling is correct for a literal and wrong for an identifier; sqlName is the
            // other operation. Pinned so the two do not get merged by a well-meaning refactor.
            assertThat(Names.sqlLiteral("weird name")).isEqualTo("weird name");
            assertThat(Names.sqlName("http://ex.org/weird name")).isEqualTo("weird_name");
        }
    }

    @Nested
    @DisplayName("sqlIdentifier — a name as a double-quoted SQL identifier")
    class SqlIdentifier {

        @Test
        @DisplayName("quotes a reserved word, which sqlName leaves unchanged and unusable (S7-8)")
        void quotesReservedWords() {
            assertThat(Names.sqlName("http://ex.org/source/ds/order")).isEqualTo("order");
            assertThat(Names.sqlIdentifier("order")).isEqualTo("\"order\"");
        }

        @Test
        @DisplayName("doubles an embedded double quote, so the identifier cannot be closed early")
        void doublesEmbeddedQuotes() {
            assertThat(Names.sqlIdentifier("a\"b")).isEqualTo("\"a\"\"b\"");
            assertThat(Names.sqlIdentifier("x\"; DROP VIEW y; --"))
                    .isEqualTo("\"x\"\"; DROP VIEW y; --\"");
        }

        @Test
        @DisplayName("leaves a single quote alone — that is sqlLiteral's job, not this one's")
        void isNotALiteralEscaper() {
            assertThat(Names.sqlIdentifier("O'Brien")).isEqualTo("\"O'Brien\"");
        }
    }

    @Nested
    @DisplayName("sqlName — IRI to SQL identifier")
    class SqlName {

        @Test
        @DisplayName("strips the namespace and replaces everything outside [a-zA-Z0-9_]")
        void stripsAndReplaces() {
            assertThat(Names.sqlName("http://example.org/onto#Paper")).isEqualTo("Paper");
            assertThat(Names.sqlName("http://example.org/onto/author-ref")).isEqualTo("author_ref");
            assertThat(Names.sqlName("urn:aran:acm papers")).isEqualTo("acm_papers");
        }
    }

    @Nested
    @DisplayName("localName — the behaviour M-7's two implementations disagreed on")
    class LocalName {

        @Test
        @DisplayName("strips after the last /, # or :")
        void stripsAllThreeSeparators() {
            assertThat(Names.localName("http://example.org/onto#Paper")).isEqualTo("Paper");
            assertThat(Names.localName("http://example.org/onto/Paper")).isEqualTo("Paper");
            assertThat(Names.localName("urn:aran:acm")).isEqualTo("acm");
        }

        @Test
        @DisplayName("the colon is the case the two copies disagreed on")
        void colonIsTheDivergence() {
            // The lastIndexOf('/')/lastIndexOf('#') variant in SourceGraphSerializer,
            // SourceGraphController and SchemaIntegrationOperator returned the whole IRI here,
            // while QueryEngineOperator and sqlName returned "acm" — the same property labelled
            // two ways in two payloads. Colon-aware wins, because sqlName already was.
            assertThat(Names.localName("urn:aran:acm_papers")).isEqualTo("acm_papers");
            assertThat(Names.sqlName("urn:aran:acm_papers")).isEqualTo("acm_papers");
        }

        @Test
        @DisplayName("a bare name and a null survive")
        void degeneratesSafely() {
            assertThat(Names.localName("Paper")).isEqualTo("Paper");
            assertThat(Names.localName(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("iriFragment — label to IRI-safe fragment")
    class IriFragment {

        @Test
        @DisplayName("keeps dot, dash and underscore; replaces the rest")
        void keepsIriSafeCharacters() {
            assertThat(Names.iriFragment("My Class")).isEqualTo("My_Class");
            assertThat(Names.iriFragment("v1.2-beta")).isEqualTo("v1.2-beta");
            assertThat(Names.iriFragment("a/b#c")).isEqualTo("a_b_c");
        }
    }
}
