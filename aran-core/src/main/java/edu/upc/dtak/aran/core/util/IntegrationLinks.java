package edu.upc.dtak.aran.core.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Source-to-integrated resolution over the schema graph — the exact {@code (rdfs:subClassOf|
 * nx:subClassOf)} / {@code (rdfs:subPropertyOf|nx:subPropertyOf)} walk {@code
 * MappingCatalogOperator}'s QUERY 1/QUERY 2 express as SPARQL, available here as plain Jena calls
 * for a caller that already holds a source-class or source-property IRI (rather than driving the
 * resolution from a SPARQL WHERE clause of its own).
 *
 * <p>Originally two {@code private static} methods inside {@link
 * edu.upc.dtak.aran.core.service.IntegrationModelService} (Phase 3B, for {@code
 * discoverConstraints}/{@code wrapperOutputLocalNames}). Phase 4B Task 3 needed the identical
 * resolution to map a harvested foreign key's physical table/column onto integrated class/property
 * IRIs, and a second private copy of a subClassOf/subPropertyOf walk is exactly the drifted-copy
 * hazard {@code Names}' javadoc warns about — so this extraction is the reuse, not a rewrite.
 * {@code IntegrationModelService} now delegates here instead of carrying its own copy.
 *
 * <p>The vocabulary constants are inlined rather than imported from {@code
 * edu.upc.dtak.aran.operators.schemaintegration.IntegrationVocab} for the same layering reason
 * {@code IntegrationModelService} already documented: aran-core cannot depend on
 * schema-integration, which owns that class and both reads and writes these triples back
 * (alignment rules, {@code MappingCatalogOperator}'s QUERY 1). Keep in sync with
 * {@code IntegrationVocab} if either drifts.
 */
@NullMarked
public final class IntegrationLinks {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String NX_SUB_CLASS_OF = NS + "subClassOf";
    private static final String NX_SUB_PROPERTY_OF = NS + "subPropertyOf";
    private static final String NX_INTEGRATED_RESOURCE = NS + "IntegratedResource";

    private IntegrationLinks() {}

    /**
     * The one integrated class {@code srcClassIri} belongs to, via {@code (rdfs:subClassOf|
     * nx:subClassOf)} — the exact alternation QUERY 1 walks, restricted the same way QUERY 1's
     * {@code ?intClass a nx:IntegratedResource} restricts it: a plain {@code rdfs:subClassOf} to
     * some unrelated class must not be mistaken for the integration link.
     *
     * <p>A source class carrying {@code nx:wrapperSql} that reaches this method is, in a
     * correctly-compiled schema, always subClassOf exactly one such class — {@code
     * SchemaIntegrator} mints one for every source class, aligned with another or not (Depth-1:
     * source classes are leaves, integrated classes are the roots). {@code null} therefore means
     * the active schema does not (yet) integrate this class — compiled but never promoted, a stale
     * caller, or a genuine graph inconsistency — not something to guess at.
     */
    public static @Nullable String resolveIntegratedClass(Model active, String srcClassIri) {
        Resource srcClass = active.getResource(srcClassIri);
        for (Property subClassOf : List.of(RDFS.subClassOf, active.createProperty(NX_SUB_CLASS_OF))) {
            StmtIterator it = srcClass.listProperties(subClassOf);
            while (it.hasNext()) {
                RDFNode obj = it.next().getObject();
                if (obj.isURIResource() && isIntegratedResource(active, obj.asResource())) {
                    return obj.asResource().getURI();
                }
            }
        }
        return null;
    }

    /**
     * {@code COALESCE(?ip, ?srcProp)} — QUERY 1's exact rule for a source property's target
     * property: the integrated property it is {@code (rdfs:subPropertyOf|nx:subPropertyOf)} of,
     * when that link exists and points at an {@code IntegratedResource}; the source property
     * itself otherwise (unaligned — it stands as its own target).
     */
    public static String resolveTargetProperty(Model active, String srcPropIri) {
        Resource srcProp = active.getResource(srcPropIri);
        for (Property subPropertyOf : List.of(RDFS.subPropertyOf, active.createProperty(NX_SUB_PROPERTY_OF))) {
            StmtIterator it = srcProp.listProperties(subPropertyOf);
            while (it.hasNext()) {
                RDFNode obj = it.next().getObject();
                if (obj.isURIResource() && isIntegratedResource(active, obj.asResource())) {
                    return obj.asResource().getURI();
                }
            }
        }
        return srcPropIri;
    }

    /**
     * Every integrated class the active schema actually <em>serves</em> — the exact key set
     * {@code MappingCatalog.classes()} ends up with, derived here without a Calcite/DuckDB
     * dependency (V42).
     *
     * <p>QUERY 1's class shape is a join, not a type test: an integrated class reaches the catalog
     * only when at least one source class carrying {@code nx:wrapperSql} is
     * {@code (rdfs:subClassOf|nx:subClassOf)} of it. This walks the same join from the wrappered
     * source classes upward through {@link #resolveIntegratedClass}, so the two cannot disagree
     * about a class that exists in the graph as an {@code IntegratedResource} but has no wrappered
     * source under it — that class is not queryable and must not be reported as one.
     *
     * <p>An empty result means the active schema serves nothing (never promoted, or rolled back to
     * empty). Callers must read that as "there is no catalog to compare against", never as "every
     * class is gone" — see {@code ModelDtos.ModelConstraintDto}.
     */
    public static Set<String> servedIntegratedClasses(Model active) {
        Property wrapperSql = active.createProperty(NS + "wrapperSql");
        Set<String> out = new LinkedHashSet<>();
        StmtIterator it = active.listStatements(null, wrapperSql, (RDFNode) null);
        while (it.hasNext()) {
            Resource srcClass = it.next().getSubject();
            if (!srcClass.isURIResource()) {
                continue;
            }
            String integrated = resolveIntegratedClass(active, srcClass.getURI());
            // QUERY 1 binds `?intClass a nx:IntegratedResource ; a rdfs:Class` — BOTH types. The
            // second test is applied here rather than inside resolveIntegratedClass because that
            // method is shared (discoverConstraints, DeclaredEdgeResolver) and tightening it would
            // change behaviour for callers this defect never touched. Here it is the exactness
            // V42 needs: a class this reported but the catalog does not serve would be a
            // constraint reported live that the engine silently skips — V42's own lie.
            if (integrated != null
                    && active.contains(active.getResource(integrated), RDF.type, RDFS.Class)) {
                out.add(integrated);
            }
        }
        return out;
    }

    private static boolean isIntegratedResource(Model active, Resource r) {
        return active.contains(r, RDF.type, active.createResource(NX_INTEGRATED_RESOURCE));
    }
}
