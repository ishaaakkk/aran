package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKey;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.ReferentialEdge;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Phase 4B Task 3: {@link DeclaredEdgeResolver} maps a harvested {@link DeclaredForeignKey}
 * (physical table/column identity, dataset-id endpoints) into a {@link ReferentialEdge} tagged
 * {@link Tier#DECLARED} on the INTEGRATED classes/properties a catalog build works in — the exact
 * source→integrated resolution phase 3B established, reused rather than re-derived.
 */
class DeclaredEdgeResolverTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String INTEGRATED_RESOURCE = NS + "IntegratedResource";

    private static final UUID EMPLOYEES_DATASET = UUID.randomUUID();
    private static final UUID DEPARTMENTS_DATASET = UUID.randomUUID();

    private static final String EMPLOYEES_SRC_CLASS = "http://ex/source/ds1/Employees";
    private static final String DEPARTMENTS_SRC_CLASS = "http://ex/source/ds2/Departments";
    private static final String EMPLOYEES_INT_CLASS = "http://ex/integration/Employee";
    private static final String DEPARTMENTS_INT_CLASS = "http://ex/integration/Department";

    /** Marks {@code iri} as an nx:IntegratedResource — the same guard QUERY 1 restricts on. */
    private static Resource integrated(Model m, String iri) {
        Resource r = m.createResource(iri);
        r.addProperty(RDF.type, m.createResource(INTEGRATED_RESOURCE));
        return r;
    }

    /** A schema with two source classes, each subClassOf a distinct integrated class. */
    private static Model baseSchema() {
        Model m = ModelFactory.createDefaultModel();
        Resource empSrc = m.createResource(EMPLOYEES_SRC_CLASS);
        Resource empInt = integrated(m, EMPLOYEES_INT_CLASS);
        empInt.addProperty(RDF.type, RDFS.Class);
        m.add(empSrc, RDFS.subClassOf, empInt);

        Resource deptSrc = m.createResource(DEPARTMENTS_SRC_CLASS);
        Resource deptInt = integrated(m, DEPARTMENTS_INT_CLASS);
        deptInt.addProperty(RDF.type, RDFS.Class);
        m.add(deptSrc, RDFS.subClassOf, deptInt);
        return m;
    }

    private static Map<String, String> srcClassByDatasetId() {
        return Map.of(
                EMPLOYEES_DATASET.toString(), EMPLOYEES_SRC_CLASS,
                DEPARTMENTS_DATASET.toString(), DEPARTMENTS_SRC_CLASS);
    }

    private static DeclaredForeignKey fk(UUID fromDs, List<String> fromCols, UUID toDs, List<String> toCols) {
        return new DeclaredForeignKey(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                fromDs, fromCols, toDs, toCols);
    }

    private static List<ILoggingEvent> capturingWarnings(Runnable body) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeclaredEdgeResolver.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            body.run();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
        return captured.list;
    }

    // ── the happy path ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a fully-resolving harvested FK becomes a DECLARED edge on the integrated classes/columns")
    void resolvesToADeclaredEdge() {
        Model active = baseSchema();
        // nx:sourceRef facts: raw physical column -> source property IRI, unaligned (no
        // subPropertyOf), so resolveTargetProperty's COALESCE falls back to the source property.
        Map<String, Map<String, String>> rawToSrcProp = Map.of(
                EMPLOYEES_SRC_CLASS, Map.of("dept_id", "http://ex/source/ds1/deptId"),
                DEPARTMENTS_SRC_CLASS, Map.of("id", "http://ex/source/ds2/id"));

        List<ReferentialEdge> edges = DeclaredEdgeResolver.resolve(active,
                List.of(fk(EMPLOYEES_DATASET, List.of("dept_id"), DEPARTMENTS_DATASET, List.of("id"))),
                srcClassByDatasetId(), rawToSrcProp);

        assertThat(edges).singleElement().satisfies(edge -> {
            assertThat(edge.tier()).isEqualTo(Tier.DECLARED);
            assertThat(edge.leftClass()).isEqualTo(EMPLOYEES_INT_CLASS);
            assertThat(edge.rightClass()).isEqualTo(DEPARTMENTS_INT_CLASS);
            assertThat(edge.leftCols()).containsExactly("http://ex/source/ds1/deptId");
            assertThat(edge.rightCols()).containsExactly("http://ex/source/ds2/id");
        });
    }

    @Test
    @DisplayName("an aligned column resolves to its INTEGRATED property IRI, not the source one")
    void resolvesAlignedColumnToIntegratedProperty() {
        Model active = baseSchema();
        String srcProp = "http://ex/source/ds2/id";
        String integratedProp = "http://ex/integration/id";
        active.add(active.createResource(srcProp), RDFS.subPropertyOf, integrated(active, integratedProp));

        Map<String, Map<String, String>> rawToSrcProp = Map.of(
                EMPLOYEES_SRC_CLASS, Map.of("dept_id", "http://ex/source/ds1/deptId"),
                DEPARTMENTS_SRC_CLASS, Map.of("id", srcProp));

        List<ReferentialEdge> edges = DeclaredEdgeResolver.resolve(active,
                List.of(fk(EMPLOYEES_DATASET, List.of("dept_id"), DEPARTMENTS_DATASET, List.of("id"))),
                srcClassByDatasetId(), rawToSrcProp);

        assertThat(edges).singleElement()
                .extracting(ReferentialEdge::rightCols)
                .isEqualTo(List.of(integratedProp));
    }

    @Test
    @DisplayName("a composite key preserves ordinal column pairing on both sides")
    void compositeKeyPreservesOrdinalPairing() {
        Model active = baseSchema();
        Map<String, Map<String, String>> rawToSrcProp = Map.of(
                EMPLOYEES_SRC_CLASS, Map.of(
                        "pz", "http://ex/source/ds1/pz",
                        "pa", "http://ex/source/ds1/pa"),
                DEPARTMENTS_SRC_CLASS, Map.of(
                        "z", "http://ex/source/ds2/z",
                        "a", "http://ex/source/ds2/a"));

        List<ReferentialEdge> edges = DeclaredEdgeResolver.resolve(active,
                List.of(fk(EMPLOYEES_DATASET, List.of("pz", "pa"), DEPARTMENTS_DATASET, List.of("z", "a"))),
                srcClassByDatasetId(), rawToSrcProp);

        assertThat(edges).singleElement().satisfies(edge -> {
            assertThat(edge.leftCols()).containsExactly("http://ex/source/ds1/pz", "http://ex/source/ds1/pa");
            assertThat(edge.rightCols()).containsExactly("http://ex/source/ds2/z", "http://ex/source/ds2/a");
        });
    }

    // ── drop-on-unresolved, each independently ─────────────────────────────────

    @Test
    @DisplayName("a dataset with no wrapping source class in the active schema drops the edge, WARN logged")
    void dropsWhenDatasetHasNoSourceClass() {
        Model active = baseSchema();
        UUID unknownDataset = UUID.randomUUID();
        Map<String, Map<String, String>> rawToSrcProp =
                Map.of(DEPARTMENTS_SRC_CLASS, Map.of("id", "http://ex/source/ds2/id"));

        List<ILoggingEvent> warnings = capturingWarnings(() -> {
            List<ReferentialEdge> edges = DeclaredEdgeResolver.resolve(active,
                    List.of(fk(unknownDataset, List.of("dept_id"), DEPARTMENTS_DATASET, List.of("id"))),
                    srcClassByDatasetId(), rawToSrcProp);
            assertThat(edges).as("no source class -> can't place it -> dropped").isEmpty();
        });

        assertThat(warnings).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains(unknownDataset.toString());
        });
    }

    @Test
    @DisplayName("a source class not subClassOf any integrated class drops the edge, WARN logged")
    void dropsWhenSourceClassNotIntegrated() {
        Model active = ModelFactory.createDefaultModel();
        // Employees source class staged, but deliberately NOT subClassOf anything.
        active.createResource(EMPLOYEES_SRC_CLASS);
        Resource deptSrc = active.createResource(DEPARTMENTS_SRC_CLASS);
        Resource deptInt = integrated(active, DEPARTMENTS_INT_CLASS);
        active.add(deptSrc, RDFS.subClassOf, deptInt);

        Map<String, Map<String, String>> rawToSrcProp = Map.of(
                EMPLOYEES_SRC_CLASS, Map.of("dept_id", "http://ex/source/ds1/deptId"),
                DEPARTMENTS_SRC_CLASS, Map.of("id", "http://ex/source/ds2/id"));

        List<ILoggingEvent> warnings = capturingWarnings(() -> {
            List<ReferentialEdge> edges = DeclaredEdgeResolver.resolve(active,
                    List.of(fk(EMPLOYEES_DATASET, List.of("dept_id"), DEPARTMENTS_DATASET, List.of("id"))),
                    srcClassByDatasetId(), rawToSrcProp);
            assertThat(edges).as("unintegrated source class -> can't place it -> dropped").isEmpty();
        });

        assertThat(warnings).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains(EMPLOYEES_SRC_CLASS);
        });
    }

    @Test
    @DisplayName("a harvested column with no nx:sourceRef fact drops the edge, WARN logged")
    void dropsWhenColumnHasNoSourceRef() {
        Model active = baseSchema();
        // No entry at all for "dept_id" on the employees source class.
        Map<String, Map<String, String>> rawToSrcProp = Map.of(
                EMPLOYEES_SRC_CLASS, Map.of(),
                DEPARTMENTS_SRC_CLASS, Map.of("id", "http://ex/source/ds2/id"));

        List<ILoggingEvent> warnings = capturingWarnings(() -> {
            List<ReferentialEdge> edges = DeclaredEdgeResolver.resolve(active,
                    List.of(fk(EMPLOYEES_DATASET, List.of("dept_id"), DEPARTMENTS_DATASET, List.of("id"))),
                    srcClassByDatasetId(), rawToSrcProp);
            assertThat(edges).as("unmapped column -> can't place it -> dropped").isEmpty();
        });

        assertThat(warnings).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("dept_id");
        });
    }

    @Test
    @DisplayName("one bad FK among several drops only that one — the rest still resolve")
    void oneUnresolvedFkDoesNotPoisonTheRest() {
        Model active = baseSchema();
        Map<String, Map<String, String>> rawToSrcProp = Map.of(
                EMPLOYEES_SRC_CLASS, Map.of("dept_id", "http://ex/source/ds1/deptId"),
                DEPARTMENTS_SRC_CLASS, Map.of("id", "http://ex/source/ds2/id"));

        DeclaredForeignKey good =
                fk(EMPLOYEES_DATASET, List.of("dept_id"), DEPARTMENTS_DATASET, List.of("id"));
        DeclaredForeignKey bad =
                fk(UUID.randomUUID(), List.of("x"), DEPARTMENTS_DATASET, List.of("id"));

        List<ReferentialEdge> edges = DeclaredEdgeResolver.resolve(active,
                List.of(bad, good), srcClassByDatasetId(), rawToSrcProp);

        assertThat(edges).singleElement()
                .extracting(ReferentialEdge::leftClass).isEqualTo(EMPLOYEES_INT_CLASS);
    }

    @Test
    @DisplayName("no declared foreign keys at all resolves to an empty list")
    void emptyInputYieldsEmptyOutput() {
        assertThat(DeclaredEdgeResolver.resolve(baseSchema(), List.of(), Map.of(), Map.of())).isEmpty();
    }
}
