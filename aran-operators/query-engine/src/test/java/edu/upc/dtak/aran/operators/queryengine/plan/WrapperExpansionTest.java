package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 3A expansion probe: does a wrapper's own SQL plan cleanly over its raw
 * {@code src_} relation?
 *
 * <p>{@link MappingCatalog.SourceView} fixtures here follow the shape
 * {@code MappingCatalogOperator} actually produces (see {@code MappingCatalogTest}):
 * {@code sourceRefs} carries raw physical column name → source-property IRI, and
 * {@code columnTypes} carries property IRI → XSD range — never the raw name directly.
 */
class WrapperExpansionTest {

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private static MappingCatalog.SourceView sourceView(
            String datasetId, String wrapperSql, Map<String, String> sourceRefs) {
        return sourceView(datasetId, wrapperSql, sourceRefs, Map.of());
    }

    private static MappingCatalog.SourceView sourceView(
            String datasetId, String wrapperSql, Map<String, String> sourceRefs,
            Map<String, String> columnTypes) {
        return new MappingCatalog.SourceView(
                datasetId, wrapperSql, Map.of(), 0L, List.of(), "",
                columnTypes, sourceRefs, "OPAQUE");
    }

    /** A profile carrying only the fields {@link SrcTable} reads — mirrors WrapperStatisticTest. */
    private static ColumnProfile profile(String columnName, long cardinality, double incompleteness) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), columnName, 0, "VARCHAR",
                cardinality, null, 0.0, incompleteness,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    // ── (a) a generated-style pure projection expands ──────────────────────────

    @Test
    @DisplayName("a pure projection over ${SNAPSHOT} probes to a tree whose leaf scans src_<dataset>")
    void pureProjectionExpandsToScanOfSrcRelation() {
        var sv = sourceView("ds1",
                "SELECT \"a\" AS x, \"b\" AS y FROM ${SNAPSHOT}",
                Map.of("a", "http://ex/a", "b", "http://ex/b"));

        Optional<RelNode> result = WrapperExpansion.probe(sv);

        assertThat(result).isPresent();
        assertThat(PlanShape.of(result.get()).toCompactString())
                .as("the probe's plan bottoms out at the src_ relation, not the class's global columns")
                .contains("SCAN[src_ds1]");
    }

    // ── (b) an unknown column never plans ───────────────────────────────────────

    @Test
    @DisplayName("a wrapper referencing a column the catalog never recorded a sourceRef for stays empty")
    void unknownColumnReferenceIsEmpty() {
        var sv = sourceView("ds2",
                "SELECT \"nope\" FROM ${SNAPSHOT}",
                Map.of("a", "http://ex/a"));

        assertThat(WrapperExpansion.probe(sv)).isEmpty();
    }

    // ── (c) DuckDB-only syntax fails closed, never throws ───────────────────────

    @Test
    @DisplayName("DuckDB-only syntax the Calcite parser rejects returns empty rather than throwing")
    void duckDbOnlySyntaxIsEmptyNotThrown() {
        var sv = sourceView("ds3",
                "SELECT * FROM ${SNAPSHOT} USING SAMPLE 5",
                Map.of("a", "http://ex/a"));

        // If probe let the SqlParseException escape instead of returning Optional.empty(), this
        // call itself would fail the test with that exception — "no throw" is asserted by the
        // fact that .isEmpty() is even reachable.
        assertThat(WrapperExpansion.probe(sv)).isEmpty();
    }

    @Test
    @DisplayName("a null wrapperSql degrades to empty rather than throwing — 'ANY exception' includes"
            + " building the substituted SQL, not just parsing it")
    void nullWrapperSqlIsEmptyNotThrown() {
        // SourceView does not itself forbid a null wrapperSql (only expansionStatus defaults via
        // the compact constructor); probe's own try must cover the substitution step, not only
        // the parse/validate/convert calls, or a corrupt catalog entry would NPE instead of
        // reporting OPAQUE.
        var sv = new MappingCatalog.SourceView(
                "ds11", null, Map.of(), 0L, List.of(), "", Map.of(), Map.of(), "OPAQUE");

        assertThat(WrapperExpansion.probe(sv)).isEmpty();
    }

    // ── (d) LATERAL UNNEST — pinned to what Calcite 1.41 actually does ──────────

    @Test
    @DisplayName("a LATERAL UNNEST wrapper — see in-line comment for the investigated Calcite 1.41 outcome")
    void lateralUnnestWrapperOutcomeIsPinned() {
        // Shape mirrors WrapperSqlBuilder.buildJson's real output for an array-of-primitives
        // leaf property: FROM ${SNAPSHOT} s, UNNEST(s."creators") AS u1(_r1) — no explicit
        // LATERAL keyword, exactly as the generator emits it (Calcite's grammar treats a
        // FROM-clause UNNEST as implicitly correlated to a preceding FROM item; the keyword is
        // optional there, same as in DuckDB/Postgres).
        var sv = sourceView("ds4",
                "SELECT s.\"title\" AS \"http://ex/title\" "
                        + "FROM ${SNAPSHOT} s, UNNEST(s.\"creators\") AS u1(_r1)",
                Map.of("title", "http://ex/title", "creators", "http://ex/creators"));

        Optional<RelNode> result = WrapperExpansion.probe(sv);

        // PINNED (Calcite 1.41.0, confirmed by running this test with logging, not assumed): the
        // statement PARSES — Calcite's grammar accepts a bare `UNNEST(...)` FROM item referencing
        // a preceding alias with no LATERAL keyword, exactly as WrapperSqlBuilder emits it. It
        // fails at VALIDATION instead, with:
        //   org.apache.calcite.tools.ValidationException:
        //   org.apache.calcite.runtime.CalciteContextException: ... Cannot apply 'UNNEST' to
        //   arguments of type 'UNNEST(<VARCHAR>)'. Supported form(s): 'UNNEST(<MULTISET>)'
        //   'UNNEST(<ARRAY>)' 'UNNEST(<MAP>)'
        // — because SrcTable, like WrapperTable/IntegratedClassTable, only ever declares scalar
        // column types (XsdTypes has no array/struct mapping), so "creators" is VARCHAR and UNNEST
        // has no collection to unnest. Caught by probe's catch(Exception) exactly like any other
        // unplannable wrapper. That parsing succeeds is a bonus; the wrapper
        // still correctly stays OPAQUE.
        assertThat(result).isEmpty();
    }

    // ── (e) a typed column carries its XSD-derived SQL type through the plan ────

    @Test
    @DisplayName("sourceRefs + columnTypes give the leaf field its XSD-derived type (BIGINT), not VARCHAR")
    void typedColumnCarriesBigintThroughThePlan() {
        var sv = sourceView("ds5",
                "SELECT \"amount\" AS \"http://ex/amount\" FROM ${SNAPSHOT}",
                Map.of("amount", "http://ex/amount"),
                Map.of("http://ex/amount", XSD + "integer"));

        Optional<RelNode> result = WrapperExpansion.probe(sv);

        assertThat(result).isPresent();
        assertThat(result.get().getRowType().getFieldList().get(0).getType().getSqlTypeName())
                .isEqualTo(SqlTypeName.BIGINT);
    }

    // ── unusablePart screening — none of a–e produces a sub-query/correl/dynamic-param tree ─

    @Test
    @DisplayName("a wrapper whose SELECT list carries a scalar sub-query over its own src_ relation"
            + " plans cleanly but is screened out as unusable, not rejected by a validation error")
    void scalarSubQueryInSelectListIsScreenedOutAfterPlanning() {
        // Unlike (b)/(c), this SQL is valid and self-contained — the inner SELECT references the
        // very same src_ table, which is registered, so parse+validate+convert all succeed and
        // produce a real tree containing a RexSubQuery. This is the one construct none of a–e
        // exercises: probe must still return empty, but via the unusablePart screen after a
        // successful plan, not via the catch(Exception) branch (b)/(c)/(d) go through.
        var sv = sourceView("ds10",
                "SELECT (SELECT MAX(\"amount\") FROM ${SNAPSHOT}) AS \"http://ex/maxAmount\" "
                        + "FROM ${SNAPSHOT}",
                Map.of("amount", "http://ex/amount"));

        assertThat(WrapperExpansion.probe(sv)).isEmpty();
    }

    // ── SrcTable's own contract (Interfaces §1) — not otherwise exercised by a–e ─

    @Test
    @DisplayName("SrcTable's raw columns fall back to columnMap's value only when sourceRefs has no entry for it (pre-V31)")
    void rawColumnsUnionCoversPreV31FallbackWithoutDuplicatingKnownOnes() {
        var sv = new MappingCatalog.SourceView(
                "ds6", "SELECT 1",
                Map.of("http://ex/legacy", "legacy_raw_name", "http://ex/known", "known_prop_iri"),
                0L, List.of(), "",
                Map.of(),
                Map.of("known_raw_name", "known_prop_iri"),
                "OPAQUE");

        RelDataType rowType = new SrcTable(sv).getRowType(new JavaTypeFactoryImpl());

        assertThat(rowType.getFieldNames())
                .as("'known_prop_iri' is already reachable via sourceRefs's raw name, so it must"
                        + " not be added a second time under its own value")
                .containsExactlyInAnyOrder("known_raw_name", "legacy_raw_name");
    }

    @Test
    @DisplayName("columnNdv resolves raw name -> sourceRefs -> property IRI -> profile, guarding cardinality <= 0")
    void columnNdvResolvesThroughSourceRefsAndGuardsNonPositiveCardinality() {
        var sv = new MappingCatalog.SourceView(
                "ds7", "SELECT 1", Map.of(), 100L,
                List.of(profile("http://ex/amount", 50, 0.2), profile("http://ex/empty", 0, 1.0)),
                "", Map.of(),
                Map.of("amount", "http://ex/amount", "empty_col", "http://ex/empty"),
                "OPAQUE");
        SrcTable table = new SrcTable(sv);
        RelDataType rowType = table.getRowType(new JavaTypeFactoryImpl());
        int amountOrdinal = rowType.getFieldNames().indexOf("amount");
        int emptyOrdinal = rowType.getFieldNames().indexOf("empty_col");

        assertThat(table.columnNdv(amountOrdinal)).isEqualTo(50.0);
        assertThat(table.columnNullFraction(amountOrdinal)).isEqualTo(0.2);
        assertThat(table.columnNdv(emptyOrdinal))
                .as("cardinality <= 0 is 'no evidence', not zero distinct values")
                .isNull();
        assertThat(table.columnNdv(-1)).isNull();
        assertThat(table.columnNullFraction(rowType.getFieldList().size())).isNull();
    }

    @Test
    @DisplayName("getStatistic reports unknown (null), not zero, for an unprofiled source")
    void getStatisticRowCountIsNullWhenUnprofiled() {
        var sv = sourceView("ds8", "SELECT 1", Map.of());
        assertThat(new SrcTable(sv).getStatistic().getRowCount()).isNull();
    }

    @Test
    @DisplayName("getStatistic reports the source's row count once profiled")
    void getStatisticRowCountReflectsProfiledRowCount() {
        var sv = new MappingCatalog.SourceView(
                "ds9", "SELECT 1", Map.of(), 4200L, List.of(), "", Map.of(), Map.of(), "OPAQUE");
        assertThat(new SrcTable(sv).getStatistic().getRowCount()).isEqualTo(4200.0);
    }

    @Test
    @DisplayName("columnNdv ignores a WRAPPER_OUTPUT profile sharing the raw column's property IRI —"
            + " SrcTable models the raw src_ relation, not the wrapper's output")
    void columnNdvIgnoresAWrapperOutputProfileForTheSameName() {
        // Phase 3B: MappingCatalog.SourceView.profiles() can now hold WRAPPER_OUTPUT rows for an
        // OPAQUE class (MappingCatalogOperator's selection rule). Those describe the WRAPPER'S
        // OUTPUT relation, not the raw src_ relation this table models — mistaking one for the
        // other would feed the expansion probe evidence about the wrong physical shape.
        ColumnProfile wrapperOutput = new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), "http://ex/amount", 0, "VARCHAR",
                999, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                "WRAPPER_OUTPUT", "http://ex/SomeClass");

        var sv = new MappingCatalog.SourceView(
                "ds12", "SELECT 1", Map.of(), 100L,
                List.of(wrapperOutput), // no SNAPSHOT evidence at all for this raw column
                "", Map.of(),
                Map.of("amount", "http://ex/amount"),
                "OPAQUE");
        SrcTable table = new SrcTable(sv);
        RelDataType rowType = table.getRowType(new JavaTypeFactoryImpl());
        int amountOrdinal = rowType.getFieldNames().indexOf("amount");

        assertThat(table.columnNdv(amountOrdinal))
                .as("a WRAPPER_OUTPUT profile is evidence about the wrapper's OUTPUT, never the raw"
                        + " src_ relation SrcTable models — it must not be mistaken for one")
                .isNull();
        assertThat(table.columnNullFraction(amountOrdinal)).isNull();
    }
}
