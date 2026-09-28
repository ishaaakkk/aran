package edu.upc.dtak.aran.core.domain.cube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What the platform already knows about the integrated schema, as the cube layer needs it:
 * classes with their properties and profile evidence, and the referential edges between them with
 * their trust tiers.
 *
 * <p>This is the parsed form of the query engine's {@code cube-evidence} payload. It lives in core
 * because the seam is stringly typed by construction — the query-engine module is not on core's
 * compile path, so the operator hands over JSON and this is the one place that reads it. Every
 * validation gate in {@code CubeService} is expressed against these records rather than against
 * {@link JsonNode}, so a shape change breaks here once instead of at each gate.
 *
 * <p>Parsing is tolerant of missing fields and never of a missing identity: a class without an
 * {@code iri} or an edge without a {@code propertyIri} is dropped, because a nameless entry
 * cannot be referenced by a definition and would only widen a lookup into a wrong answer.
 */
@NullMarked
public record CubeEvidence(List<EvidenceClass> classes, List<EvidenceEdge> edges) {

    /**
     * One property of an integrated class.
     *
     * @param numeric     whether the planner's widened type is a number — the gate a measure's
     *                    column must pass
     * @param ndv         distinct values, when a profile knows; {@code null} for an unprofiled
     *                    property (a federated source with only harvested evidence, say)
     * @param nullFraction the fraction of NULLs, when known — what makes a dimension's "unknown
     *                    member" offer honest rather than speculative
     */
    public record EvidenceProperty(
            String iri,
            String name,
            @Nullable String xsd,
            boolean numeric,
            @Nullable Long ndv,
            @Nullable Double nullFraction
    ) {}

    /** @param rowCount rows in the class, when known; {@code null} for an unprofiled class */
    public record EvidenceClass(
            String iri,
            String name,
            @Nullable Long rowCount,
            List<EvidenceProperty> properties
    ) {

        public Optional<EvidenceProperty> property(String iri) {
            return properties.stream().filter(p -> p.iri().equals(iri)).findFirst();
        }
    }

    /**
     * One accepted join, as the planner sees it.
     *
     * @param tier {@code HYPOTHESIS}, {@code DECLARED} or {@code VERIFIED}. Left as the string the
     *             engine sent rather than mirrored into a core enum: the tiers are the query
     *             engine's, a fourth one appearing there must not fail to parse here, and the only
     *             thing the cube layer does with it is refuse {@code HYPOTHESIS}
     */
    public record EvidenceEdge(
            String propertyIri,
            String propertyName,
            String leftClassIri,
            String rightClassIri,
            String leftColIri,
            String rightColIri,
            String tier
    ) {

        /** The tier the join gate refuses unless the user insists. */
        public static final String HYPOTHESIS = "HYPOTHESIS";

        /**
         * Whether traversing this edge <em>from</em> {@code classIri} is provably to-one.
         *
         * <p>Two independent conditions, and S17-1 was checking only the first. The tier says how
         * much the platform trusts the edge; the <em>orientation</em> says which way the fan-out
         * runs. An edge is FK → PK — {@code leftClassIri} holds the foreign key, {@code
         * rightClassIri} is the referenced side — so it is to-one read left-to-right and to-many
         * read right-to-left. A DECLARED edge traversed from its referenced end is exactly as
         * multiplying as a HYPOTHESIS one, and a cube that slices a fact by such a dimension
         * double-counts every measure.
         */
        public boolean provablyToOneFrom(String classIri) {
            return !HYPOTHESIS.equals(tier) && leftClassIri.equals(classIri);
        }

        public boolean touches(String classIri) {
            return leftClassIri.equals(classIri) || rightClassIri.equals(classIri);
        }

        /**
         * The class at the other end of this edge from {@code classIri}.
         *
         * <p>A self-join answers with the same class, which is correct: both ends are it.
         */
        public String otherEnd(String classIri) {
            return leftClassIri.equals(classIri) ? rightClassIri : leftClassIri;
        }

        /** The join key column on {@code classIri}'s side of this edge. */
        public String columnOn(String classIri) {
            return leftClassIri.equals(classIri) ? leftColIri : rightColIri;
        }

        /**
         * The join key column at the other end from {@code classIri}.
         *
         * <p>Not {@code columnOn(otherEnd(classIri))}: for a self-join both ends carry the same
         * class IRI, and that composition would hand back the near side twice. Orientation is
         * decided once, against the class the caller anchored on.
         */
        public String columnOppositeTo(String classIri) {
            return leftClassIri.equals(classIri) ? rightColIri : leftColIri;
        }
    }

