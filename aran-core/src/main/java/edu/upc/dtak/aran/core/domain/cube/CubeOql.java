package edu.upc.dtak.aran.core.domain.cube;

import edu.upc.dtak.aran.core.domain.cube.CubeEvidence.EvidenceEdge;
import edu.upc.dtak.aran.core.util.Names;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;

/**
 * The OQL behind a cube's star: one single-class projection per member product.
 *
 * <p>Each member is a plain projection over one integrated class — the fact's measure columns plus
 * its join keys, and each dimension's join key plus its hierarchy levels. Nothing is aggregated
 * here: the star is the *shape* a BI tool wants, and the aggregation is what the BI tool does with
 * it. That also keeps every member on the existing GAV pipeline with no new query features.
 *
 * <h2>Why names are gated instead of quoted</h2>
 *
 * <p>This generator writes every {@code Class.property} bare. OQL can now write the rest —
 * double-quoted identifiers, and keywords that are keywords only where the grammar expects one —
 * but this class is a second, hand-kept copy of the lexical rules, and teaching it quoting belongs
 * to the shared OQL writer every producer will go through (D6, after v0.1.0), not to a local
 * patch. Until then a name it cannot write bare is refused here, so cube creation fails loudly
 * instead of emitting OQL that fails <em>later</em>, inside a materialization job, as an opaque
 * product failure. The keyword half of the gate is now stricter than the parser — it refuses
 * names that would in fact parse — which is the safe direction.
 */
@NullMarked
public final class CubeOql {

    /** What OQL can express as a bare identifier. */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * Words the OQL grammar uses as keywords, all of them refused as names here. Checked
     * case-insensitively because the parser accepts keywords in any case, so {@code Order.count}
     * is no safer than {@code Order.COUNT}.
     */
    private static final Set<String> RESERVED = Set.of(
            "select", "from", "where", "and", "or", "as", "limit", "group", "order", "by",
            "having", "asc", "desc", "distinct", "is", "not", "null", "true", "false",
            "count", "sum", "avg", "min", "max");

    private CubeOql() {
    }

    /**
     * The fact member: every measured column, every join key a dimension needs, and the calendar's
     * date column.
     *
     * <p>The join keys are projected even though nobody asked for them as measures — without them
     * the fact table cannot be joined to its dimensions, which is the entire point of a star.
     */
    public static String fact(CubeDefinition cube, CubeEvidence evidence) {
        List<String> propertyIris = new ArrayList<>();
        for (CubeMeasure measure : cube.measures()) {
            // A RATIO measure has no column of its own — it is computed after aggregation from
            // the others, so it contributes nothing to project.
            if (measure.columnIri() != null) {
                propertyIris.add(measure.columnIri());
            }
        }
        for (CubeDimension dimension : cube.dimensions()) {
            propertyIris.add(edgeFor(cube, dimension, evidence).columnOn(cube.factClassIri()));
        }
        if (cube.calendar() != null) {
            propertyIris.add(cube.calendar().fromColumnIri());
        }
        return projection(cube.factClassIri(), propertyIris);
    }

    /** A dimension member: its join key, then its hierarchy levels in order. */
    public static String dimension(CubeDefinition cube, CubeDimension dimension,
                                   CubeEvidence evidence) {
        List<String> propertyIris = new ArrayList<>();
        propertyIris.add(edgeFor(cube, dimension, evidence).columnOppositeTo(cube.factClassIri()));
        propertyIris.addAll(dimension.hierarchy());
        return projection(dimension.classIri(), propertyIris);
    }

    /**
     * {@code SELECT Class.a, Class.b FROM Class} over the local names of {@code propertyIris}.
     *
     * <p>Deduplication is on the emitted local name, but only for the SAME IRI listed twice
     * (a measure column doubling as a join key). Two DIFFERENT IRIs sharing a local name are
     * rejected: OQL addresses properties by local name only, so it cannot tell them apart, and
     * silently dropping one would ship a star whose definition claims a column it does not have.
     */
    public static String projection(String classIri, List<String> propertyIris) {
        String className = identifier(classIri, "class");
        Map<String, String> columnSources = new LinkedHashMap<>();
        for (String propertyIri : propertyIris) {
            String column = identifier(propertyIri, "property");
            String existing = columnSources.putIfAbsent(column, propertyIri);
            if (existing != null && !existing.equals(propertyIri)) {
                throw new IllegalArgumentException("properties " + existing + " and " + propertyIri
                        + " share the local name '" + column
                        + "' — OQL cannot tell them apart, so this star cannot include both");
            }
        }
        Set<String> columns = columnSources.keySet();
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("cannot build OQL over " + className
                    + ": there is nothing to project — a star member needs at least one column");
        }
        StringBuilder oql = new StringBuilder("SELECT ");
        boolean first = true;
        for (String column : columns) {
            if (!first) {
                oql.append(", ");
            }
            oql.append(className).append('.').append(column);
            first = false;
        }
        return oql.append(" FROM ").append(className).toString();
    }

    private static EvidenceEdge edgeFor(CubeDefinition cube, CubeDimension dimension,
                                        CubeEvidence evidence) {
        return evidence.edgeOf(dimension.joinPropertyIri()).orElseThrow(
                () -> new IllegalArgumentException("dimension '" + dimension.name() + "': join "
                        + dimension.joinPropertyIri() + " is not a relationship of the integrated "
                        + "schema"));
    }

    /** The local name of {@code iri}, or a rejection when this generator cannot write it bare. */
    private static String identifier(String iri, String what) {
        String local = Names.localName(iri);
        if (!IDENTIFIER.matcher(local).matches()) {
            throw new IllegalArgumentException("the " + what + " " + iri + " has a local name ('"
                    + local + "') the star's OQL cannot write: it writes every projection item "
                    + "as an unquoted letters-digits-underscore identifier");
        }
        if (RESERVED.contains(local.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("the " + what + " " + iri + " has a local name ('"
                    + local + "') that is an OQL keyword — the star's OQL writes names unquoted, "
                    + "and does not yet emit OQL's quoted identifiers");
        }
        return local;
    }
}
