package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.rules.ProjectJoinRemoveRule;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.mapping.IntPair;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Removes a redundant {@code A ⋈ B → A} inner join, but <b>only</b> when it is provably safe —
 * the payoff of Phase 4B and, deliberately, its narrowest, most heavily gated rule.
 *
 * <h2>Why a bespoke rule, not a configured stock one</h2>
 *
 * <p>Calcite core has no inner-join-elimination rule. {@code ProjectJoinRemoveRule} /
 * {@code AggregateJoinRemoveRule} match a {@code JoinRelType.LEFT}/{@code RIGHT} <em>outer</em>
 * join and key off column uniqueness alone (an outer join preserves every row of its retained
 * side regardless of a match, so no referential integrity is needed and none is read). Aran's OQL
 * emits only {@code INNER} joins, so those rules never match; and even if they did, they carry no
 * notion of a trust tier. Eliminating an inner join is sound only under referential integrity
 * (every retained-side FK value has a match, so no rows are lost) <em>and</em> a unique referenced
 * key (so no rows are duplicated) — exactly the two facts Phase 4B tracks, and exactly what stock
 * Calcite does not consult. Hence this rule.
 *
 * <h2>The gates (all four required; every other case leaves the plan byte-identical)</h2>
 *
 * <ol>
 *   <li><b>Unprojected side.</b> The removed side {@code B} contributes no column read above the
 *       join. Checked against the {@link LogicalProject} that is the sole consumer in the match.</li>
 *   <li><b>Trust tier (the spine).</b> The join is backed by a {@code DECLARED} or {@code VERIFIED}
 *       referential edge {@code A → B} on the join's own key columns — <em>never</em> a
 *       {@code HYPOTHESIS} (an accepted-but-unverified join). Recovered via
 *       {@link TieredReferentialConstraint#tierOf}, which fail-safes anything untagged to
 *       {@code HYPOTHESIS}. A hypothesis is a guess; dropping a join on a guess drops rows.</li>
 *   <li><b>Exact key.</b> {@code B}'s referenced key is a verified unique key
 *       ({@code areColumnsUnique}), which post-Task-3 is true only for exact freyja uniqueness —
 *       never a {@code HARVESTED}-approximate one. A non-unique referenced side fans the join out,
 *       so removing it changes row multiplicities.</li>
 *   <li><b>Inner equi-join only.</b> A non-INNER join, or one with a residual (non-equi) predicate,
 *       is left alone: the residual would vanish with the join node.</li>
 * </ol>
 *
 * <h2>Null-rejection is part of correctness</h2>
 *
 * <p>An inner equi-join drops every {@code A} row whose FK is NULL ({@code NULL = x} is UNKNOWN),
 * and a foreign key does <em>not</em> imply {@code NOT NULL}. So the rewrite is not {@code A} but
 * {@code σ(fk IS NOT NULL)(A)} — with the FK columns forced non-null, referential integrity makes
 * every survivor match and the unique key makes it match exactly once, so the projected result is
 * row-for-row identical to the join's. Omitting this filter would resurrect the NULL-FK rows the
 * join discarded.
 *
 * <h2>Match shape</h2>
 *
 * <p>{@code Project(Join(TableScan, TableScan))}. Both sides are bare scans because that is where
 * the evidence lives: the referential edge is read off {@code A}'s {@link org.apache.calcite.plan
 * .RelOptTable} (the tier survives that path — pinned by
 * {@code AranStatisticTest.tierSurvivesTheRelOptTableScanPath}), and the key off {@code B}'s. This
 * is the single-source referential-join shape OQL produces; {@code JOIN_COMMUTE} is registered
 * (see {@link AranRules}) and OQL can name either class first, so the referenced side may be either
 * physical input — both orientations are tried.
 */
final class AranJoinRemoveRule extends RelRule<RelRule.Config> implements TransformationRule {

