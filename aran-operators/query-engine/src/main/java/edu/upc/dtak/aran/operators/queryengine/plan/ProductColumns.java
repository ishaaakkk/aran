package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ProductColumn;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.type.SqlTypeName;
import org.jspecify.annotations.NullMarked;

/**
 * {@link RelDataType} → {@link ProductColumn}: the Calcite-side half of the registry's column
 * shape, used where there is no parquet file for {@code ProductSqlExecutor.describeParquet} to
 * read a {@code ResultSetMetaData} off of.
 *
 * <p>A MATERIALIZED product still describes its columns post-hoc, from the real DuckDB driver
 * reading the file {@code materialize-oql} wrote — that path is untouched. A VIRTUAL product
 * (Phase 5) has no file, ever, so its {@code output_schema} is derived here instead, straight
 * from the optimizer's own {@code RelNode#getRowType()} at plan time (see {@code rewrite-oql}
 * in {@code QueryEngineOperator}, the only caller). Field order and field name both come
 * straight off the row type, and this is never a positional placeholder for an OQL query, only
 * one of two human-meaningful shapes: {@code OqlPlanner.buildProject} names an explicit
 * projection's output from the OQL's own local property name or its {@code AS} alias (so
 * {@code SELECT Class.prop} yields {@code "prop"}, matching the SQL {@code materialize-oql}
 * would run for the same query — see {@code ProductColumnsParityTest}); only star-expansion
 * ({@code Class.*}) falls back to the full global property IRI (see
 * {@code OqlPlannerTest#starExpansionProducesAllColumns}), because there is no per-column alias
 * to draw a shorter name from.
 *
 * <p>{@code jdbcType} is {@link SqlTypeName#getJdbcOrdinal()} — Calcite already carries the
 * {@link java.sql.Types} code for every type it knows, so there is nothing to hand-roll there.
 * {@code typeName} has no equivalent shared source: Calcite's enum name and DuckDB's own
 * driver-reported name are two different vocabularies. They agree on the common cases this
 * system actually produces — VARCHAR, BIGINT, DOUBLE, BOOLEAN, DATE, TIMESTAMP, the exact set
 * {@link XsdTypes} widens every column to — and are not contractually required to agree beyond
 * that (DECIMAL is the one case in that set where they visibly diverge: DuckDB's driver reports
 * precision/scale, e.g. {@code DECIMAL(38,9)}, Calcite's bare enum name does not). A product's
 * registry entry is metadata for a person browsing a schema, not a string another system parses,
 * so this divergence is accepted rather than chased — see the parity test in
 * {@code ProductColumnsParityTest} for what is and is not asserted identical.
 */
@NullMarked
public final class ProductColumns {

    private ProductColumns() {
    }

    /** The columns {@code rowType} describes, in field order. */
    public static List<ProductColumn> of(RelDataType rowType) {
        List<ProductColumn> columns = new ArrayList<>();
        for (RelDataTypeField field : rowType.getFieldList()) {
            SqlTypeName sqlType = field.getType().getSqlTypeName();
            columns.add(new ProductColumn(
                    field.getName(), duckDbTypeName(sqlType), sqlType.getJdbcOrdinal()));
        }
        return columns;
    }

    /**
     * The DuckDB type name closest to {@code type} — good enough for a schema browser, not a
     * promise of matching the DuckDB driver's own spelling byte-for-byte (see the class javadoc).
     * Every case not listed explicitly — MULTISET, SYMBOL, a future Calcite addition, whatever
     * this planner never actually emits today — falls back to VARCHAR rather than throwing,
     * the same "never fail on an unrecognised type" rule {@link XsdTypes} uses.
     */
    private static String duckDbTypeName(SqlTypeName type) {
        return switch (type) {
            case BOOLEAN -> "BOOLEAN";
            case TINYINT -> "TINYINT";
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case DECIMAL -> "DECIMAL";
            case FLOAT, REAL -> "REAL";
            case DOUBLE -> "DOUBLE";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE -> "TIMESTAMP";
            case VARCHAR, CHAR -> "VARCHAR";
            case VARBINARY, BINARY -> "BLOB";
            default -> "VARCHAR";
        };
    }
}
