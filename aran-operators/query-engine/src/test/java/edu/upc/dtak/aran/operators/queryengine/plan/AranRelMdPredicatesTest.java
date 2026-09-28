package edu.upc.dtak.aran.operators.queryengine.plan;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.metadata.JaninoRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.sql.SqlKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AranRelMdPredicates} — an enforced denial constraint reaches the planner as a pulled-up
 * predicate on every scan of the class it constrains.
 *
 * <p>The constraint is already <em>enforced</em>: {@code DuckDbExecutor.buildDdl} splices it into
 * each {@code u_} member's body, so no row violating it can be read. What the planner lacked was
 * knowledge of that fact, which is worth three things: a query predicate contradicting the
 * constraint can be answered without touching a source, one implied by it stops being evaluated
 * per row, and every estimate downstream is sharper.
 *
 * <p>The safety property runs the other way and matters more: a constraint that cannot be parsed
 * is <em>dropped from the planner</em>, never fatal. Enforcement lives in the view bodies, so a
 * dropped predicate costs estimate quality and nothing else — see
 * {@link #anUnparseableConstraintIsDroppedWithAWarningAndChangesNoPlan}.
 */
class AranRelMdPredicatesTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String ARTWORKS = NS + "integration/Artworks";
    private static final String P_TITLE = NS + "integration/title";
    private static final String P_YEAR = NS + "integration/year";
    private static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";

    /** {@code year} carries an {@code xsd:integer} range, so its row-type field is BIGINT (Task 3). */
    private static MappingCatalog singleSource() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_YEAR, "year"),
                        100L, List.of(), "", Map.of(P_YEAR, XSD_INTEGER))));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    /** Two sources: the plan scans {@code u_} members under a UNION ALL, never {@code int_}. */
    private static MappingCatalog twoSources() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art1", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_YEAR, "year"),
                        100L, List.of(), "", Map.of(P_YEAR, XSD_INTEGER)),
                new MappingCatalog.SourceView("art2", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(P_TITLE, "title", P_YEAR, "year"),
                        200L, List.of(), "", Map.of(P_YEAR, XSD_INTEGER))));
        return new MappingCatalog(Map.of(ARTWORKS, artworks), List.of(), List.of(), "v1");
    }

    private static final Map<String, List<String>> YEAR_POSITIVE =
            Map.of(ARTWORKS, List.of("year > 0"));

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RelNode lower(MappingCatalog catalog, Map<String, List<String>> enforced, String oql) {
        return new OqlPlanner(catalog, enforced).lower(OqlParser.parse(oql));
    }

    /** The compact plan shape after the real VolcanoPlanner pass — what the corpus goldens pin. */
    private static String optimizedShape(MappingCatalog catalog,
                                         Map<String, List<String>> enforced, String oql) {
        return PlanShape.of(PlanExecutor.optimize(lower(catalog, enforced, oql))).toCompactString();
    }

    /** First {@link TableScan} in {@code rel}, depth-first — the node the handler is asked about. */
    private static TableScan firstScan(RelNode rel) {
        if (rel instanceof TableScan scan) {
            return scan;
        }
        for (RelNode input : rel.getInputs()) {
            TableScan found = firstScan(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static RelOptPredicateList pulledUpOnFirstScan(MappingCatalog catalog,
                                                           Map<String, List<String>> enforced) {
        RelNode logical = lower(catalog, enforced, "SELECT Artworks.title FROM Artworks");
        TableScan scan = firstScan(logical);
        assertThat(scan).as("the plan must contain a scan to ask about").isNotNull();
        return scan.getCluster().getMetadataQuery().getPulledUpPredicates(scan);
    }

    // ── The predicate reaches the scan ────────────────────────────────────────

    @Test
    @DisplayName("an enforced constraint is visible as a pulled-up predicate on the class's scan")
    void enforcedConstraintBecomesAPulledUpPredicate() {
        RelOptPredicateList predicates = pulledUpOnFirstScan(singleSource(), YEAR_POSITIVE);

        assertThat(predicates.pulledUpPredicates).hasSize(1);
        assertThat(predicates.pulledUpPredicates.get(0).getKind()).isEqualTo(SqlKind.GREATER_THAN);
        // The ordinal must index the scan's own row type, not the catalog's column map: the probe
        // plans over the same AranSchema, so $n is already the right n.
        assertThat(predicates.pulledUpPredicates.get(0).toString()).isEqualTo(">($1, 0)");
    }

    @Test
    @DisplayName("a class with no constraints returns exactly Calcite's own empty predicate list")
    void noConstraintsIsByteIdenticalToCalcitesDefault() {
        RelOptPredicateList ours = pulledUpOnFirstScan(singleSource(), Map.of());

        // Not merely "empty": RelOptPredicateList.EMPTY is the identical instance Calcite's own
        // RelMdPredicates.getPredicates(TableScan, ..) returns for a table carrying no handler.
        // Chaining ahead of the default makes its TableScan method dead code, so this class owes
        // that answer itself — a bare `return null` would be final, not a fall-through.
        assertThat(ours).isSameAs(RelOptPredicateList.EMPTY);
    }

    @Test
    @DisplayName("the same answer as stock Calcite for an unconstrained scan, through a stock chain")
    void unconstrainedScanMatchesStockCalcite() {
        RelNode logical = lower(singleSource(), Map.of(), "SELECT Artworks.title FROM Artworks");
        TableScan scan = firstScan(logical);
        scan.getCluster().setMetadataQuerySupplier(() ->
                new RelMetadataQuery(JaninoRelMetadataProvider.of(DefaultRelMetadataProvider.INSTANCE)));
        scan.getCluster().invalidateMetadataQuery();

        assertThat(scan.getCluster().getMetadataQuery().getPulledUpPredicates(scan))
                .isSameAs(RelOptPredicateList.EMPTY);
    }

    // ── The payoff: FILTER_REDUCE_EXPRESSIONS consumes it ─────────────────────

    @Test
    @DisplayName("a query contradicting the constraint collapses to empty VALUES — no scan at all")
    void contradictingQueryCollapsesToEmptyValues() {
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.year < 0";

        String withConstraint = optimizedShape(singleSource(), YEAR_POSITIVE, oql);
        String without = optimizedShape(singleSource(), Map.of(), oql);

        assertThat(without)
                .as("baseline: without the constraint the query is a real filtered scan")
                .contains("SCAN[u_art_Artworks]");
        assertThat(withConstraint)
                .as("year < 0 cannot be satisfied where year > 0 is enforced")
                .contains("OTHER[LogicalValues]")
                .doesNotContain("SCAN[");
    }

    @Test
    @DisplayName("the collapse works for a multi-source class too — the u_ members carry it")
    void contradictingQueryCollapsesAcrossTheUnion() {
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.year < 0";

        assertThat(optimizedShape(twoSources(), YEAR_POSITIVE, oql))
                .as("selection distributes over the union, so every member enforces it")
                .contains("OTHER[LogicalValues]")
                .doesNotContain("SCAN[");
    }

    @Test
    @DisplayName("a query predicate implied by the constraint stops being a range check")
    void impliedQueryPredicateIsWeakenedAway() {
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.year > 0";

        String withConstraint = optimizedShape(singleSource(), YEAR_POSITIVE, oql);
        String without = optimizedShape(singleSource(), Map.of(), oql);

        assertThat(without)
                .as("baseline: without the constraint the comparison is evaluated per row")
                .contains(">(year, 0)");
        assertThat(withConstraint)
                .as("the enforced constraint already guarantees it; re-checking it is dead work")
                .doesNotContain(">(year, 0)")
                // Without this the assertion above would also be satisfied by an accidental
                // collapse to empty VALUES — which is the opposite outcome, and wrong here:
                // year > 0 is implied by the constraint, so every row still qualifies.
                .contains("SCAN[");
    }

    // ── The safety property ───────────────────────────────────────────────────

    /**
     * Asserts the whole drop-on-failure contract for one bad constraint: no predicate reaches the
     * scan, the optimized plan is the one the query would have had with no constraint at all, and
     * a WARN naming {@code messageFragments} was logged — silent degradation being the failure
     * mode these tests exist to prevent.
     */
    private static void assertDroppedWithWarning(Map<String, List<String>> badConstraint,
                                                 String... messageFragments) {
        String oql = "SELECT Artworks.title FROM Artworks WHERE Artworks.year < 0";

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OqlPlanner.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        String shape;
        RelOptPredicateList predicates;
        try {
            shape = optimizedShape(singleSource(), badConstraint, oql);
            predicates = pulledUpOnFirstScan(singleSource(), badConstraint);
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }

        assertThat(predicates).isSameAs(RelOptPredicateList.EMPTY);
        assertThat(shape)
                .as("a constraint the planner cannot use must not change what the query does")
                .isEqualTo(optimizedShape(singleSource(), Map.of(), oql));
        assertThat(captured.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains(messageFragments);
                });
    }

    @Test
    @DisplayName("an unparseable constraint is dropped with a WARN and changes no plan")
    void anUnparseableConstraintIsDroppedWithAWarningAndChangesNoPlan() {
        assertDroppedWithWarning(Map.of(ARTWORKS, List.of("some garbage (((")),
                "some garbage (((");
    }

    @Test
    @DisplayName("a constraint carrying a sub-query is dropped with a WARN and changes no plan")
    void aConstraintCarryingASubQueryIsDroppedWithAWarningAndChangesNoPlan() {
        // This one parses and validates cleanly — catching exceptions is not enough. Calcite
        // leaves IN unexpanded by default, so the condition comes back holding a RexSubQuery
        // over a RelNode built in the PROBE's cluster. Carried into the query's planner as a
        // pulled-up guarantee, anything it throws throws outside the probe's try/catch, i.e. a
        // failed query — the one outcome the drop-on-failure contract rules out. The WARN must
        // therefore name the sub-query, not merely report a drop: asserting only "dropped" would
        // still pass if the constraint had been rejected for some unrelated reason.
        assertDroppedWithWarning(Map.of(ARTWORKS, List.of("year IN (SELECT 1)")),
                "sub-query", "year IN (SELECT 1)");
    }

    @Test
    @DisplayName("a constraint carrying a dynamic parameter is dropped with a WARN and changes no plan")
    void aConstraintCarryingADynamicParameterIsDroppedWithAWarningAndChangesNoPlan() {
        // Same problem as a sub-query, by a different route: Calcite validates "year > ?" cleanly
        // (it infers the parameter's type from the comparison), so the condition comes back
        // holding a RexDynamicParam that cannot be bound in the query's own cluster — nothing
        // supplies it a value there. Carrying it into the pulled-up predicate list would put an
        // unbindable node where the planner expects a guarantee, so it must be dropped exactly
        // like the sub-query case (see unusablePart), not merely fail to be recognised as unusable.
        assertDroppedWithWarning(Map.of(ARTWORKS, List.of("year > ?")),
                "dynamic parameter", "year > ?");
    }

    @Test
    @DisplayName("a constraint naming a column the class does not have is dropped, not fatal")
    void aConstraintOverAnUnknownColumnIsDropped() {
        // The rewriter leaves an unknown local name bare, so the validator rejects it. That is a
        // model/catalog mismatch, not a query error, and the query must still answer — and, like
        // every other drop-on-failure case, it must not fail silently: a WARN has to be logged.
        assertDroppedWithWarning(Map.of(ARTWORKS, List.of("no_such_column > 0")),
                "no_such_column > 0");
    }
}
