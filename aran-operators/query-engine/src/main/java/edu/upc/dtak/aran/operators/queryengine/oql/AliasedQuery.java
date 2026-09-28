package edu.upc.dtak.aran.operators.queryengine.oql;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Internal alias-based OQL AST used by {@link OqlPlanner}.
 *
 * <p>Produced by {@link OqlLowerer}. Projections include aggregate functions; group-by keys and
 * having predicates are expressed in terms of synthetic aliases (t0, t1 …).
 * Must not be deleted until Phase 15 (OqlRewriter retirement).
 */
@NullMarked
public final class AliasedQuery {

    public record Source(String className, String alias) {}

    public record Join(String className, String alias, String joinProperty) {}

    /**
     * A projection: plain property, star expansion, or aggregate function.
     * When {@code aggFn != null} the projection is an aggregate call;
     * {@code alias} and {@code property} identify the input field (null for COUNT_STAR).
     */
    public record Projection(
            @Nullable String alias,
            @Nullable String property,
            @Nullable String as,
            boolean star,
            OqlQuery.@Nullable AggFn aggFn
    ) {
        public static Projection of(String alias, String property, @Nullable String as) {
            return new Projection(alias, property, as, false, null);
        }
        public static Projection star(@Nullable String alias) {
            return new Projection(alias, null, null, true, null);
        }
        public static Projection agg(OqlQuery.AggFn fn, @Nullable String alias,
                                      @Nullable String property, @Nullable String as) {
            return new Projection(alias, property, as, false, fn);
        }
        public boolean isAggregate() { return aggFn != null; }
    }

    public enum Op {
        EQ("="), NE("<>"), LT("<"), LE("<="), GT(">"), GE(">="),
        IS_NULL("IS NULL"), IS_NOT_NULL("IS NOT NULL");
        private final String sql;
        Op(String sql) { this.sql = sql; }
        public String sql() { return sql; }
    }

    public record Predicate(String alias, String property, Op op, String literal, boolean or) {
        /** Backward-compatible constructor: or=false. */
        Predicate(String alias, String property, Op op, String literal) {
            this(alias, property, op, literal, false);
        }
    }

    /** A group-by key expressed with a synthetic alias instead of a class name. */
    public record GroupKey(String alias, String property) {}

    /**
     * An ORDER BY key expressed with a synthetic alias, or with a null alias when the key is a
     * bare SELECT output name ({@code ORDER BY n} after {@code COUNT(*) AS n}).
     */
    public record OrderKey(@Nullable String alias, String property, boolean desc) {}

    /**
     * A HAVING predicate expressed with synthetic aliases.
     * Mirrors {@link OqlQuery.HavingPredicate} with className replaced by alias.
     */
    public record HavingPredicate(
            OqlQuery.@Nullable AggFn aggFn,
            @Nullable String alias,
            @Nullable String property,
            Op op,
            String literal
    ) {}

    private final boolean               distinct;
    private final List<Projection>      select;
    private final Source                from;
    private final List<Join>            joins;
    private final List<Predicate>       where;
    private final List<GroupKey>        groupBy;
    private final List<HavingPredicate> having;
    private final List<OrderKey>        orderBy;
    private final @Nullable Integer     limit;

    public AliasedQuery(boolean distinct, List<Projection> select, Source from, List<Join> joins,
                        List<Predicate> where, List<GroupKey> groupBy,
                        List<HavingPredicate> having, List<OrderKey> orderBy,
                        @Nullable Integer limit) {
        this.distinct = distinct;
        this.select  = List.copyOf(select);
        this.from    = from;
        this.joins   = List.copyOf(joins);
        this.where   = List.copyOf(where);
        this.groupBy = List.copyOf(groupBy);
        this.having  = List.copyOf(having);
        this.orderBy = List.copyOf(orderBy);
        this.limit   = limit;
    }

    public boolean               distinct() { return distinct; }
    public List<Projection>      select()  { return select; }
    public Source                from()    { return from; }
    public List<Join>            joins()   { return joins; }
    public List<Predicate>       where()   { return where; }
    public List<GroupKey>        groupBy() { return groupBy; }
    public List<HavingPredicate> having()  { return having; }
    public List<OrderKey>        orderBy() { return orderBy; }
    public @Nullable Integer     limit()   { return limit; }
}
