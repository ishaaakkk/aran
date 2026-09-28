package edu.upc.dtak.aran.core.domain.cube;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The QB4OLAP projection, asserted through the Jena API rather than as serialized text: the
 * consumer is a SPARQL query, so what matters is which triples exist, not what order they were
 * written in.
 */
@DisplayName("CubeQb4olap")
class CubeQb4olapTest {

    private static final String G = "http://aran/global#";
    private static final UUID CUBE_ID = UUID.fromString("6f1f9f3e-0000-4000-8000-00000000abcd");

    private static final CubeDefinition CUBE = new CubeDefinition(
            CUBE_ID, UUID.randomUUID(), UUID.randomUUID(), "sales", "Orders by customer",
            G + "Sales",
            List.of(CubeMeasure.of("amount", G + "amount", "SUM", Additivity.ADDITIVE),
                    CubeMeasure.ratio("margin_pct", "SUM(margin) / SUM(amount)")),
            List.of(new CubeDimension("customer", G + "Customer", G + "hasCustomer", true,
                    List.of(G + "country", G + "city"), false)),
            new CubeCalendar(G + "orderDate", List.of("year", "month")),
            List.of(), UUID.randomUUID(), null, Instant.now());

    private static final Model MODEL = CubeQb4olap.project(CUBE);

    private static Resource resource(String uri) {
        return MODEL.createResource(uri);
    }

    private static Resource dsd() {
        return resource(CubeQb4olap.dsdIri(CUBE_ID));
    }

    private static Resource measure(String name) {
        return resource(CubeQb4olap.dsdIri(CUBE_ID) + ":measure:" + name);
    }

    private static Resource dimension(String name) {
        return resource(CubeQb4olap.dsdIri(CUBE_ID) + ":dimension:" + name);
    }

    private static Resource level(String dimensionName, String levelName) {
        return resource(CubeQb4olap.dsdIri(CUBE_ID) + ":dimension:" + dimensionName
                + ":level:" + levelName);
    }

    @Test
    @DisplayName("the graph a cube's triples live in is named for the cube, and is not the DSD node")
    void graph_and_node_are_different_names() {
        assertThat(CubeQb4olap.graphUri(CUBE_ID))
                .isEqualTo("urn:aran:cubes:6f1f9f3e-0000-4000-8000-00000000abcd");
        assertThat(CubeQb4olap.dsdIri(CUBE_ID))
                .isEqualTo("urn:aran:cube:6f1f9f3e-0000-4000-8000-00000000abcd");
    }

    @Test
    @DisplayName("the cube is a qb:DataStructureDefinition labelled with its name")
    void emits_a_dsd() {
        assertThat(MODEL.contains(dsd(), RDF.type,
                resource(CubeQb4olap.QB + "DataStructureDefinition"))).isTrue();
        assertThat(MODEL.contains(dsd(), RDFS.label, "sales")).isTrue();
    }

    @Test
    @DisplayName("a column measure is a qb:MeasureProperty specialising the property it aggregates")
    void emits_a_measure_property() {
        assertThat(MODEL.contains(measure("amount"), RDF.type,
                resource(CubeQb4olap.QB + "MeasureProperty"))).isTrue();
        assertThat(MODEL.contains(measure("amount"), RDFS.subPropertyOf, resource(G + "amount")))
                .isTrue();
    }

    @Test
    @DisplayName("the aggregator reaches the component spec as a qb4o:aggregateFunction")
    void emits_the_aggregate_function() {
        assertThat(MODEL.listStatements(
                        null, MODEL.createProperty(CubeQb4olap.QB4O, "aggregateFunction"),
                        resource(CubeQb4olap.QB4O + "Sum"))
                .toList()).hasSize(1);
        // The component spec carrying it is the same one that names the measure.
        Resource spec = MODEL.listStatements(null,
                        MODEL.createProperty(CubeQb4olap.QB, "measure"), measure("amount"))
                .next().getSubject();
        assertThat(MODEL.contains(spec,
                MODEL.createProperty(CubeQb4olap.QB4O, "aggregateFunction"),
                resource(CubeQb4olap.QB4O + "Sum"))).isTrue();
    }

    @Test
    @DisplayName("a RATIO measure has no aggregate function — it is not an aggregation")
    void a_ratio_has_no_aggregate_function() {
        Resource spec = MODEL.listStatements(null,
                        MODEL.createProperty(CubeQb4olap.QB, "measure"), measure("margin_pct"))
                .next().getSubject();

        assertThat(MODEL.contains(spec,
                MODEL.createProperty(CubeQb4olap.QB4O, "aggregateFunction"))).isFalse();
        assertThat(MODEL.contains(measure("margin_pct"), RDFS.comment,
                "expr: SUM(margin) / SUM(amount)")).isTrue();
    }

    @Test
    @DisplayName("a dimension is a qb:DimensionProperty ranged on its class and over its join")
    void emits_a_dimension_property() {
        assertThat(MODEL.contains(dimension("customer"), RDF.type,
                resource(CubeQb4olap.QB + "DimensionProperty"))).isTrue();
        assertThat(MODEL.contains(dimension("customer"), RDFS.range, resource(G + "Customer")))
                .isTrue();
        assertThat(MODEL.contains(dimension("customer"), RDFS.subPropertyOf,
                resource(G + "hasCustomer"))).isTrue();
    }

    @Test
    @DisplayName("hierarchy levels roll up in definition order — city's parent is country")
    void wires_levels_coarsest_first() {
        assertThat(MODEL.contains(level("customer", "city"), RDF.type,
                resource(CubeQb4olap.QB4O + "LevelProperty"))).isTrue();
        assertThat(MODEL.contains(level("customer", "city"),
                MODEL.createProperty(CubeQb4olap.QB4O, "parentLevel"),
                level("customer", "country"))).isTrue();
        // The coarsest level is the root: nothing is above country.
        assertThat(MODEL.contains(level("customer", "country"),
                MODEL.createProperty(CubeQb4olap.QB4O, "parentLevel"))).isFalse();
        // Each level still points at the property it is made of.
        assertThat(MODEL.contains(level("customer", "city"),
                MODEL.createProperty(CubeQb4olap.QB4O, "hasAttribute"), resource(G + "city")))
                .isTrue();
    }

    @Test
    @DisplayName("the calendar is a dimension over its date column, with its levels wired")
    void emits_the_calendar_as_a_dimension() {
        assertThat(MODEL.contains(dimension("calendar"), RDF.type,
                resource(CubeQb4olap.QB + "DimensionProperty"))).isTrue();
        assertThat(MODEL.contains(dimension("calendar"), RDFS.subPropertyOf,
                resource(G + "orderDate"))).isTrue();
        assertThat(MODEL.contains(level("calendar", "month"),
                MODEL.createProperty(CubeQb4olap.QB4O, "parentLevel"),
                level("calendar", "year"))).isTrue();
    }

    @Test
    @DisplayName("every measure and dimension hangs off the DSD as a qb:component")
    void every_component_is_reachable_from_the_dsd() {
        // Two measures, one dimension, one calendar dimension.
        assertThat(MODEL.listStatements(dsd(),
                MODEL.createProperty(CubeQb4olap.QB, "component"), (Resource) null).toList())
                .hasSize(4);
    }

    @Test
    @DisplayName("no observation is ever emitted — the rows stay in the lake")
    void emits_no_observations() {
        assertThat(MODEL.contains(null, RDF.type,
                resource(CubeQb4olap.QB + "Observation"))).isFalse();
    }
}
