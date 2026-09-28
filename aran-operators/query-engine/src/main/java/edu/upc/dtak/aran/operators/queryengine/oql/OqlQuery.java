package edu.upc.dtak.aran.operators.queryengine.oql;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * OQL (Aran Query Language) abstract syntax tree.
 *
 * <p>OQL queries identify classes and properties by their local names in G; there are no aliases
 * or variables. Path steps in FROM express joins, GROUP BY and aggregate functions in SELECT
 * enable analytical queries over the integrated views.
 *
 * <pre>
 *   SELECT Artworks.title, IArtist.name AS artist
 *   FROM Artworks -[hasArtist]-&gt; IArtist
 *   WHERE Artworks.title &lt;&gt; 'none'
 *   LIMIT 100
 *
 *   SELECT IResearcher.affiliation, COUNT(*) AS paper_count
 *   FROM IPaper -[hasAuthor]-&gt; IResearcher
 *   GROUP BY IResearcher.affiliation
 *   HAVING COUNT(*) &gt; 2
 * </pre>
 *
 * <p>Single-class queries do not require a FROM clause — the class is inferred from SELECT:
 * <pre>
 *   SELECT Artworks.*
 *   LIMIT 100
 * </pre>
 */
@NullMarked
public final class OqlQuery {

    // ── Aggregate functions ───────────────────────────────────────────────────

    public enum AggFn {
        COUNT_STAR, COUNT, SUM, AVG, MIN, MAX;

        public String display() {
            return this == COUNT_STAR ? "COUNT(*)" : name();
        }
    }

    // ── SELECT projections ────────────────────────────────────────────────────

    /**
     * A projection item: either {@code ClassName.property [AS alias]}, {@code ClassName.*},
     * {@code *} (bare star — all columns of all FROM classes), or an aggregate function.
     */
    public record Projection(
            @Nullable String className,   // null for COUNT_STAR or bare SELECT *
            @Nullable String property,    // null for star or COUNT_STAR
            @Nullable String as,
            boolean star,
            @Nullable AggFn aggFn         // null → plain property or star
    ) {
        public static Projection of(String className, String property, @Nullable String as) {
            return new Projection(className, property, as, false, null);
        }
        public static Projection star(String className) {
            return new Projection(className, null, null, true, null);
        }
        /** Bare {@code SELECT *} — expand all columns of all classes in scope. */
        public static Projection globalStar() {
            return new Projection(null, null, null, true, null);
        }
        /** Aggregate on a specific property: SUM(X.prop), AVG(X.prop), etc. */
        public static Projection agg(AggFn fn, String className, String property, @Nullable String as) {
            return new Projection(className, property, as, false, fn);
        }
        /** COUNT(*) — no class or property reference. */
        public static Projection countStar(@Nullable String as) {
            return new Projection(null, null, as, false, AggFn.COUNT_STAR);
        }

        public boolean isAggregate() { return aggFn != null; }
    }

    // ── FROM path ─────────────────────────────────────────────────────────────

    /** One hop in the FROM path: src -[joinProperty]-&gt; target */
    public record PathStep(String sourceClass, String joinProperty, String targetClass) {}

    // ── WHERE / HAVING ────────────────────────────────────────────────────────

    public enum Op {
        EQ("="), NE("<>"), LT("<"), LE("<="), GT(">"), GE(">="),
        IS_NULL("IS NULL"), IS_NOT_NULL("IS NOT NULL");
        private final String sql;
        Op(String sql) { this.sql = sql; }
        public String sql() { return sql; }
    }

    /**
     * A WHERE filter: {@code ClassName.property OP literal}.
     * {@code or=true} means this filter starts a new OR group (it is OR'd with
     * the preceding AND-group rather than AND'd into it).
     */
    public record Filter(String className, String property, Op op, String literal, boolean or) {
        /** Backward-compatible constructor: or=false (AND with previous). */
        Filter(String className, String property, Op op, String literal) {
            this(className, property, op, literal, false);
        }
    }

    /**
     * A HAVING predicate. Two forms:
     * <ul>
     *   <li>Aggregate: {@code aggFn != null} — e.g. {@code COUNT(*) > 2} or
     *       {@code AVG(X.prop) >= 10}. {@code className} and {@code property} are null for
     *       COUNT_STAR.</li>
     *   <li>Group-key: {@code aggFn == null} — filters on a group-by property,
     *       equivalent to a WHERE pushed below the aggregate.</li>
     * </ul>
     */
    public record HavingPredicate(
            @Nullable AggFn aggFn,
            @Nullable String className,
            @Nullable String property,
            Op op,
            String literal
    ) {
        public static HavingPredicate onAggregate(AggFn fn, @Nullable String className,
                                                  @Nullable String property, Op op, String literal) {
            return new HavingPredicate(fn, className, property, op, literal);
        }
        public static HavingPredicate onProperty(String className, String property,
                                                  Op op, String literal) {
            return new HavingPredicate(null, className, property, op, literal);
        }
        public boolean isAggregate() { return aggFn != null; }
    }

    // ── GROUP BY ──────────────────────────────────────────────────────────────

    public record GroupKey(String className, String property) {}

    // ── ORDER BY ──────────────────────────────────────────────────────────────

    /**
     * An ORDER BY key. {@code className} is null for a bare output alias
     * ({@code ORDER BY n} after {@code COUNT(*) AS n}) — an aggregate's output has no class to
     * qualify it with, which is why this component is nullable rather than the property being
     * overloaded.
     */
    public record OrderKey(@Nullable String className, String property, boolean desc) {}

    // ── OqlQuery ──────────────────────────────────────────────────────────────

    private final boolean distinct;
    private final List<Projection> select;
    private final List<PathStep> path;
    private final @Nullable String explicitFrom;
    private final List<Filter> where;
    private final List<GroupKey> groupBy;
    private final List<HavingPredicate> having;
    private final List<OrderKey> orderBy;
    private final @Nullable Integer limit;

    public OqlQuery(boolean distinct, List<Projection> select, List<PathStep> path,
                    @Nullable String explicitFrom, List<Filter> where, List<GroupKey> groupBy,
                    List<HavingPredicate> having, List<OrderKey> orderBy, @Nullable Integer limit) {
        this.distinct     = distinct;
        this.select       = List.copyOf(select);
        this.path         = List.copyOf(path);
        this.explicitFrom = explicitFrom;
        this.where        = List.copyOf(where);
        this.groupBy      = List.copyOf(groupBy);
        this.having       = List.copyOf(having);
        this.orderBy      = List.copyOf(orderBy);
        this.limit        = limit;
    }

    public boolean          distinct()     { return distinct; }
    public List<Projection> select()       { return select; }
    public List<PathStep>   path()         { return path; }
    /** The class explicitly named in FROM, if any (even when there are no join steps). */
    public @Nullable String explicitFrom() { return explicitFrom; }
    public List<Filter>     where()        { return where; }
    public List<GroupKey>   groupBy()      { return groupBy; }
    public List<HavingPredicate> having()  { return having; }
    public List<OrderKey>   orderBy()      { return orderBy; }
    public @Nullable Integer limit()       { return limit; }
}
