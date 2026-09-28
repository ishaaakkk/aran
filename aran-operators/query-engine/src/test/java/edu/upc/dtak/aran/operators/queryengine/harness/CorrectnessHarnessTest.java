package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorrectnessHarnessTest {

    private static final String NS  = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String SRC = NS + "source/";

    // Artworks / IArtist model
    private static final String ARTWORKS   = NS + "integration/Artworks";
    private static final String IARTIST    = NS + "integration/IArtist";
    private static final String P_TITLE    = NS + "integration/title";   // localName "title"
    private static final String P_NAME     = NS + "integration/name";    // localName "name"
    private static final String P_AID      = SRC + "art/artist_id";      // localName "artist_id"
    private static final String P_ID       = SRC + "artist/id";          // localName "id"
    private static final String HAS_ARTIST = NS + "integration/hasArtist";

    // Customer / Order model (fan-out: one customer, many orders)
    private static final String CUSTOMER   = NS + "integration/Customer";
    // Local name "Orders", not "Order": chosen when the OQL lexer reserved "order". Keywords
    // are contextual now and "Order" would parse; the name is kept so the corpus stays stable.
    private static final String ORDER      = NS + "integration/Orders";
    private static final String C_NAME     = NS + "integration/name";      // localName "name"
    private static final String C_CREDIT   = NS + "integration/credit";    // localName "credit"
    private static final String C_ID       = SRC + "cust/id";              // localName "id"
    private static final String O_ID       = SRC + "order/id";             // localName "id"
    private static final String O_CUSTID   = SRC + "order/customer_id";    // localName "customer_id"
    private static final String HAS_ORDER  = NS + "integration/hasOrder";

    @Test
    void joinReturnsCorrectRows() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            Path artFile = h.writeJson("art",
                    "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"}," +
                    " {\"title\":\"Convergence\",\"artist_id\":\"a2\"}]");
            Path artistFile = h.writeJson("artist",
                    "[{\"id\":\"a1\",\"name\":\"Picasso\"}," +
                    " {\"id\":\"a2\",\"name\":\"Pollock\"}]");

            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "art"    -> "read_json_auto('" + artFile.toAbsolutePath() + "')";
                case "artist" -> "read_json_auto('" + artistFile.toAbsolutePath() + "')";
                default -> throw new IllegalArgumentException("Unknown dataset: " + ds);
            };

            var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                    new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                            Map.of(P_TITLE, "title", P_AID, "artist_id"))));
            var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                    new MappingCatalog.SourceView("artist", "SELECT * FROM ${SNAPSHOT}",
                            Map.of(P_ID, "id", P_NAME, "name"))));
            var join = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
            var catalog = new MappingCatalog(
                    Map.of(ARTWORKS, artworks, IARTIST, iartist),
                    List.of(join), List.of(), "v1");

            String oql = "SELECT Artworks.title, IArtist.name AS artist "
                    + "FROM Artworks -[hasArtist]-> IArtist";

            QueryHarness.Outcome out = h.run(catalog, resolver, oql);

            // Element type of rows() is List<Object>; use List.<Object>of so the
            // expected rows are List<Object>, not the invariant List<String>.
            assertThat(out.result().rows())
                    .containsExactlyInAnyOrder(
                            List.<Object>of("Guernica", "Picasso"),
                            List.<Object>of("Convergence", "Pollock"));
        }
    }

    @Test
    void fanOutJoinDoubleCountsAndPlannerDoesNotFixIt() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            // One customer with credit 100, and TWO orders -> the join explodes the
            // customer row into two, so SUM(credit) double-counts to 200 (honest 1NF).
            Path custFile = h.writeJson("cust",
                    "[{\"id\":\"c1\",\"name\":\"Alice\",\"credit\":100}]");
            Path orderFile = h.writeJson("order",
                    "[{\"id\":\"o1\",\"customer_id\":\"c1\"}," +
                    " {\"id\":\"o2\",\"customer_id\":\"c1\"}]");

            DuckDbExecutor.SnapshotResolver resolver = ds -> switch (ds) {
                case "cust"  -> "read_json_auto('" + custFile.toAbsolutePath() + "')";
                case "order" -> "read_json_auto('" + orderFile.toAbsolutePath() + "')";
                default -> throw new IllegalArgumentException("Unknown dataset: " + ds);
            };

            var customer = new MappingCatalog.IntegratedClass(CUSTOMER, List.of(
                    new MappingCatalog.SourceView("cust", "SELECT * FROM ${SNAPSHOT}",
                            Map.of(C_ID, "id", C_NAME, "name", C_CREDIT, "credit"))));
            var order = new MappingCatalog.IntegratedClass(ORDER, List.of(
                    new MappingCatalog.SourceView("order", "SELECT * FROM ${SNAPSHOT}",
                            Map.of(O_ID, "id", O_CUSTID, "customer_id"))));
            var join = new MappingCatalog.JoinProperty(HAS_ORDER, CUSTOMER, ORDER, C_ID, O_CUSTID);
            var catalog = new MappingCatalog(
                    Map.of(CUSTOMER, customer, ORDER, order),
                    List.of(join), List.of(), "v1");

            String oql = "SELECT Customer.name, SUM(Customer.credit) AS total "
                    + "FROM Customer -[hasOrder]-> Orders GROUP BY Customer.name";

            QueryHarness.Outcome out = h.run(catalog, resolver, oql);

            // 100 counted once per exploded row => 200. This is the honest fan-out result;
            // the mediator cannot de-duplicate it. Documented, not "fixed".
            //
            // The total is a Long, not the string "200": DuckDB types SUM over integers as
            // HUGEINT (in 1.3 as much as in 1.5), which jsonSafe used to stringify wholesale — so
            // every aggregate in every OQL query left the API as text. This assertion encoded
            // that. See JsonSafeTypeTest.
            assertThat(out.result().rows()).containsExactly(List.<Object>of("Alice", 200L));
        }
    }

    @Test
    void dialectEmitsExecutableLimitOverQuotedIriColumns() throws Exception {
        try (QueryHarness h = new QueryHarness()) {
            Path artFile = h.writeJson("art",
                    "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"}," +
                    " {\"title\":\"Convergence\",\"artist_id\":\"a2\"}," +
                    " {\"title\":\"No.5\",\"artist_id\":\"a2\"}]");
            DuckDbExecutor.SnapshotResolver resolver = ds ->
                    "read_json_auto('" + artFile.toAbsolutePath() + "')";

            var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                    new MappingCatalog.SourceView("art", "SELECT * FROM ${SNAPSHOT}",
                            Map.of(P_TITLE, "title", P_AID, "artist_id"))));
            var catalog = new MappingCatalog(Map.of(ARTWORKS, artworks),
                    List.of(), List.of(), "v1");

            // LIMIT exercises the dialect's offset/fetch unparsing; the IRI column
            // exercises identifier quoting. Both must execute on DuckDB.
            String oql = "SELECT Artworks.title FROM Artworks LIMIT 2";
            QueryHarness.Outcome out = h.run(catalog, resolver, oql);

            assertThat(out.sql()).containsIgnoringCase("LIMIT 2");
            assertThat(out.result().rows()).hasSize(2);
        }
    }
}
