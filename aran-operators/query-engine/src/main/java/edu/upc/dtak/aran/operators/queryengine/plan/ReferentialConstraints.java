package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.util.Names;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.IntegratedClass;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.ReferentialEdge;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.util.mapping.IntPair;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Translates a {@link MappingCatalog}'s tier-tagged {@link ReferentialEdge}s into the Calcite
 * {@link RelReferentialConstraint}s a class table's statistic exposes, keeping the {@link
 * MappingCatalog.Tier} recoverable via {@link TieredReferentialConstraint}.
 *
 * <p>One builder, two callers, so the two levels of the GAV unfolding cannot drift:
 *
 * <ul>
 *   <li>{@link #forIntegratedClass} — the {@code int_} view. Source and target are both full class
 *       views ({@code int_A → int_B}).</li>
 *   <li>{@link #forWrapperMember} — a {@code u_} union member. Source is this member
 *       ({@code u_ds_A}); the target is exposed <b>only when the referenced class has a single
 *       source</b> ({@code u_ds'_B}). A union member is not a sound referent for a class-level
 *       constraint — an {@code A} row may reference a {@code B} in either source, so no single
 *       {@code u_B} view carries it. This mirrors the reason {@link AranStatistic} refuses to
 *       declare a key across a UNION ALL, and matters beyond {@code HYPOTHESIS}: a {@code DECLARED}
 *       edge Task 4 could act on must never be handed an unsound below-union referent.</li>
 * </ul>
 *
 * <p><b>The referencing (source) side is gated too, and structurally.</b> A {@code DECLARED}/{@code
 * VERIFIED} edge — the only tiers {@code AranJoinRemoveRule} can eliminate on — is exposed only when
 * the <em>referencing</em> class has a single source. A foreign key is harvested from one source
 * dataset, so attributing it to a multi-source class would falsely assert that a co-source's rows
 * satisfy it too; refusing to expose the constraint at all makes that unsoundness structurally
 * unable to reach the elimination rule, rather than relying on the plan shape (a multi-source class
 * unfolds to a UNION the rule's {@code Project(Join(Scan,Scan))} match happens not to fit) to save
 * it. {@code HYPOTHESIS} edges are estimate-only and keep their existing exposure regardless of
 * source count — so this gate only ever tightens the tiers that could change an answer.
 *
 * <p>Column ordinals are positions in {@link WrapperTable#globalColumns} of the respective class —
 * the same sorted global-IRI vector both {@code int_} and every {@code u_} member expose, so the
 * source/target ordinals are level-independent. A join key that is not one of its class's columns
 * (never expected under the join contract) drops the whole edge rather than emit a malformed pair.
 */
final class ReferentialConstraints {

    private ReferentialConstraints() {}

    static List<RelReferentialConstraint> forIntegratedClass(MappingCatalog catalog,
                                                             IntegratedClass cls) {
        return build(catalog, cls,
                "int_" + Names.sqlName(cls.iri()),
                target -> "int_" + Names.sqlName(target.iri()),
                target -> true);
    }

    static List<RelReferentialConstraint> forWrapperMember(MappingCatalog catalog,
                                                           IntegratedClass cls,
                                                           MappingCatalog.SourceView member) {
        return build(catalog, cls,
                WrapperTable.viewName(member, cls),
                target -> WrapperTable.viewName(target.sources().get(0), target),
                target -> target.sources().size() == 1);
    }

    private static List<RelReferentialConstraint> build(MappingCatalog catalog, IntegratedClass cls,
                                                        String sourceName,
                                                        Function<IntegratedClass, String> targetName,
                                                        Predicate<IntegratedClass> targetIsSoundReferent) {
        List<RelReferentialConstraint> result = new ArrayList<>();
        List<String> sourceCols = WrapperTable.globalColumns(cls);
        // Source-side soundness, the structural mirror of targetIsSoundReferent. A DECLARED/VERIFIED
        // edge is the only kind AranJoinRemoveRule can act on; attributing one to a multi-source
        // referencing class would claim a co-source's rows also satisfy the FK — false, since the
        // FK was harvested from just one of those sources. So an eliminable edge is exposed only when
        // the referencing class has a single source (then that member IS the FK's own sole source).
        // A HYPOTHESIS edge can only sharpen estimates (the rule refuses it), so it keeps its
        // existing exposure and this gate never narrows it — which is also why no golden shifts.
        boolean referencingIsSingleSource = cls.sources().size() == 1;
        for (ReferentialEdge edge : catalog.referentialEdges()) {
            if (!edge.leftClass().equals(cls.iri())) {
                continue;
            }
            if (edge.tier() != Tier.HYPOTHESIS && !referencingIsSingleSource) {
                continue;
            }
            IntegratedClass target = catalog.classes().get(edge.rightClass());
            if (target == null || !targetIsSoundReferent.test(target)) {
                continue;
            }
            List<IntPair> pairs = columnPairs(edge, sourceCols, WrapperTable.globalColumns(target));
            if (pairs == null) {
                continue;
            }
            result.add(TieredReferentialConstraint.of(edge.tier(),
                    List.of(sourceName), List.of(targetName.apply(target)), pairs));
        }
        return result;
    }

    /** {@code null} if any join-key column is not one of its class's columns — drops the edge. */
    private static @Nullable List<IntPair> columnPairs(ReferentialEdge edge,
                                                       List<String> sourceCols, List<String> targetCols) {
        List<IntPair> pairs = new ArrayList<>();
        for (int i = 0; i < edge.leftCols().size(); i++) {
            int source = sourceCols.indexOf(edge.leftCols().get(i));
            int target = targetCols.indexOf(edge.rightCols().get(i));
            if (source < 0 || target < 0) {
                return null;
            }
            pairs.add(IntPair.of(source, target));
        }
        return pairs;
    }
}
