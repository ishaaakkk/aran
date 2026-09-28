package edu.upc.dtak.aran.operators.schemaintegration;

import org.jspecify.annotations.NullMarked;

/**
 * Vocabulary IRIs for the integration metamodel extension defined in
 * Flores et al. (2024), §5. Following the paper, an integrated class or
 * property is identified by carrying both its rdf:type to {@code rdfs:Class}
 * / {@code rdf:Property} <em>and</em> an additional rdf:type to
 * {@link #INTEGRATED_RESOURCE}.
 */
@NullMarked
public final class IntegrationVocab {

    public static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";

    /** Marker type for any integrated class or property. */
    public static final String INTEGRATED_RESOURCE = NS + "IntegratedResource";

    /** Marker linking an integrated datatype-property to its join-object-property representation. */
    public static final String JOIN_PROPERTY = NS + "JoinProperty";

    /** Namespace for newly-minted integrated-resource IRIs. */
    public static final String INTEGRATION_NS = NS + "integration/";

    public static final String JOIN_ON_LEFT = NS + "joinOnLeft";
    public static final String JOIN_ON_RIGHT = NS + "joinOnRight";
    public static final String PROVENANCE = NS + "provenance";
    public static final String SUB_CLASS_OF = NS + "subClassOf";
    public static final String SUB_PROPERTY_OF = NS + "subPropertyOf";

    /**
     * Marker triple predicate: {@code <urn:aran:schema> nx:integrationModel "{modelId}"} records
     * which integration model owns an integrated graph, so an incremental step can refuse to build
     * on a base graph owned by a different model (multiple models per tenant).
     */
    public static final String INTEGRATION_MODEL = NS + "integrationModel";

    private IntegrationVocab() {}
}
