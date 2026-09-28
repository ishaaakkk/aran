package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.sql.type.SqlTypeName;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * XSD range → SQL type mapping, and the widen lattice a multi-source integrated class folds
 * its per-source ranges through.
 *
 * <p>The rule: where sources of one class disagree, widen; fall back to VARCHAR per column,
 * never fail compile — union type alignment stays by construction. Every method here holds it:
 * an XSD range this class does not recognise, a range that is simply absent, and two sources
 * whose ranges cannot be reconciled all degrade to VARCHAR rather than raising. A DuckDB
 * {@code UNION ALL} across the sources needs every branch to agree on a column's type, and
 * VARCHAR is the one type every value can always be read as.
 */
@NullMarked
public final class XsdTypes {

    private XsdTypes() {
    }

    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";

    /**
     * Maps an XSD datatype IRI (as bound to {@code rdfs:range} and read via
     * {@code MappingCatalog.SourceView.columnTypes()}) to the Calcite type it becomes.
     *
     * <p>{@code null} — no range was recorded for the column — and any IRI this table does not
     * recognise both fall back to VARCHAR: neither is evidence the value is narrower than text.
     */
    public static SqlTypeName fromXsd(@Nullable String xsdIri) {
        if (xsdIri == null) {
            return SqlTypeName.VARCHAR;
        }
        String local = xsdIri.startsWith(XSD_NS) ? xsdIri.substring(XSD_NS.length()) : xsdIri;
        return switch (local) {
            case "string" -> SqlTypeName.VARCHAR;
            case "integer", "int", "long", "short", "byte",
                 "nonNegativeInteger", "positiveInteger" -> SqlTypeName.BIGINT;
            case "decimal" -> SqlTypeName.DECIMAL;
            case "double", "float" -> SqlTypeName.DOUBLE;
            case "boolean" -> SqlTypeName.BOOLEAN;
            case "date" -> SqlTypeName.DATE;
            case "dateTime" -> SqlTypeName.TIMESTAMP;
            default -> SqlTypeName.VARCHAR;
        };
    }

    /**
     * Combines two sources' types for the same global column into the one type both can be
     * read as. Equal types need no widening. The three numeric pairs promote to the wider
     * numeric type (a UNION of BIGINT and DOUBLE rows reads cleanly as DOUBLE). Every other
     * disagreement — including DATE vs TIMESTAMP, which a looser rule might also promote —
     * falls back to VARCHAR: the rule is deliberately closed-world rather than "widen whatever
     * looks safe", so a new kind of disagreement degrades gracefully instead of guessing.
     */
    public static SqlTypeName widen(SqlTypeName a, SqlTypeName b) {
        if (a == b) {
            return a;
        }
        if (isPair(a, b, SqlTypeName.BIGINT, SqlTypeName.DECIMAL)) {
            return SqlTypeName.DECIMAL;
        }
        if (isPair(a, b, SqlTypeName.BIGINT, SqlTypeName.DOUBLE)
                || isPair(a, b, SqlTypeName.DECIMAL, SqlTypeName.DOUBLE)) {
            return SqlTypeName.DOUBLE;
        }
        return SqlTypeName.VARCHAR;
    }

    private static boolean isPair(SqlTypeName a, SqlTypeName b, SqlTypeName x, SqlTypeName y) {
        return (a == x && b == y) || (a == y && b == x);
    }

    /**
     * The DuckDB column type a widened {@link SqlTypeName} is declared as in view DDL.
     *
     * <p>DECIMAL(38,9): 38 is DuckDB's maximum precision; a 9-digit scale leaves 29 integer
     * digits, comfortably wider than BIGINT's 19, so promoting BIGINT into DECIMAL never loses
     * range. Scale-9 rounding for a genuine NUMERIC/DECIMAL source with more than 9 fractional
     * digits is accepted until phase 4 harvests real precision/scale from the catalog. Any type
     * this class does not otherwise widen to (including VARCHAR itself) is DuckDB's VARCHAR —
     * the same "never fail" fallback {@link #fromXsd} and {@link #widen} use.
     */
    public static String duckType(SqlTypeName t) {
        return switch (t) {
            case BIGINT -> "BIGINT";
            case DECIMAL -> "DECIMAL(38,9)";
            case DOUBLE -> "DOUBLE";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP";
            default -> "VARCHAR";
        };
    }

    /**
     * The single type an integrated class's column {@code globalIri} should be declared as,
     * folding every source's recorded XSD range through {@link #widen}.
     *
     * <p>Every source in {@code cls.sources()} contributes, even one whose
     * {@code columnTypes()} has no entry for {@code globalIri} — whether because that source
     * has no range recorded for it, or because the source has no such column at all. Either
     * way {@link #fromXsd} reads the missing entry as VARCHAR: "unknown" and "absent" must both
     * fail to sharpen the type, or a source silently missing type info would let the other
     * sources' opinion win instead of being treated as a disagreement.
     */
    public static SqlTypeName widenedType(MappingCatalog.IntegratedClass cls, String globalIri) {
        return cls.sources().stream()
                .map(sv -> fromXsd(sv.columnTypes().get(globalIri)))
                .reduce(XsdTypes::widen)
                .orElse(SqlTypeName.VARCHAR);
    }
}
