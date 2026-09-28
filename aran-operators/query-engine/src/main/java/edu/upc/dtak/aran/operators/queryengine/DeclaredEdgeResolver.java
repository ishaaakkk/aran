package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.domain.DeclaredForeignKey;
import edu.upc.dtak.aran.core.util.IntegrationLinks;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.ReferentialEdge;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves harvested {@link DeclaredForeignKey}s (physical table/column identity, Aran dataset
 * ids) into {@link ReferentialEdge}s tagged {@link Tier#DECLARED} on the INTEGRATED class/property
 * IRIs a catalog build works in — {@code MappingCatalogOperator.buildCatalog}'s feed into
 * {@code MappingCatalog.declaredEdges}.
 *
 * <p>Mirrors the resolution {@code IntegrationModelService.discoverConstraints} already performs
 * for a different fact (a discovered denial constraint), reusing the exact same primitives: source
 * class → integrated class via {@link IntegrationLinks#resolveIntegratedClass}, source property →
 * integrated (or unaligned-source) property via {@link IntegrationLinks#resolveTargetProperty}. The
 * one extra step this mapping needs — physical table/column name → source class/property IRI — is
 * QUERY 1's own {@code nx:datasetId}/{@code nx:sourceRef} facts, which {@code
 * MappingCatalogOperator}'s existing loop already collects (as {@code tempViews}/{@code
 * refsBySrcClass}) for its own SNAPSHOT-profile renaming; this class takes those same two maps
 * rather than re-querying {@code active} for them.
 *
 * <p><b>Every failure to resolve drops the WHOLE edge, never a partial one</b> — logged WARN, never
 * thrown, mirroring every other "can't place it" path in this phase ({@code
 * RelationalCatalogHarvestService}'s per-piece degrade, {@code discoverConstraints}'s own
 * unenforceable-class WARN). A {@code ReferentialEdge} with fewer columns than the harvested key,
 * or with one side left in source-namespace because resolution failed, would not be a weaker
 * version of the true edge — it would be a WRONG edge, and Task 4's elimination rule trusts the
 * DECLARED tier specifically to skip re-verifying it. Dropping is the only safe reading of a
 * harvested FK this catalog cannot fully place.
 *
 * <p><b>One soundness check deliberately lives downstream, not here: the referencing class must be
 * single-source.</b> A harvested FK constrains only the one source dataset it was declared on, so a
 * DECLARED edge attributed to a multi-source integrated class would falsely cover a co-source's
 * rows. That check is applied at <em>exposure</em> time by {@code ReferentialConstraints} (the
 * aran-operators/query-engine plan package), not at resolution, because soundness depends on the
 * GAV view level the constraint is exposed at ({@code int_} vs a {@code u_} union member) — a fact
 * this resolver, which works purely in integrated-class terms, does not see.
 * The edge is still resolved and stored; it is simply never handed to the planner for a multi-source
 * referencing class, so the elimination rule cannot act on it.
 */
@NullMarked
final class DeclaredEdgeResolver {

    private static final Logger log = LoggerFactory.getLogger(DeclaredEdgeResolver.class);

    private DeclaredEdgeResolver() {}

    /**
     * @param srcClassByDatasetId          dataset id, in the same string form as {@code
     *                                     SourceView.datasetId()} → the source class IRI QUERY 1
     *                                     recorded {@code nx:datasetId}/{@code nx:provenance} for
     * @param rawColumnToSrcPropBySrcClass source class IRI → {physical column name → source
     *                                     property IRI}, i.e. {@code MappingCatalogOperator}'s
     *                                     {@code refsBySrcClass} ({@code nx:sourceRef} facts)
     */
    static List<ReferentialEdge> resolve(Model active, List<DeclaredForeignKey> declaredForeignKeys,
            Map<String, String> srcClassByDatasetId,
            Map<String, Map<String, String>> rawColumnToSrcPropBySrcClass) {
        List<ReferentialEdge> edges = new ArrayList<>();
        for (DeclaredForeignKey fk : declaredForeignKeys) {
            ReferentialEdge edge =
                    resolveOne(active, fk, srcClassByDatasetId, rawColumnToSrcPropBySrcClass);
            if (edge != null) {
                edges.add(edge);
            }
        }
        return edges;
    }

    private static @Nullable ReferentialEdge resolveOne(Model active, DeclaredForeignKey fk,
            Map<String, String> srcClassByDatasetId,
            Map<String, Map<String, String>> rawColumnToSrcPropBySrcClass) {
        Side from = resolveSide(active, fk.fromDatasetId(), fk.fromCols(),
                srcClassByDatasetId, rawColumnToSrcPropBySrcClass, "from");
        if (from == null) {
            return null;
        }
        Side to = resolveSide(active, fk.toDatasetId(), fk.toCols(),
                srcClassByDatasetId, rawColumnToSrcPropBySrcClass, "to");
        if (to == null) {
            return null;
        }
        return new ReferentialEdge(from.classIri, from.cols, to.classIri, to.cols, Tier.DECLARED);
    }

    private record Side(String classIri, List<String> cols) {}

    private static @Nullable Side resolveSide(Model active, UUID datasetId, List<String> physicalCols,
            Map<String, String> srcClassByDatasetId,
            Map<String, Map<String, String>> rawColumnToSrcPropBySrcClass, String label) {
        String srcClassIri = srcClassByDatasetId.get(datasetId.toString());
        if (srcClassIri == null) {
            log.warn("Dropping a harvested foreign key: no source class in the active schema wraps "
                    + "the '{}' dataset '{}' — can't place it", label, datasetId);
            return null;
        }
        String integratedClassIri = IntegrationLinks.resolveIntegratedClass(active, srcClassIri);
        if (integratedClassIri == null) {
            log.warn("Dropping a harvested foreign key: '{}' source class '{}' (dataset '{}') is not "
                    + "subClassOf any integrated class in the active schema — can't place it",
                    label, srcClassIri, datasetId);
            return null;
        }
        Map<String, String> rawToSrcProp = rawColumnToSrcPropBySrcClass.getOrDefault(srcClassIri, Map.of());
        List<String> resolvedCols = new ArrayList<>();
        for (String col : physicalCols) {
            String srcPropIri = rawToSrcProp.get(col);
            if (srcPropIri == null) {
                log.warn("Dropping a harvested foreign key: '{}' column '{}' on dataset '{}' (source "
                        + "class '{}') has no recorded nx:sourceRef in the active schema — can't place it",
                        label, col, datasetId, srcClassIri);
                return null;
            }
            resolvedCols.add(IntegrationLinks.resolveTargetProperty(active, srcPropIri));
        }
        return new Side(integratedClassIri, resolvedCols);
    }
}
