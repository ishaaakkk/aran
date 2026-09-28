package edu.upc.dtak.aran.core.source.engines;

import edu.upc.dtak.aran.core.source.Harvest.ColKey;
import edu.upc.dtak.aran.core.source.Harvest.ForeignKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure — every case here feeds synthetic rows straight to the parser, the same shape {@code
 * JdbcCatalogHarvester} would hand over from a real {@code ResultSet}, without a database. The
 * live, Testcontainers-backed proof that PostgreSQL's {@code pg_constraint} catalog actually returns
 * rows shaped like this — the composite {@code conkey}/{@code confkey} pairing and the same-named
 * constraints on two tables the OID identity keeps apart — lives in {@code
 * RelationalCatalogHarvestServiceTest} (aran-operators/bootstrapping).
 */
class PostgresCatalogHarvestQueriesTest {

    private final PostgresCatalogHarvestQueries queries = new PostgresCatalogHarvestQueries();

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    @Nested
    @DisplayName("SQL text")
    class Sql {

        @Test
        @DisplayName("row count / NDV / FK SQL all scope to the given schema")
        void scoped_to_schema() {
            assertThat(queries.rowCountSql("public")).contains("nspname = 'public'");
            assertThat(queries.ndvSql("public")).contains("schemaname = 'public'");
            // Both FK queries now scope by the referencing constraint's own namespace (pg_constraint
            // via pg_namespace), not information_schema's table_schema/constraint_schema.
            assertThat(queries.foreignKeyFromColumnsSql("public")).contains("nspname = 'public'");
            assertThat(queries.foreignKeyToColumnsSql("public")).contains("nspname = 'public'");
        }

        @Test
        @DisplayName("a schema name containing a quote is escaped, not concatenated raw")
        void schema_is_escaped() {
            // Same injection concern RelationalEnginesTest asserts for federatedScan's credentials.
            String sql = queries.rowCountSql("weird'schema");
            assertThat(sql).contains("weird''schema").doesNotContain("weird'schema'");
        }

        @Test
        @DisplayName("only catalog views are read — pg_class, pg_stats, pg_constraint")
        void reads_catalog_views_only() {
            assertThat(queries.rowCountSql("public")).contains("pg_class");
            assertThat(queries.ndvSql("public")).contains("pg_stats");
            // FK harvest reads pg_constraint (OID-based identity), not information_schema — see the
            // class javadoc for why information_schema's name-based joins are unsound on PostgreSQL.
            assertThat(queries.foreignKeyFromColumnsSql("public"))
                    .contains("pg_constraint")
                    .doesNotContain("information_schema");
            assertThat(queries.foreignKeyToColumnsSql("public"))
                    .contains("pg_constraint")
                    .doesNotContain("information_schema");
        }

        @Test
        @DisplayName("both FK queries carry the constraint OID as identity and a shared pairing ordinal")
        void fk_queries_carry_oid_identity_and_a_pairing_ordinal() {
            // The bug this exists to prevent: identifying a foreign key by constraint name alone
            // merges two same-named constraints on different tables (a name is unique only per table
            // on PostgreSQL). con.oid is the table-unique identity; unnest(conkey/confkey) WITH
            // ORDINALITY gives the shared per-position pairing ordinal.
            assertThat(queries.foreignKeyFromColumnsSql("public"))
                    .contains("con.oid AS constraint_id")
                    .contains("con.conrelid")
                    .contains("unnest(con.conkey) WITH ORDINALITY");
            assertThat(queries.foreignKeyToColumnsSql("public"))
                    .contains("con.oid AS constraint_id")
                    .contains("con.confrelid")
                    .contains("unnest(con.confkey) WITH ORDINALITY");
        }
    }

    @Nested
    @DisplayName("row counts")
    class RowCounts {

        @Test
        @DisplayName("reltuples becomes a row count by table name")
        void basic() {
            Map<String, Long> counts = queries.parseRowCounts(List.of(
                    row("table_name", "employees", "row_count", 900.0f),
                    row("table_name", "departments", "row_count", 5.0f)));

            assertThat(counts).containsEntry("employees", 900L).containsEntry("departments", 5L);
        }

        @Test
        @DisplayName("a negative reltuples (never vacuumed) is omitted, not reported as zero")
        void negative_is_omitted() {
            Map<String, Long> counts = queries.parseRowCounts(List.of(
                    row("table_name", "fresh", "row_count", -1.0),
                    row("table_name", "employees", "row_count", 900.0)));

            assertThat(counts).doesNotContainKey("fresh").containsEntry("employees", 900L);
        }

