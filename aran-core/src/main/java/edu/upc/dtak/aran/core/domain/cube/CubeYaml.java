package edu.upc.dtak.aran.core.domain.cube;

import edu.upc.dtak.aran.core.util.Names;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;

/**
 * The cube as {@code cube.yaml} — the definition in the syntax a user reads, diffs and pastes.
 *
 * <p>Kept deliberately close to Unity Catalog metric-view syntax ({@code measures:}/{@code
 * dimensions:} with {@code name}/{@code expr}), so the Databricks export is a near-identity
 * transform; hierarchies are our extension. See §4.3 of the design brainstorm.
 *
 * <p><b>Emit-only.</b> There is no parser here, and that is the v1 scope: a cube is created from a
 * validated definition, never from this text, so nothing can arrive through the YAML that the
 * validation gates did not already see. When the code-view door of §4.3 lands it needs a reader,
 * and the reader will need its own gates.
 *
 * <p>The emitter is hand-rolled rather than a YAML library because the output is a fixed shape of
 * a few dozen lines with comments in specific places, and a serializer that keeps comments is a
 * bigger dependency than the thing it would write. Ordering is definition order throughout, so
 * the same cube always emits the same bytes.
 */
@NullMarked
public final class CubeYaml {

    /** The format's own version, bumped when the shape changes — not the cube's. */
    public static final String VERSION = "0.1";

    /**
     * A scalar safe to write unquoted. Deliberately narrow: no {@code :}, {@code #}, quote or
     * bracket can appear, which is what makes quoting-by-exception correct rather than hopeful.
     */
    private static final Pattern PLAIN = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_ ().+*/-]*$");

    private CubeYaml() {
    }

    public static String emit(CubeDefinition cube) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: ").append(VERSION).append('\n');
        yaml.append("name: ").append(scalar(cube.name())).append('\n');
        if (cube.description() != null && !cube.description().isBlank()) {
            yaml.append("description: ").append(scalar(cube.description())).append('\n');
        }
        // The local name is what every consumer writes; the IRI is what it means. Both, because
        // dropping the IRI makes the YAML unresolvable against the graph it describes.
        yaml.append("fact: ").append(Names.localName(cube.factClassIri()))
                .append("  # ").append(cube.factClassIri()).append('\n');

        if (!cube.measures().isEmpty()) {
            yaml.append("measures:\n");
            for (CubeMeasure measure : cube.measures()) {
                yaml.append("  - name: ").append(scalar(measure.name())).append('\n');
                yaml.append("    expr: ").append(scalar(expressionOf(measure)));
                if (measure.additivity() == Additivity.RATIO) {
                    yaml.append("  # ratio — evaluated after aggregation, never re-aggregated");
                }
                yaml.append('\n');
                // ADDITIVE is the assumption a BI tool makes anyway, so stating it adds noise;
                // every other class is exactly the thing the reader must not miss.
                if (measure.additivity() != Additivity.ADDITIVE) {
                    yaml.append("    additivity: ").append(keyword(measure.additivity()))
                            .append('\n');
                }
            }
        }

        if (!cube.dimensions().isEmpty()) {
            yaml.append("dimensions:\n");
            for (CubeDimension dimension : cube.dimensions()) {
                yaml.append("  - name: ").append(scalar(dimension.name())).append('\n');
                yaml.append("    class: ").append(Names.localName(dimension.classIri())).append('\n');
                yaml.append("    on: ").append(Names.localName(dimension.joinPropertyIri()))
                        .append('\n');
                if (dimension.unknownMember()) {
                    yaml.append("    unknown_member: true\n");
                }
                if (!dimension.hierarchy().isEmpty()) {
                    yaml.append("    hierarchy: ").append(localFlow(dimension.hierarchy()))
                            .append('\n');
                }
            }
        }

        CubeCalendar calendar = cube.calendar();
        if (calendar != null) {
            yaml.append("calendar:\n");
            yaml.append("  from: ").append(Names.localName(calendar.fromColumnIri())).append('\n');
            yaml.append("  levels: [").append(String.join(", ", calendar.levels())).append("]\n");
        }
        return yaml.toString();
    }

    /** {@code SUM(amount)} for a column measure; the author's own text for a RATIO. */
    private static String expressionOf(CubeMeasure measure) {
        if (measure.additivity() == Additivity.RATIO) {
            return measure.expr() == null ? "" : measure.expr();
        }
        return measure.aggregator() + "(" + Names.localName(measure.columnIri()) + ")";
    }

    private static String localFlow(List<String> iris) {
        StringBuilder flow = new StringBuilder("[");
        for (int i = 0; i < iris.size(); i++) {
            if (i > 0) {
                flow.append(", ");
            }
            flow.append(Names.localName(iris.get(i)));
        }
        return flow.append(']').toString();
    }

    private static String keyword(Additivity additivity) {
        return additivity.name().toLowerCase(Locale.ROOT);
    }

    /**
     * A scalar, quoted only when it has to be. Free text reaches this — a description, a RATIO's
     * expression — and an unquoted colon or hash in one of those is the difference between a
     * document and a parse error.
     */
    private static String scalar(String value) {
        if (PLAIN.matcher(value).matches() && !value.endsWith(" ")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }
}
