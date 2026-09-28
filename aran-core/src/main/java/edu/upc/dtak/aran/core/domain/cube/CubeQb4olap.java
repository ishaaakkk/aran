package edu.upc.dtak.aran.core.domain.cube;

import edu.upc.dtak.aran.core.util.Names;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.jspecify.annotations.NullMarked;

/**
 * The cube as RDF: a QB4OLAP data structure definition over the classes and properties the schema
 * graph already holds.
 *
 * <p>Why a standard vocabulary rather than an {@code nx:} one — RDF Data Cube is a W3C
 * Recommendation and QB4OLAP is the established extension that adds the hierarchies and
 * per-measure aggregate functions QB lacks. Every downstream artefact (Mondrian XML, TMDL, Cube
 * YAML, an OData {@code $metadata}) is then a projection of one KG description rather than a
 * parallel definition. See §1.1 of the design brainstorm.
 *
 * <h2>Deliberate simplifications</h2>
 *
 * <p>Only the <em>schema half</em> of QB is emitted: the DSD, its components, and the levels. No
 * {@code qb:Observation} is ever written — the rows stay in the lake, which is the same
 * describe/execute split the rest of the platform runs on.
 *
 * <p>Two more, worth knowing before reading the triples as a complete QB4OLAP model: a RATIO
 * measure's expression is carried as an {@code rdfs:comment} rather than in a vocabulary term
 * (QB4OLAP names aggregate functions, not post-aggregation expressions, and inventing a predicate
 * for it would make the graph the second definition of record instead of the projection);
 * and {@link Additivity} is likewise recorded as a comment, since the vocabulary has no term for
 * it. The definition of record is the persisted {@link CubeDefinition} and its {@code cube.yaml}
 * rendering — this graph is what a SPARQL consumer reads, not what the platform reads back.
 */
@NullMarked
public final class CubeQb4olap {

    public static final String QB = "http://purl.org/linked-data/cube#";
    public static final String QB4O = "http://purl.org/qb4olap/cubes#";

    private CubeQb4olap() {
    }

    /** The per-cube named graph the triples live in — cleared and rewritten as one act. */
    public static String graphUri(UUID cubeId) {
        return "urn:aran:cubes:" + cubeId;
    }

    /** The DSD node itself. Singular, and deliberately not the graph name: one names the
     * container, the other the thing described inside it. */
    public static String dsdIri(UUID cubeId) {
        return "urn:aran:cube:" + cubeId;
    }

    public static Model project(CubeDefinition cube) {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("qb", QB);
        model.setNsPrefix("qb4o", QB4O);
        model.setNsPrefix("rdfs", RDFS.uri);

        Resource dsd = model.createResource(dsdIri(cube.id()))
                .addProperty(RDF.type, model.createResource(QB + "DataStructureDefinition"))
                .addProperty(RDFS.label, cube.name());
        if (cube.description() != null && !cube.description().isBlank()) {
            dsd.addProperty(RDFS.comment, cube.description());
        }

        Property component = model.createProperty(QB, "component");
        for (CubeMeasure measure : cube.measures()) {
            Resource measureProperty = model
                    .createResource(dsdIri(cube.id()) + ":measure:"
                            + Names.iriFragment(measure.name()))
                    .addProperty(RDF.type, model.createResource(QB + "MeasureProperty"))
                    .addProperty(RDFS.label, measure.name())
                    .addProperty(RDFS.comment, "additivity: " + measure.additivity().name());
            if (measure.columnIri() != null) {
                // The measure specialises the integrated property it aggregates — the standard
                // way to say "this is that column, in this cube's terms".
                measureProperty.addProperty(RDFS.subPropertyOf,
                        model.createResource(measure.columnIri()));
            }
            if (measure.expr() != null) {
                measureProperty.addProperty(RDFS.comment, "expr: " + measure.expr());
            }
            Resource spec = model.createResource()
                    .addProperty(model.createProperty(QB, "measure"), measureProperty);
            if (measure.aggregator() != null) {
                spec.addProperty(model.createProperty(QB4O, "aggregateFunction"),
                        model.createResource(QB4O + aggregateFunction(measure.aggregator())));
            }
            dsd.addProperty(component, spec);
        }

        for (CubeDimension dimension : cube.dimensions()) {
            Resource dimensionProperty = model
                    .createResource(dsdIri(cube.id()) + ":dimension:"
                            + Names.iriFragment(dimension.name()))
                    .addProperty(RDF.type, model.createResource(QB + "DimensionProperty"))
                    .addProperty(RDFS.label, dimension.name())
                    // The class the members come from, and the join that reaches it: together
                    // these are the whole relationship, and neither is derivable from the other.
                    .addProperty(RDFS.range, model.createResource(dimension.classIri()))
                    .addProperty(RDFS.subPropertyOf,
                            model.createResource(dimension.joinPropertyIri()));
            if (dimension.unknownMember()) {
                dimensionProperty.addProperty(RDFS.comment, "unknownMember: true");
            }
            dsd.addProperty(component, model.createResource()
                    .addProperty(model.createProperty(QB, "dimension"), dimensionProperty));
            addLevels(model, dimensionProperty, dimension.hierarchy(), true);
        }

        CubeCalendar calendar = cube.calendar();
        if (calendar != null) {
            Resource dimensionProperty = model
                    .createResource(dsdIri(cube.id()) + ":dimension:calendar")
                    .addProperty(RDF.type, model.createResource(QB + "DimensionProperty"))
                    .addProperty(RDFS.label, "calendar")
                    .addProperty(RDFS.subPropertyOf,
                            model.createResource(calendar.fromColumnIri()));
            dsd.addProperty(component, model.createResource()
                    .addProperty(model.createProperty(QB, "dimension"), dimensionProperty));
            addLevels(model, dimensionProperty, calendar.levels(), false);
        }
        return model;
    }

    /**
     * One {@code qb4o:LevelProperty} per level, in order, each naming the previous one as its
     * {@code qb4o:parentLevel}.
     *
     * <p>The list is coarsest-first (see {@link CubeDimension#hierarchy()}), so the parent of a
     * level is its predecessor — city rolls up to country, not the other way round. Getting this
     * backwards would produce a model that drills the wrong way, silently.
     *
     * @param fromIris whether the level names are property IRIs (a dimension hierarchy) or the
     *                 generated calendar's plain level names
     */
    private static void addLevels(Model model, Resource dimensionProperty, List<String> levels,
                                  boolean fromIris) {
        Resource parent = null;
        for (String level : levels) {
            String label = fromIris ? Names.localName(level) : level;
            Resource levelProperty = model
                    .createResource(dimensionProperty.getURI() + ":level:"
                            + Names.iriFragment(label))
                    .addProperty(RDF.type, model.createResource(QB4O + "LevelProperty"))
                    .addProperty(RDFS.label, label);
            if (fromIris) {
                levelProperty.addProperty(model.createProperty(QB4O, "hasAttribute"),
                        model.createResource(level));
            }
            dimensionProperty.addProperty(model.createProperty(QB4O, "hasLevel"), levelProperty);
            if (parent != null) {
                levelProperty.addProperty(model.createProperty(QB4O, "parentLevel"), parent);
            }
            parent = levelProperty;
        }
    }

    /** SUM → {@code qb4o:Sum}: the vocabulary's own instances of {@code qb4o:AggregateFunction}. */
    private static String aggregateFunction(String aggregator) {
        String lower = aggregator.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