    /**
     * The match operand, built through the current (non-deprecated) {@code OperandBuilder} API.
     * calcite-core 1.41 exposes no {@code RelRule.Config.EMPTY} and this module has no Immutables
     * annotation processor, so we re-point the operand of Calcite's own join-removal config — the
     * closest stock analog — rather than hand-roll a {@code Config}. Only the base
     * {@code operandSupplier}/{@code description}/{@code relBuilderFactory} (the last defaulting to
     * {@code LOGICAL_BUILDER}) are read; every {@code ProjectJoinRemoveRule}-specific property is
     * inert here because {@link #onMatch} is our own.
     */
    private static final RelRule.Config CONFIG = ProjectJoinRemoveRule.Config.DEFAULT
            .withDescription("AranJoinRemoveRule")
            .withOperandSupplier(b0 -> b0.operand(LogicalProject.class)
                    .oneInput(b1 -> b1.operand(LogicalJoin.class)
                            .inputs(b2 -> b2.operand(LogicalTableScan.class).noInputs(),
                                    b3 -> b3.operand(LogicalTableScan.class).noInputs())));

    static final AranJoinRemoveRule INSTANCE = new AranJoinRemoveRule();

    private AranJoinRemoveRule() {
        super(CONFIG);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        final LogicalProject project = call.rel(0);
        final LogicalJoin join = call.rel(1);
        final TableScan leftScan = call.rel(2);
        final TableScan rightScan = call.rel(3);

        if (join.getJoinType() != JoinRelType.INNER) {
            return;
        }
        final JoinInfo info = join.analyzeCondition();
        // A residual (non-equi) predicate, or a join with no equi-key at all, cannot be eliminated:
        // the predicate would be silently dropped with the join node — a changed answer, not a
        // cheaper plan.
        if (!info.isEqui() || info.leftKeys.isEmpty()) {
            return;
        }
        // Defensive, and unreachable today because OqlPlanner emits only `=`: JoinInfo.isEqui() also
        // accepts IS NOT DISTINCT FROM as an equi-condition, but that operator matches NULL to NULL,
        // so the σ(fk IS NOT NULL)(A) rewrite below would drop A rows the join actually keeps (two
        // NULL keys join under INDF, but IS NOT NULL removes them). Require every equi-conjunct to be
        // a true `=` before eliminating — the null-rejection reasoning in the class javadoc holds
        // only for `=`.
        for (RexNode conjunct : RelOptUtil.conjunctions(join.getCondition())) {
            if (conjunct.getKind() != SqlKind.EQUALS) {
                return;
            }
        }

        final int nLeft = join.getLeft().getRowType().getFieldCount();
        final int nTotal = join.getRowType().getFieldCount();

        // Orientation 1 — remove the RIGHT input (B = right, the referential edge's referenced
        // "one" side; A = left, the referencing side). B's columns occupy [nLeft, nTotal) in the
        // join's output, and A's occupy [0, nLeft) so the Project needs no re-indexing (offset 0).
        if (tryRemove(call, project, leftScan, rightScan,
                info.leftKeys, info.rightKeys, nLeft, nTotal, 0)) {
            return;
        }
        // Orientation 2 — remove the LEFT input (B = left; A = right). B's columns occupy [0, nLeft),
        // and A's occupy [nLeft, nTotal) in the join's output, so the Project's refs shift down by
        // nLeft onto A's own row type.
        tryRemove(call, project, rightScan, leftScan,
                info.rightKeys, info.leftKeys, 0, nLeft, nLeft);
    }

