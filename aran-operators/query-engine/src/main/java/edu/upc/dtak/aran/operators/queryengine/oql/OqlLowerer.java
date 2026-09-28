package edu.upc.dtak.aran.operators.queryengine.oql;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates an {@link OqlQuery} (variable-free) into an {@link AliasedQuery} (alias-based)
 * by assigning synthetic aliases t0, t1, … to each class in the query.
 *
 * <p>Handles plain projections, aggregate functions, GROUP BY keys, and HAVING predicates.
 *
 * <p>Paths are n-hop: one JOIN per {@link OqlQuery.PathStep}, aliased t1..tN. Covered to three
 * hops by {@code SourcesToOqlEndToEndTest.three_hop_path}. The two shapes that are rejected,
 * both below, are self-joins and any path that visits the same class twice — a class is its
 * own alias key here, so a repeat would silently overwrite the earlier hop's alias.
 */
@NullMarked
public final class OqlLowerer {

    public static final class OqlLowerException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public OqlLowerException(String message) { super(message); }
    }

    private OqlLowerer() {}

    public static AliasedQuery lower(OqlQuery oql) {
        List<OqlQuery.PathStep> path = oql.path();

        // class → alias map: t0 = primary, t1..tN = join hops.
        Map<String, String> classAlias = new HashMap<>();
        String primaryClass;

        if (path.isEmpty()) {
            primaryClass = oql.explicitFrom() != null
                    ? oql.explicitFrom()
                    : inferPrimaryClass(oql.select());
            classAlias.put(primaryClass, "t0");
        } else {
            primaryClass = path.get(0).sourceClass();
            classAlias.put(primaryClass, "t0");
            for (int hop = 0; hop < path.size(); hop++) {
                OqlQuery.PathStep step = path.get(hop);
                if (step.sourceClass().equals(step.targetClass())) {
                    throw new OqlLowerException(
                        "self-join is not supported: class '" + step.sourceClass()
                        + "' appears on both sides of path step '" + step.joinProperty() + "'");
                }
                if (classAlias.containsKey(step.targetClass())) {
                    throw new OqlLowerException(
                        "class '" + step.targetClass() + "' appears more than once in the FROM path "
                        + "(cyclic joins are not supported)");
                }
                classAlias.put(step.targetClass(), "t" + (hop + 1));
            }
        }

        for (OqlQuery.Projection proj : oql.select()) {
            if (proj.className() != null && !classAlias.containsKey(proj.className())) {
                throw new OqlLowerException(
                    "class '" + proj.className() + "' referenced in SELECT is not in FROM; "
                    + "known classes: " + classAlias.keySet());
            }
        }

        for (OqlQuery.Filter f : oql.where()) {
            if (!classAlias.containsKey(f.className())) {
                throw new OqlLowerException(
                    "class '" + f.className() + "' referenced in WHERE is not in FROM; "
                    + "known classes: " + classAlias.keySet());
            }
        }

        for (OqlQuery.GroupKey gk : oql.groupBy()) {
            if (!classAlias.containsKey(gk.className())) {
                throw new OqlLowerException(
                    "class '" + gk.className() + "' referenced in GROUP BY is not in FROM; "
                    + "known classes: " + classAlias.keySet());
            }
        }

        for (OqlQuery.HavingPredicate hp : oql.having()) {
            if (!hp.isAggregate() && hp.className() != null
                    && !classAlias.containsKey(hp.className())) {
                throw new OqlLowerException(
                    "class '" + hp.className() + "' referenced in HAVING is not in FROM; "
                    + "known classes: " + classAlias.keySet());
            }
            if (hp.isAggregate() && hp.aggFn() != OqlQuery.AggFn.COUNT_STAR
                    && hp.className() != null && !classAlias.containsKey(hp.className())) {
                throw new OqlLowerException(
                    "class '" + hp.className() + "' referenced in HAVING aggregate is not in FROM");
            }
        }

        List<AliasedQuery.Projection> aliasedSelect = new ArrayList<>();
        for (OqlQuery.Projection proj : oql.select()) {
            if (proj.isAggregate()) {
                @Nullable String alias = proj.className() != null
                        ? classAlias.get(proj.className()) : null;
                aliasedSelect.add(AliasedQuery.Projection.agg(proj.aggFn(), alias, proj.property(), proj.as()));
            } else if (proj.star()) {
                String alias = classAlias.get(proj.className());
                aliasedSelect.add(AliasedQuery.Projection.star(alias));
            } else {
                String alias = classAlias.get(proj.className());
                aliasedSelect.add(AliasedQuery.Projection.of(alias, proj.property(), proj.as()));
            }
        }

        AliasedQuery.Source source = new AliasedQuery.Source(primaryClass, "t0");

        List<AliasedQuery.Join> joins = new ArrayList<>();
        for (int hop = 0; hop < path.size(); hop++) {
            OqlQuery.PathStep step = path.get(hop);
            joins.add(new AliasedQuery.Join(step.targetClass(), "t" + (hop + 1), step.joinProperty()));
        }

        List<AliasedQuery.Predicate> predicates = new ArrayList<>();
        for (OqlQuery.Filter f : oql.where()) {
            String alias = classAlias.get(f.className());
            predicates.add(new AliasedQuery.Predicate(alias, f.property(), toAliasedOp(f.op()), f.literal(), f.or()));
        }

        List<AliasedQuery.GroupKey> groupBy = new ArrayList<>();
        for (OqlQuery.GroupKey gk : oql.groupBy()) {
            String alias = classAlias.get(gk.className());
            groupBy.add(new AliasedQuery.GroupKey(alias, gk.property()));
        }

        List<AliasedQuery.HavingPredicate> having = new ArrayList<>();
        for (OqlQuery.HavingPredicate hp : oql.having()) {
            @Nullable String alias = hp.className() != null ? classAlias.get(hp.className()) : null;
            having.add(new AliasedQuery.HavingPredicate(
                    hp.aggFn(), alias, hp.property(), toAliasedOp(hp.op()), hp.literal()));
        }

        // A bare key (className null) names a SELECT output, not a class member, so there is no
        // class to check against FROM. The planner resolves it against the projection list.
        for (OqlQuery.OrderKey ok : oql.orderBy()) {
            if (ok.className() != null && !classAlias.containsKey(ok.className()))
                throw new OqlLowerException("class '" + ok.className()
                        + "' in ORDER BY is not in FROM; known: " + classAlias.keySet());
        }
        List<AliasedQuery.OrderKey> orderBy = new ArrayList<>();
        for (OqlQuery.OrderKey ok : oql.orderBy())
            orderBy.add(new AliasedQuery.OrderKey(
                    ok.className() != null ? classAlias.get(ok.className()) : null,
                    ok.property(), ok.desc()));

        return new AliasedQuery(oql.distinct(), aliasedSelect, source, joins, predicates, groupBy, having, orderBy, oql.limit());
    }

    private static String inferPrimaryClass(List<OqlQuery.Projection> projections) {
        // Aggregate-only queries (e.g. SELECT COUNT(*)) have no class in projection
        for (OqlQuery.Projection p : projections) {
            if (p.className() != null) return p.className();
        }
        throw new OqlLowerException("SELECT list has no class reference — cannot infer primary class");
    }

    private static AliasedQuery.Op toAliasedOp(OqlQuery.Op op) {
        return switch (op) {
            case EQ          -> AliasedQuery.Op.EQ;
            case NE          -> AliasedQuery.Op.NE;
            case LT          -> AliasedQuery.Op.LT;
            case LE          -> AliasedQuery.Op.LE;
            case GT          -> AliasedQuery.Op.GT;
            case GE          -> AliasedQuery.Op.GE;
            case IS_NULL     -> AliasedQuery.Op.IS_NULL;
            case IS_NOT_NULL -> AliasedQuery.Op.IS_NOT_NULL;
        };
    }
}