    public CubeEvidence {
        classes = classes == null ? List.of() : List.copyOf(classes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static CubeEvidence parse(String json, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(json == null ? "" : json);
        } catch (Exception e) {
            throw new IllegalStateException("cube-evidence returned unreadable JSON", e);
        }
        if (root == null || root.isMissingNode()) {
            return new CubeEvidence(List.of(), List.of());
        }

        Map<String, EvidenceClass> classes = new LinkedHashMap<>();
        for (JsonNode node : root.path("classes")) {
            String iri = text(node, "iri");
            if (iri == null) {
                continue;
            }
            List<EvidenceProperty> properties = new ArrayList<>();
            for (JsonNode prop : node.path("properties")) {
                String propIri = text(prop, "iri");
                if (propIri == null) {
                    continue;
                }
                properties.add(new EvidenceProperty(
                        propIri,
                        nameOr(prop, propIri),
                        text(prop, "xsd"),
                        prop.path("numeric").asBoolean(false),
                        longOrNull(prop, "ndv"),
                        doubleOrNull(prop, "nullFraction")));
            }
            classes.put(iri, new EvidenceClass(
                    iri, nameOr(node, iri), longOrNull(node, "rowCount"), List.copyOf(properties)));
        }

        List<EvidenceEdge> edges = new ArrayList<>();
        // The blank-propertyIri drop is a decision, not tolerance of sloppy input:
        // QueryEngineOperator.cubeEvidence deliberately emits propertyIri:"" for a DECLARED FK
        // with no accepted join behind it (empty rather than invented), and such an edge has no
        // OQL join step to traverse, so it could never become a dimension. Both sides are tested.
        for (JsonNode node : root.path("edges")) {
            String propertyIri = text(node, "propertyIri");
            String left = text(node, "leftClassIri");
            String right = text(node, "rightClassIri");
            String leftCol = text(node, "leftColIri");
            String rightCol = text(node, "rightColIri");
            if (propertyIri == null || left == null || right == null
                    || leftCol == null || rightCol == null) {
                continue;
            }
            // The engine names an edge "propertyName" (a class or property is "name"); read as
            // "name", every edge fell back to its full IRI in the wizard and in CubeService's 400s.
            String propertyName = text(node, "propertyName");
            edges.add(new EvidenceEdge(propertyIri, propertyName == null ? propertyIri : propertyName,
                    left, right, leftCol, rightCol, node.path("tier").asText(EvidenceEdge.HYPOTHESIS)));
        }

        return new CubeEvidence(List.copyOf(classes.values()), List.copyOf(edges));
    }

    public Optional<EvidenceClass> classOf(String iri) {
        return classes.stream().filter(c -> c.iri().equals(iri)).findFirst();
    }

    /** The edge a join property names, if the engine reported one. */
    public Optional<EvidenceEdge> edgeOf(String propertyIri) {
        return edges.stream().filter(e -> e.propertyIri().equals(propertyIri)).findFirst();
    }

    @Nullable
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("");
        return text.isBlank() ? null : text;
    }

    /** An entry's name, falling back to its IRI so a display never comes back empty. */
    private static String nameOr(JsonNode node, String iri) {
        String name = text(node, "name");
        return name == null ? iri : name;
    }

    @Nullable
    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    @Nullable
    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }
}
