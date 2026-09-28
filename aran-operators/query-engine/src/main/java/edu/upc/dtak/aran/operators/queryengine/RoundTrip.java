package edu.upc.dtak.aran.operators.queryengine;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The compile-time round-trip oracle for a candidate wrapper (Phase 3A): does the user's own SQL
 * text and Calcite's unparse of {@link edu.upc.dtak.aran.operators.queryengine.plan.WrapperExpansion}'s
 * probed tree produce the same rows against the real snapshot?
 *
 * <p>Structural comparison of the two {@code RelNode} trees was the tempting cheap oracle and is
 * too brittle to be one — the same rows have many syntactically different but semantically
 * identical plans, so a structural diff would fail wrappers a human would call equivalent
 * (column order, redundant casts, an {@code AS} the unparser spells out that the user omitted).
 * Comparing <em>results</em> on real data is the honest oracle instead: run both texts over
 * the same {@code src_} relation and compare what came back.
 *
 * <p>The comparison itself is a fingerprint, not a full result diff — {@link #fingerprintSql} is
 * {@code COUNT(*)} plus an order-insensitive {@code SUM(hash(row))} — so neither side has to be
 * materialised, sorted or streamed alongside the other; it costs one extra cheap aggregate query
 * per side. <b>This is probabilistic, by design, and deliberately so.</b> A hash collision (or an
 * unrelated set of row changes whose per-row hashes happen to sum to the same total) would let a
 * genuinely different expansion pass as {@code EXPANDED}. The design spec names this precedent
 * explicitly rather than treating it as an oversight (same doc, §9 risk 1: "COUNT + sum-of-row-hash
 * is probabilistic; acceptable for a performance gate, stated as such") — the practical collision
 * probability of DuckDB's 64-bit {@code hash()} summed over any realistic row count is negligible
 * next to the other ways a wrapper can be wrong, and every failure mode this class does not cover
 * (a wrapper that never plans at all, an operational error resolving the snapshot, a surprise at
 * plan time) still falls back to the verbatim, byte-identical {@code OPAQUE} path — this check
 * only ever unlocks an optimization, it never gates correctness on its own.
 */
@NullMarked
public final class RoundTrip {

    private RoundTrip() {
    }

    /**
     * The fingerprint query for {@code innerSql}: a single row carrying the result's row count
     * and an order-insensitive hash of its rows, so two differently-shaped queries producing the
     * same multiset of rows fingerprint identically regardless of the order either returned them.
     *
     * <p>{@code hash("__aran_rt")} hashes the whole row the alias stands for (DuckDB hashes a
     * table/subquery alias as the struct of its columns) rather than any single column, so the
     * fingerprint is sensitive to every column the inner query projects. The alias is the
     * deliberately unshadowable {@code "__aran_rt"} rather than the shorter {@code t} a reader
     * might reach for: DuckDB's name resolution prefers a COLUMN over the enclosing row-struct
     * alias when the two names collide, so a plain {@code t} silently rebinds {@code hash(t)} to
     * a column literally named {@code t} the instant the inner query happens to project one —
     * collapsing the oracle to {@code COUNT} plus that one column's hash and hiding every other
     * column's difference entirely (see {@code RoundTripTest
     * .columnLiterallyNamedTNoLongerShadowsTheRowAlias}, which reproduces the exact collision
     * against the old alias). {@code "__aran_rt"} is not a name any real wrapper's SELECT list
     * plausibly emits, which is the only property this alias needs.
     *
     * <p>{@code SUM} rather than any order-sensitive combinator is what makes the fingerprint
     * independent of row order — the property {@link #verify} relies on when the user's text and
     * the expansion's unparse do not promise to return rows in the same order. {@code COALESCE(…,
     * 0)} turns the "zero rows" case into a concrete, comparable value: an aggregate {@code SUM}
     * over no input rows is SQL {@code NULL}, and pinning it to {@code 0} means an empty result
     * compares equal to another empty result without leaning on how any particular JDBC driver or
     * Java type happens to treat two {@code NULL} reads as "equal".
     *
     * <p>The exact string is pinned ({@code RoundTripTest.fingerprintSqlIsPinned}) because it is
     * spliced verbatim into a query {@link #verify} executes against a real DuckDB connection —
     * a drifted copy would not be caught by a type system, only by that query failing or lying.
     */
    public static String fingerprintSql(String innerSql) {
        return "SELECT COUNT(*) AS c, COALESCE(SUM(hash(\"__aran_rt\")), 0) AS h FROM ("
                + innerSql + ") AS \"__aran_rt\"";
    }

    /**
     * The round-trip outcome: {@code EXPANDED} when both fingerprints matched, else
     * {@code OPAQUE} with a human-readable reason. {@code reason} is {@code null} exactly when
     * {@code status} is {@code EXPANDED} — there is nothing to explain about a match.
     */
    public record Verdict(String status, @Nullable String reason) {
    }

    /**
     * Fingerprints {@code userSql} and {@code unparsedSql} on {@code duckdb} and compares them.
     *
     * @param duckdb      an open connection whose schema already contains whatever tables/views
     *                    both {@code userSql} and {@code unparsedSql} reference (the caller's
     *                    job — this class only runs the two fingerprint queries and compares).
     * @param userSql     the candidate wrapper's own text, with its {@code ${SNAPSHOT}}
     *                    placeholder already substituted for a real relation.
     * @param unparsedSql {@code PlanExecutor.toSqlFromOptimized}'s unparse of
     *                    {@code WrapperExpansion.probe}'s tree for the same wrapper.
     * @throws RuntimeException if either fingerprint query fails to execute — the caller (the
     *                          {@code verify-wrapper} operation) is expected to treat that as an
     *                          operational failure and fold it into an {@code OPAQUE} verdict of
     *                          its own, the same as a missing snapshot; this method does not do
     *                          that folding itself so it stays a pure, unit-testable comparator.
     */
    public static Verdict verify(Connection duckdb, String userSql, String unparsedSql) {
        Fingerprint user = fingerprint(duckdb, userSql);
        Fingerprint expansion = fingerprint(duckdb, unparsedSql);
        if (user.equals(expansion)) {
            return new Verdict("EXPANDED", null);
        }
        return new Verdict("OPAQUE", "round-trip mismatch: user SQL fingerprinted to (count="
                + user.count + ", hash=" + user.hash + ") but the expansion fingerprinted to"
                + " (count=" + expansion.count + ", hash=" + expansion.hash + ")");
    }

    /**
     * {@code (count, hash)} read back as strings rather than as numbers on purpose: DuckDB has
     * changed which integer type it picks for {@code SUM}/{@code hash()} results across engine
     * versions (BIGINT in 1.3, HUGEINT in 1.5 — see {@code CorrectnessHarnessTest}), and a Java
     * {@code Long} does not {@code equals()} a {@code BigInteger} carrying the identical value.
     * Both fingerprints run the same query shape on the same connection, so whichever Java type
     * the driver picks, it picks consistently on both sides — comparing their decimal string
     * forms sidesteps the type entirely instead of chasing the driver's version-to-version choice.
     */
    private record Fingerprint(String count, String hash) {
    }

    private static Fingerprint fingerprint(Connection duckdb, String sql) {
        try (Statement st = duckdb.createStatement();
             ResultSet rs = st.executeQuery(fingerprintSql(sql))) {
            rs.next(); // a bare COUNT/SUM aggregate with no GROUP BY always returns one row
            return new Fingerprint(rs.getString(1), rs.getString(2));
        } catch (SQLException e) {
            throw new RuntimeException("Round-trip fingerprint query failed: " + e.getMessage(), e);
        }
    }
}