    /**
     * Attempts {@code A ⋈ B → σ(aKeys NOT NULL)(A)} for one orientation, returning whether it
     * fired. {@code aScan}/{@code bScan} are the retained/removed scans; {@code aKeys}/{@code bKeys}
     * are the join's equi-key ordinals in each scan's own row type; {@code [bLo, bHi)} is B's column
     * range in the join output; {@code keepOffset} is where A's columns start there.
     */
    private boolean tryRemove(RelOptRuleCall call, LogicalProject project,
                              TableScan aScan, TableScan bScan,
                              ImmutableIntList aKeys, ImmutableIntList bKeys,
                              int bLo, int bHi, int keepOffset) {
        // Gate 1 — B must contribute no column the Project reads (else removing it drops data).
        ImmutableBitSet.Builder used = ImmutableBitSet.builder();
        for (RexNode e : project.getProjects()) {
            used.addAll(RelOptUtil.InputFinder.bits(e));
        }
        if (used.build().intersects(ImmutableBitSet.range(bLo, bHi))) {
            return false;
        }

        // Gate 2 (the trust-tier spine) — a DECLARED/VERIFIED edge A.aKeys → B.bKeys must back the
        // join. Never a HYPOTHESIS.
        if (!backedByTrustedEdge(aScan, bScan, aKeys, bKeys)) {
            return false;
        }

        // Gate 3 — B's referenced key must be a verified (exact) unique key. areColumnsUnique reads
        // the statistic, which reports a key only for exact freyja uniqueness (never HARVESTED).
        final RelMetadataQuery mq = call.getMetadataQuery();
        if (!Boolean.TRUE.equals(mq.areColumnsUnique(bScan, ImmutableBitSet.of(bKeys)))) {
            return false;
        }

        // All gates pass. Rewrite to σ(aKeys IS NOT NULL)(A), then re-home the Project onto A.
        final RelBuilder relBuilder = call.builder();
        relBuilder.push(aScan);
        List<RexNode> notNulls = new ArrayList<>();
        for (int k : aKeys) {
            notNulls.add(relBuilder.isNotNull(relBuilder.field(k)));
        }
        relBuilder.filter(notNulls);
        List<RexNode> reindexed = new RexShuttle() {
            @Override
            public RexNode visitInputRef(RexInputRef ref) {
                return keepOffset == 0 ? ref
                        : new RexInputRef(ref.getIndex() - keepOffset, ref.getType());
            }
        }.apply(project.getProjects());
        relBuilder.project(reindexed, project.getRowType().getFieldNames());
        call.transformTo(relBuilder.build());
        return true;
    }

    /**
     * Whether {@code aScan}'s table declares a referential edge {@code A.aKeys → B.bKeys} whose tier
     * is {@code DECLARED} or {@code VERIFIED}. The edge is read straight off the {@code RelOptTable}
     * and its tier recovered with {@link TieredReferentialConstraint#tierOf} — which returns
     * {@code HYPOTHESIS} for any constraint of unknown provenance, so nothing untrusted slips
     * through. The join's own key columns must match the edge's column pairs exactly: a declared FK
     * on <em>other</em> columns of the same two tables does not back <em>this</em> join.
     */
    private static boolean backedByTrustedEdge(TableScan aScan, TableScan bScan,
                                               ImmutableIntList aKeys, ImmutableIntList bKeys) {
        String bName = lastName(bScan.getTable().getQualifiedName());
        // A bare scan's row type is its table's global-column vector, so the join's key ordinals are
        // in the very order the edge's column pairs are expressed in — no offset to reconcile.
        List<IntPair> joinPairs = new ArrayList<>();
        for (int i = 0; i < aKeys.size(); i++) {
            joinPairs.add(IntPair.of(aKeys.get(i), bKeys.get(i)));
        }
        Set<IntPair> joinPairSet = Set.copyOf(joinPairs);
        for (RelReferentialConstraint ri : aScan.getTable().getReferentialConstraints()) {
            if (!lastName(ri.getTargetQualifiedName()).equals(bName)) {
                continue;
            }
            if (!Set.copyOf(ri.getColumnPairs()).equals(joinPairSet)) {
                continue;
            }
            if (TieredReferentialConstraint.tierOf(ri) != Tier.HYPOTHESIS) {
                return true;
            }
        }
        return false;
    }

    private static String lastName(List<String> qualifiedName) {
        return qualifiedName.get(qualifiedName.size() - 1);
    }
}
