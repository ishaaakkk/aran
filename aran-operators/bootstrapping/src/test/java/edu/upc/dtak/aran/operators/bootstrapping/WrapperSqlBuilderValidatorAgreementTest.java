package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.WrapperSqlValidator;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The product had two definitions of a valid wrapper — what {@link WrapperSqlBuilder} emits and
 * what {@link WrapperSqlValidator} admits — and nothing ran one through the other, so every
 * comma-lateral wrapper bootstrap wrote for a nested-JSON class was refused by the Wrapper tab's
 * preview, save and verify while executing fine everywhere else (S4-3).
 *
 * <p>This test exists because only this module sees both sides: the builder is package-private
 * here, the validator lives in aran-core. A per-module test cannot close the gap by construction.
 */
class WrapperSqlBuilderValidatorAgreementTest {

    private final CsvBootstrap csvEngine = new CsvBootstrap();
    private final JsonBootstrap jsonEngine = new JsonBootstrap();

    @Test
    @DisplayName("flat CSV wrappers are accepted by the validator")
    void flatWrappersValidate() throws Exception {
        String csv = "title,web_url\nSunflowers,http://x/1\n";
        List<String> wrappers = wrappersFor(csvEngine.bootstrap("ds1", "cmoa", "snap1", csv.getBytes()));

        assertThat(wrappers).hasSize(1);
        assertEveryWrapperValidates(wrappers);
    }

    @Test
    @DisplayName("struct-access wrappers (nested JSON object) are accepted by the validator")
    void structAccessWrappersValidate() throws Exception {
        String json = "[{\"title\":\"A\",\"creator\":{\"fullName\":\"X\",\"nationality\":\"Y\"}}]";
        List<String> wrappers = wrappersFor(jsonEngine.bootstrap("ds2", "cmoa", "snap2", json.getBytes()));

        assertThat(wrappers).hasSize(2);
        assertThat(wrappers).anyMatch(s -> s.contains("s.\"creator\".\"fullName\""));
        assertEveryWrapperValidates(wrappers);
    }

    @Test
    @DisplayName("single-UNNEST and chained-UNNEST wrappers are accepted by the validator")
    void unnestWrappersValidate() throws Exception {
        String json = "{\"artworks\":[{\"title\":\"A\",\"creators\":[{\"name\":\"x\"}]}]}";
        List<String> wrappers = wrappersFor(jsonEngine.bootstrap("ds3", "museum", "snap3", json.getBytes()));

        // The two shapes this test is really about — assert they were actually generated, so a
        // builder change that stops emitting them cannot turn this into a vacuous pass.
        assertThat(wrappers).anyMatch(s -> s.contains("UNNEST(s.\"artworks\")"));
        assertThat(wrappers).anyMatch(s ->
                s.contains("UNNEST(s.\"artworks\")") && s.contains("UNNEST(_r1.\"creators\")"));
        assertEveryWrapperValidates(wrappers);
    }

    @Test
    @DisplayName("an array-of-primitives wrapper (UNNEST off the row expression) validates")
    void primitiveArrayWrapperValidates() throws Exception {
        String json = "[{\"title\":\"A\",\"tags\":[\"x\",\"y\"]}]";
        List<String> wrappers = wrappersFor(jsonEngine.bootstrap("ds4", "cmoa", "snap4", json.getBytes()));

        assertThat(wrappers).anyMatch(s -> s.contains("UNNEST(s.\"tags\")"));
        assertEveryWrapperValidates(wrappers);
    }

    @Test
    @DisplayName("the allowance is only for benign laterals: a real join, a second snapshot and a "
            + "comma-joined reader are still refused")
    void nonBenignShapesAreStillRefused() {
        List<String> refused = List.of(
                "SELECT * FROM ${SNAPSHOT} s JOIN other_table t ON s.id = t.id",
                "SELECT * FROM ${SNAPSHOT} s, ${SNAPSHOT} t",
                "SELECT * FROM ${SNAPSHOT} s, read_csv('/etc/passwd')",
                "SELECT * FROM ${SNAPSHOT} s, http_get('http://169.254.169.254/')");

        for (String sql : refused) {
            assertThatThrownBy(() -> WrapperSqlValidator.validate(sql))
                    .as("must refuse: %s", sql)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static void assertEveryWrapperValidates(List<String> wrappers) {
        for (String sql : wrappers) {
            assertThatCode(() -> WrapperSqlValidator.validate(sql))
                    .as("generated wrapper must pass the validator that guards preview/save/verify: %s", sql)
                    .doesNotThrowAnyException();
        }
    }

    private static List<String> wrappersFor(Model model) {
        WrapperGenerator.attachPerClassWrappers(model);
        List<String> out = new ArrayList<>();
        StmtIterator it = model.listStatements(
                null, WrapperVocab.WRAPPER_SQL, (org.apache.jena.rdf.model.RDFNode) null);
        while (it.hasNext()) {
            Statement s = it.next();
            out.add(s.getObject().asLiteral().getString());
        }
        return out;
    }
}
