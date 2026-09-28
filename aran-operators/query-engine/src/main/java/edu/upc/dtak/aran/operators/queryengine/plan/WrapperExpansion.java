package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.util.Names;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Phase 3A's expansion probe: plans a wrapper's own SQL text over a one-table schema standing
 * in for its {@code src_<sanitize(datasetId)>} snapshot relation ({@link SrcTable}), so a later
 * phase can eventually see through a wrapper that is a pure projection instead of treating
 * every wrapper as an opaque scan.
 *
 * <p>Deliberately produces evidence, not a decision. A successful {@link #probe} only means
 * the wrapper's text plans cleanly over the raw columns the catalog recorded for it
 * ({@code sourceRefs}/{@code columnMap} — see {@link MappingCatalog.SourceView}); whether an
 * expanded tree actually gets substituted into a real query plan is a later phase's call. This
 * class never executes anything — {@link SrcTable#scan} throws exactly like
 * {@link WrapperTable#scan}/{@link IntegratedClassTable#scan}, because nothing here is meant to
 * run.
 */
public final class WrapperExpansion {

    private static final Logger log = LoggerFactory.getLogger(WrapperExpansion.class);

    /**
     * The literal token a wrapper's {@code FROM} clause carries in place of a real table name —
     * mirrors {@code WrapperVocab.SNAPSHOT_PLACEHOLDER} (aran-operators:bootstrapping) and the
     * substitution {@code WrapperSqlValidator} (aran-core) already performs to make the same
     * text parseable.
     */
    private static final String SNAPSHOT_PLACEHOLDER = "${SNAPSHOT}";

    private WrapperExpansion() {
    }

    /**
     * Plans {@code sv.wrapperSql()} over a schema containing only {@code sv}'s own
     * {@code src_<sanitize(datasetId)>} relation, returning the resulting tree — or
     * {@code Optional.empty()} for any reason the wrapper cannot be trusted as an expansion.
     *
     * <p>{@code ${SNAPSHOT}} is substituted with the view name <em>quoted</em>
     * ({@code "src_ds1"}, not {@code src_ds1}), for the same reason
     * {@code OqlPlanner.probeConstraint} quotes the view name it substitutes into its own probe
     * SQL: Calcite's default parser config upper-cases a bare (unquoted) identifier before
     * resolving it, while a quoted one resolves character-for-character. {@link Names#sqlName}
     * does not upper-case anything, so an unquoted reference to a mixed-case dataset id's view
     * name would look for a table this method never registered.
     *
     * <p>Two independent things can make a wrapper unusable, and both are logged (WARN) and
     * folded into the same empty result — the caller cannot act differently on one versus the
     * other, and a wrapper that fails either one simply stays {@code OPAQUE}:
     * <ul>
     *   <li><b>The text does not plan.</b> Parse, validation or conversion can all fail — a
     *       DuckDB-only construct Calcite's parser does not know, a column the catalog never
     *       recorded a {@code sourceRef} for, and so on — and every one of them surfaces as an
     *       {@link Exception}. An {@link Error} is deliberately not caught: it is not a
     *       statement about the wrapper.</li>
     *   <li><b>The text plans, but to something unusable.</b> {@link #unusablePart} screens the
     *       tree for a sub-query, correlation variable or dynamic parameter — the same three
     *       constructs {@code OqlPlanner.unusablePart} rejects from an enforced constraint, and
     *       for the identical reason: none of the three can be carried into a different
     *       planner's cluster later.</li>
     * </ul>
     */
    public static Optional<RelNode> probe(MappingCatalog.SourceView sv) {
        try {
            String viewName = "src_" + Names.sqlName(sv.datasetId());
            String quotedViewName = "\"" + viewName.replace("\"", "\"\"") + "\"";
            String sql = sv.wrapperSql().replace(SNAPSHOT_PLACEHOLDER, quotedViewName);

            SchemaPlus schema = Frameworks.createRootSchema(false);
            schema.add(viewName, new SrcTable(sv));

            Planner planner = Frameworks.getPlanner(
                    Frameworks.newConfigBuilder().defaultSchema(schema).build());
            try {
                RelNode tree = planner.rel(planner.validate(planner.parse(sql))).rel;
                String unusable = unusablePart(tree);
                if (unusable != null) {
                    log.warn("Wrapper for dataset '{}' plans to a tree containing a {}, which"
                            + " cannot be carried as an expansion, so it stays OPAQUE: {}",
                            sv.datasetId(), unusable, sv.wrapperSql());
                    return Optional.empty();
                }
                return Optional.of(tree);
            } finally {
                planner.close();
            }
        } catch (Exception e) {
            // Every way Calcite reports "this text does not plan over this schema" — parse,
            // validation and conversion failures alike — is an Exception, and every one of them
            // means the same thing here: this wrapper stays OPAQUE. Same discipline as
            // OqlPlanner.probeConstraint, for the same reason: a probe is allowed to fail, a
            // query is not.
            log.warn("Wrapper for dataset '{}' could not be planned over its src_ relation, so"
                    + " it stays OPAQUE: {}", sv.datasetId(), sv.wrapperSql(), e);
            return Optional.empty();
        }
    }

    /**
     * {@link OqlPlanner#unusablePart}, applied to a whole plan tree rather than one pre-known
     * filter condition. A wrapper's SELECT list can itself carry a scalar sub-query (e.g.
     * {@code SELECT (SELECT MAX(x) FROM ${SNAPSHOT}) FROM ${SNAPSHOT}}), which is a Project's
     * own expression, not a Filter's — so this walks every operator {@link PlanShape#walk}
     * already knows how to describe that can carry a Rex expression (Filter, Join, Project,
     * Sort) and delegates the actual "is this construct unusable" check to the one shared
     * implementation, rather than re-deciding it here.
     *
     * <p>{@code WrapperSqlValidator} rejects JOIN and anything but a single SELECT before a
     * wrapper is ever saved, so a real wrapper's plan is at most Scan → [Filter] → [Project] →
     * [Sort]. Join is screened anyway so a probe run against SQL that never went through that
     * validator — a test, or a graph written before it existed — degrades the same way instead
     * of silently skipping a join condition.
     */
    private static @Nullable String unusablePart(RelNode rel) {
        String found = null;
        if (rel instanceof Filter filter) {
            found = OqlPlanner.unusablePart(filter.getCondition());
        } else if (rel instanceof Join join) {
            found = OqlPlanner.unusablePart(join.getCondition());
        } else if (rel instanceof Project project) {
            for (RexNode expr : project.getProjects()) {
                found = OqlPlanner.unusablePart(expr);
                if (found != null) {
                    break;
                }
            }
        } else if (rel instanceof Sort sort) {
            if (sort.fetch != null) {
                found = OqlPlanner.unusablePart(sort.fetch);
            }
            if (found == null && sort.offset != null) {
                found = OqlPlanner.unusablePart(sort.offset);
            }
        }
        if (found != null) {
            return found;
        }
        for (RelNode input : rel.getInputs()) {
            found = unusablePart(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The structural half of the expansion gate, shared by {@code OqlPlanner.pushExpandedMember}
     * (which refuses to substitute such a plan) and {@code QueryEngineOperator.verifyWrapper}
     * (which must not persist an {@code EXPANDED} verdict the planner will never honor —
     * audit WRAPPER_VERIFY-9's structural half): only a chain of Project/Filter/Sort over the
     * single {@code src_} TableScan is rebuildable in the planner's own cluster. Returns the
     * offending node's simple name, or null when the shape is fine. One definition on purpose —
     * two copies would let the badge and the planner disagree again.
     */
    public static @Nullable String unrebuildablePart(RelNode rel) {
        if (rel instanceof org.apache.calcite.rel.core.TableScan) {
            return null;
        }
        if (rel.getInputs().size() != 1
                || !(rel instanceof Project
                        || rel instanceof Filter
                        || rel instanceof Sort)) {
            return rel.getClass().getSimpleName();
        }
        return unrebuildablePart(rel.getInput(0));
    }
}
