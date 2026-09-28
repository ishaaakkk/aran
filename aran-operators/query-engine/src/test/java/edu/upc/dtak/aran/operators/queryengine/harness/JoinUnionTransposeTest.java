package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payoff for putting the union in the plan: a join pushed <em>below</em> a UNION ALL
 * lands on single-source branches, where freyja-verified keys are valid and
 * {@link edu.upc.dtak.aran.operators.queryengine.plan.AranRelMdRowCount}'s many-to-one
 * estimate can fire.
 *
 * <p>Above the union it never can. UNION ALL does not preserve uniqueness, so
 * {@code areColumnsUnique} is false for an integrated class no matter how clean each
 * underlying source is — which is the limitation
 * {@code AranRelMdRowCountTest.multiSourceClassStillFallsBack} pins.
 */
class JoinUnionTransposeTest {

    private static final String NS = "http://example.com/";
    private static final String SRC = NS + "source/";

    private static final String ORDERS = NS + "integration/Orders";
    private static final String CUSTOMER = NS + "integration/Customer";
    private static final String O_REF = SRC + "orders/customerRef";
    private static final String O_AMT = NS + "integration/amount";
    private static final String C_ID = SRC + "customers/id";
    private static final String C_NAME = NS + "integration/custName";
    private static final String PLACED_BY = NS + "integration/placedBy";

    private static ColumnProfile profile(String column, double uniqueness) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
                100L, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    /**
     * Orders (one source, no key on the FK) joined to Customer, which is integrated from
     * two sources that each have a genuinely unique {@code id}.
     */
    private static MappingCatalog catalog() {
        // "customer_ref" carries no profile at all — not one with a low uniqueness — because
        // since AranRelMdRowCount's NDV branch (Task 5), a profile's cardinality is live NDV
        // evidence, not cosmetic filler; this test's "no key on the FK" premise means no
        // evidence of any kind, matching AranRelMdRowCountTest.catalog()'s identical fix.
        var orders = new MappingCatalog.IntegratedClass(ORDERS, List.of(
                new MappingCatalog.SourceView("ord", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(O_REF, "customer_ref", O_AMT, "amount"),
                        1000,
                        List.of(profile("amount", 0.5)))));
        var customer = new MappingCatalog.IntegratedClass(CUSTOMER, List.of(
                new MappingCatalog.SourceView("cust_eu", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(C_ID, "id", C_NAME, "name"),
                        25,
                        List.of(profile("id", 1.0), profile("name", 0.6))),
                new MappingCatalog.SourceView("cust_us", "SELECT * FROM ${SNAPSHOT}",
                        Map.of(C_ID, "id", C_NAME, "name"),
                        25,
                        List.of(profile("id", 1.0), profile("name", 0.6)))));
        return new MappingCatalog(
                Map.of(ORDERS, orders, CUSTOMER, customer),
                List.of(new MappingCatalog.JoinProperty(PLACED_BY, ORDERS, CUSTOMER, O_REF, C_ID)),
                List.of(), "v1");
    }

    private static final String OQL =
            "SELECT Orders.amount, Customer.custName FROM Orders -[placedBy]-> Customer";

    @Test
    @DisplayName("the join is pushed below the union onto single-source branches")
    void joinIsPushedBelowTheUnion() {
        try (QueryHarness h = new QueryHarness()) {
            PlanShape shape = h.plan(catalog(), OQL).shape();
            String compact = shape.toCompactString();

            // Transposed, the Union sits ABOVE the joins: a JOIN node must be reachable by
            // descending from the UNION node. Untransposed, the union would instead sit
            // below (or absent from) the join's inputs.
            PlanShape.Node union = findFirst(shape.root(), "UNION");
            PlanShape.Node join = findFirst(shape.root(), "JOIN");
            assertThat(union).as("a two-source class must still produce a Union:\n%s", compact)
                    .isNotNull();
            assertThat(join).as("the query joins two classes:\n%s", compact)
                    .isNotNull();
            assertThat(findFirst(union, "JOIN"))
                    .as("the Union should sit above the joins after transposition:\n%s", compact)
                    .isNotNull();
        }
    }

    private static PlanShape.Node findFirst(PlanShape.Node n, String kind) {
        if (n.kind().equals(kind)) return n;
        for (PlanShape.Node in : n.inputs()) {
            PlanShape.Node hit = findFirst(in, kind);
            if (hit != null) return hit;
        }
        return null;
    }

    @Test
    @DisplayName("transposing tightens the cardinality estimate — the reason to do it at all")
    void transposedPlanHasATighterEstimate() {
        try (QueryHarness h = new QueryHarness()) {
            String plan = h.plan(catalog(), OQL).optimizedPlan();

            // Above the union the class cannot declare a key, so the estimate would be
            // 1000 x 50 x 0.15 = 7500. Below it, each branch's id is unique, so each order
            // matches at most one customer per branch: 1000 per branch, 2000 unioned.
            assertThat(plan)
                    .as("plan should not carry the no-key fallback estimate:\n%s", plan)
                    .doesNotContain("rowcount=7500.0");
        }
    }

    @Test
    @DisplayName("the transposed plan still returns the correct rows against DuckDB")
    void transposedPlanIsStillCorrect() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            Path ord = h.writeJson("ord", """
                    {"customer_ref":"c1","amount":10}
                    {"customer_ref":"c2","amount":20}
                    {"customer_ref":"c3","amount":30}
                    """);
            Path eu = h.writeJson("cust_eu", """
                    {"id":"c1","name":"Ada"}
                    """);
            Path us = h.writeJson("cust_us", """
                    {"id":"c2","name":"Grace"}
                    """);
            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "ord" -> "read_json_auto('" + ord + "')";
                case "cust_eu" -> "read_json_auto('" + eu + "')";
                case "cust_us" -> "read_json_auto('" + us + "')";
                default -> throw new IllegalArgumentException(ds);
            };

            var outcome = h.run(catalog(), resolver, OQL);

            // c1 matches the EU branch, c2 the US branch, c3 matches neither.
            assertThat(outcome.result().rows())
                    .as("inner join over a transposed union must not duplicate or drop rows")
                    .hasSize(2);
            assertThat(outcome.result().rows().stream().map(r -> String.valueOf(r.get(1))))
                    .containsExactlyInAnyOrder("Ada", "Grace");
        }
    }
}