        @Test
        @DisplayName("no rows means no tables — not a failure")
        void empty_is_empty() {
            assertThat(queries.parseRowCounts(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("column NDV — the n_distinct sign")
    class Ndv {

        @Test
        @DisplayName("a positive n_distinct is an absolute count, unaffected by row count")
        void positive_is_absolute() {
            Map<ColKey, Long> ndv = queries.parseNdv(List.of(
                    row("table_name", "employees", "column_name", "status",
                            "n_distinct", 3.0f, "row_count", 900.0f)));

            assertThat(ndv).containsEntry(new ColKey("employees", "status"), 3L);
        }

        @Test
        @DisplayName("n_distinct = -1 means every row is distinct: NDV equals the row count")
        void minus_one_means_unique() {
            Map<ColKey, Long> ndv = queries.parseNdv(List.of(
                    row("table_name", "employees", "column_name", "id",
                            "n_distinct", -1.0f, "row_count", 900.0f)));

            assertThat(ndv).containsEntry(new ColKey("employees", "id"), 900L);
        }

        @Test
        @DisplayName("a fractional negative n_distinct is a ratio of the row count")
        void fractional_ratio() {
            Map<ColKey, Long> ndv = queries.parseNdv(List.of(
                    row("table_name", "t", "column_name", "c", "n_distinct", -0.25, "row_count", 400.0)));

            assertThat(ndv).containsEntry(new ColKey("t", "c"), 100L);
        }

        @Test
        @DisplayName("n_distinct = 0 is reported as zero, not omitted")
        void zero_is_reported_as_zero() {
            Map<ColKey, Long> ndv = queries.parseNdv(List.of(
                    row("table_name", "t", "column_name", "c", "n_distinct", 0.0, "row_count", 500.0)));

            assertThat(ndv).containsEntry(new ColKey("t", "c"), 0L);
        }

        @Test
        @DisplayName("a negative ratio with an unknown row count is omitted, not a nonsense value")
        void negative_ratio_with_unknown_row_count_is_omitted() {
            // row_count itself carries pg_class's "never analyzed" sentinel here (see RowCounts'
            // negative_is_omitted) — there is no honest row count to multiply the ratio by.
            Map<ColKey, Long> ndv = queries.parseNdv(List.of(
                    row("table_name", "t", "column_name", "c", "n_distinct", -1.0, "row_count", -1.0)));

            assertThat(ndv).doesNotContainKey(new ColKey("t", "c"));
        }

        @Test
        @DisplayName("a table ANALYZE never touched simply has no pg_stats rows — absent, not zero")
        void no_rows_means_no_entries() {
            assertThat(queries.parseNdv(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("column NULL fraction — pg_stats.null_frac")
    class NullFractions {

        @Test
        @DisplayName("the NDV query reads null_frac, and each row's value is that column's fraction")
        void read_from_the_ndv_rows() {
            assertThat(queries.ndvSql("public")).contains("null_frac");

            Map<ColKey, Double> fractions = queries.parseNullFractions(List.of(
                    row("table_name", "employees", "column_name", "manager_id",
                            "n_distinct", 40.0f, "null_frac", 0.25f, "row_count", 900.0f),
                    row("table_name", "employees", "column_name", "id",
                            "n_distinct", -1.0f, "null_frac", 0.0f, "row_count", 900.0f)));

            assertThat(fractions)
                    .containsEntry(new ColKey("employees", "manager_id"), 0.25)
                    .containsEntry(new ColKey("employees", "id"), 0.0);
        }

        @Test
        @DisplayName("a row without a null_frac is absent, not zero")
        void missing_is_absent() {
            assertThat(queries.parseNullFractions(List.of(
                    row("table_name", "t", "column_name", "c", "n_distinct", 3.0, "row_count", 9.0))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("declared foreign keys")
    class ForeignKeys {

        @Test
        @DisplayName("a single-column FK zips the one from-row to the one to-row")
        void single_column() {
            List<ForeignKey> fks = queries.parseForeignKeys(
                    List.of(row("constraint_id", 1L, "constraint_name", "employees_dept_id_fkey",
                            "from_table", "employees", "from_column", "dept_id",
                            "ordinal_position", 1, "to_ordinal", 1)),
                    List.of(row("constraint_id", 1L, "constraint_name", "employees_dept_id_fkey",
                            "to_table", "departments", "to_column", "id", "to_ordinal", 1)));

            assertThat(fks).containsExactly(
                    new ForeignKey("employees", List.of("dept_id"), "departments", List.of("id")));
        }

        @Test
        @DisplayName("a composite FK pairs by ordinal even when the referenced side arrives out of declared order")
        void composite_pairs_by_ordinal_not_row_order() {
            // parent(z, a) PRIMARY KEY (z, a) — z is confkey-position 1, a is confkey-position 2 —
            // and child(pz, pa) REFERENCES parent(z, a), so pz -> z (ordinal 1), pa -> a (ordinal
            // 2). The to-side rows are fed in the "wrong" order (ordinal 2 first) on purpose: an
            // ordinal-keyed lookup pairs correctly where a positional zip would swap them.
            List<ForeignKey> fks = queries.parseForeignKeys(
                    List.of(
                            row("constraint_id", 2L, "constraint_name", "fk_composite", "from_table", "child",
                                    "from_column", "pz", "ordinal_position", 1, "to_ordinal", 1),
                            row("constraint_id", 2L, "constraint_name", "fk_composite", "from_table", "child",
                                    "from_column", "pa", "ordinal_position", 2, "to_ordinal", 2)),
                    List.of(
                            row("constraint_id", 2L, "constraint_name", "fk_composite", "to_table", "parent",
                                    "to_column", "a", "to_ordinal", 2),
                            row("constraint_id", 2L, "constraint_name", "fk_composite", "to_table", "parent",
                                    "to_column", "z", "to_ordinal", 1)));

            assertThat(fks).containsExactly(
                    new ForeignKey("child", List.of("pz", "pa"), "parent", List.of("z", "a")));
        }

        @Test
        @DisplayName("from-side columns are sorted by their own ordinal, even if rows arrive out of order")
        void from_side_is_sorted_by_ordinal_even_out_of_order() {
            // Fed "2, then 1" on purpose: foreignKeyFromColumnsSql's ORDER BY should already
            // guarantee this, but parseForeignKeys re-sorts rather than trust row-arrival order —
            // the same principle the referenced-side pairing applies, applied symmetrically.
            List<ForeignKey> fks = queries.parseForeignKeys(
                    List.of(
                            row("constraint_id", 3L, "constraint_name", "fk", "from_table", "t1",
                                    "from_column", "second", "ordinal_position", 2, "to_ordinal", 2),
                            row("constraint_id", 3L, "constraint_name", "fk", "from_table", "t1",
                                    "from_column", "first", "ordinal_position", 1, "to_ordinal", 1)),
                    List.of(
                            row("constraint_id", 3L, "constraint_name", "fk", "to_table", "t2",
                                    "to_column", "x", "to_ordinal", 1),
                            row("constraint_id", 3L, "constraint_name", "fk", "to_table", "t2",
                                    "to_column", "y", "to_ordinal", 2)));

            assertThat(fks).containsExactly(
                    new ForeignKey("t1", List.of("first", "second"), "t2", List.of("x", "y")));
        }

        @Test
        @DisplayName("two tables each carrying a same-named constraint are NEVER cross-attributed — the OID keeps them apart")
        void same_named_constraints_on_two_tables_are_not_cross_attributed() {
            // The exact bug the pg_constraint switch fixes: `orders` and `invoices` each declare a
            // CONSTRAINT named `fk_customer`, referencing DIFFERENT tables (customers vs vendors).
            // Grouping by constraint_name alone merged them and attributed both to whichever arrived
            // first; grouping by the constraint OID (con.oid) keeps them entirely separate, so each
            // is harvested against its own correct table.
            List<ForeignKey> fks = queries.parseForeignKeys(
                    List.of(
                            row("constraint_id", 100L, "constraint_name", "fk_customer", "from_table", "orders",
                                    "from_column", "customer_id", "ordinal_position", 1, "to_ordinal", 1),
                            row("constraint_id", 200L, "constraint_name", "fk_customer", "from_table", "invoices",
                                    "from_column", "vendor_id", "ordinal_position", 1, "to_ordinal", 1)),
                    List.of(
                            row("constraint_id", 100L, "constraint_name", "fk_customer", "to_table", "customers",
                                    "to_column", "id", "to_ordinal", 1),
                            row("constraint_id", 200L, "constraint_name", "fk_customer", "to_table", "vendors",
                                    "to_column", "id", "to_ordinal", 1)));

            assertThat(fks).containsExactlyInAnyOrder(
                    new ForeignKey("orders", List.of("customer_id"), "customers", List.of("id")),
                    new ForeignKey("invoices", List.of("vendor_id"), "vendors", List.of("id")));
        }

        @Test
        @DisplayName("a referencing column whose ordinal matches no referenced column skips that FK")
        void unmatched_ordinal_is_skipped() {
            List<ForeignKey> fks = queries.parseForeignKeys(
                    List.of(
                            row("constraint_id", 4L, "constraint_name", "fk_bad", "from_table", "t1",
                                    "from_column", "a", "ordinal_position", 1, "to_ordinal", 1),
                            row("constraint_id", 4L, "constraint_name", "fk_bad", "from_table", "t1",
                                    "from_column", "b", "ordinal_position", 2, "to_ordinal", 2)),
                    // Only referenced-ordinal 1 is present; ordinal 2 (needed by column "b") is not.
                    List.of(row("constraint_id", 4L, "constraint_name", "fk_bad", "to_table", "t2",
                            "to_column", "x", "to_ordinal", 1)));

            assertThat(fks).isEmpty();
        }

        @Test
        @DisplayName("two different referenced columns claiming the same ordinal skips that FK rather than guessing")
        void ambiguous_referenced_ordinal_is_skipped() {
            List<ForeignKey> fks = queries.parseForeignKeys(
                    List.of(row("constraint_id", 5L, "constraint_name", "fk_ambiguous", "from_table", "t1",
                            "from_column", "a", "ordinal_position", 1, "to_ordinal", 1)),
                    List.of(
                            row("constraint_id", 5L, "constraint_name", "fk_ambiguous", "to_table", "t2",
                                    "to_column", "x", "to_ordinal", 1),
                            row("constraint_id", 5L, "constraint_name", "fk_ambiguous", "to_table", "t2",
                                    "to_column", "y", "to_ordinal", 1)));

            assertThat(fks).isEmpty();
        }

        @Test
        @DisplayName("a null referencing ordinal skips the WHOLE FK, never just that column (no shrunken composite key)")
        void null_from_ordinal_skips_the_whole_fk() {
            // Minor 6: a malformed from-row (here a null ordinal on the second column) must drop the
            // entire foreign key. Keeping the first column and dropping the second would harvest a
            // NARROWER key than the source declares — an over-claim the DECLARED tier must never make.
            List<ForeignKey> fks = queries.parseForeignKeys(
                    List.of(
                            row("constraint_id", 6L, "constraint_name", "fk_partial", "from_table", "t1",
                                    "from_column", "a", "ordinal_position", 1, "to_ordinal", 1),
                            row("constraint_id", 6L, "constraint_name", "fk_partial", "from_table", "t1",
                                    "from_column", "b", "ordinal_position", null, "to_ordinal", 2)),
                    List.of(
                            row("constraint_id", 6L, "constraint_name", "fk_partial", "to_table", "t2",
                                    "to_column", "x", "to_ordinal", 1),
                            row("constraint_id", 6L, "constraint_name", "fk_partial", "to_table", "t2",
                                    "to_column", "y", "to_ordinal", 2)));

            assertThat(fks).isEmpty();
        }

        @Test
        @DisplayName("one OID resolving to two distinct referencing tables is skipped, never attributed to the first")
        void distinct_from_tables_for_one_oid_is_skipped() {
            // Defensive: impossible against a real pg_constraint (conrelid is a single OID), but the
            // parser must refuse rather than pick whichever table arrived first if it ever appears.
            List<ForeignKey> fks = queries.parseForeignKeys(
                    List.of(
                            row("constraint_id", 7L, "constraint_name", "fk_weird", "from_table", "t1",
                                    "from_column", "a", "ordinal_position", 1, "to_ordinal", 1),
                            row("constraint_id", 7L, "constraint_name", "fk_weird", "from_table", "t2",
                                    "from_column", "a", "ordinal_position", 2, "to_ordinal", 2)),
                    List.of(
                            row("constraint_id", 7L, "constraint_name", "fk_weird", "to_table", "u",
                                    "to_column", "x", "to_ordinal", 1),
                            row("constraint_id", 7L, "constraint_name", "fk_weird", "to_table", "u",
                                    "to_column", "y", "to_ordinal", 2)));

            assertThat(fks).isEmpty();
        }

        @Test
        @DisplayName("no declared foreign keys is an empty list, not a failure")
        void empty_is_empty() {
            assertThat(queries.parseForeignKeys(List.of(), List.of())).isEmpty();
        }
    }
}
