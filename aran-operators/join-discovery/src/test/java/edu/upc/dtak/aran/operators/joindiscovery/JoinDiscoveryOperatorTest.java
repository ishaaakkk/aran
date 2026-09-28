package edu.upc.dtak.aran.operators.joindiscovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.source.Harvest;
import edu.upc.dtak.aran.core.source.HarvestedColumnProfiles;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Prior to this test, the join-discovery module (a single class) had zero test
 * coverage — everything below exercises real branches: parameter validation, the
 * two-call freyja round-trip (distance, then rank, via a throwaway JDK HttpServer),
 * and the threshold filter on the ranked candidates.
 */
class JoinDiscoveryOperatorTest {

    private static final String FREYJA_SECRET = "test-freyja-secret";

    private HttpServer server;
    private DatasetRepository datasetRepository;
    private JoinDiscoveryOperator operator;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        datasetRepository = mock(DatasetRepository.class);
        String freyjaUrl = "http://localhost:" + server.getAddress().getPort();
        operator = new JoinDiscoveryOperator(freyjaUrl, FREYJA_SECRET, new ObjectMapper(), datasetRepository);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static ColumnProfile column(UUID datasetId, String name, boolean empty) {
        return new ColumnProfile(
                UUID.randomUUID(), datasetId, name, 0, "string",
                20, 3.0, 1.0, 0.0,
                1, 1, 1, 0, 0,
                0, 0, 0, 0,
                10, 3, 6,
                1, 1, 1, 1, 0,
                null, null,
                empty, false,
                List.of(), List.of());
    }

    /** Same profile, but on a non-SNAPSHOT generation (Phase 3B WRAPPER_OUTPUT / Phase 4B HARVESTED). */
    private static ColumnProfile column(UUID datasetId, String name, String subject, @Nullable String classIri) {
        return new ColumnProfile(
                UUID.randomUUID(), datasetId, name, 0, "string",
                20, 3.0, 1.0, 0.0,
                1, 1, 1, 0, 0,
                0, 0, 0, 0,
                10, 3, 6,
                1, 1, 1, 1, 0,
                null, null,
                false, false,
                List.of(), List.of(),
                subject, classIri);
    }

    @Test
    void manifestDeclaresPingAndDiscoverWithFiveParameters() {
        var manifest = operator.manifest();
        assertThat(manifest.operatorId()).isEqualTo("join-discovery");
        assertThat(manifest.operations().stream().map(OperationSpec::name))
                .containsExactlyInAnyOrder("ping", "discover");
        OperationSpec discover = manifest.operations().stream()
                .filter(op -> op.name().equals("discover")).findFirst().orElseThrow();
        assertThat(discover.parameters().stream().filter(p -> p.required()).map(p -> p.name()))
                .containsExactlyInAnyOrder("datasetIdA", "datasetIdB");
    }

    @Test
    void pingReportsOperational() {
        OperatorResult result = operator.execute(new OperatorRequest("tenant-1", "ping", Map.of()));
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("status", "ok");
        verifyNoInteractions(datasetRepository);
    }

    @Test
    void rejectsMissingDatasetIds() {
        OperatorResult result = operator.execute(new OperatorRequest("tenant-1", "discover", Map.of("datasetIdA", "x")));
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MISSING_PARAMETER");
    }

    @Test
    void rejectsANonUuidDatasetId() {
        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", "not-a-uuid", "datasetIdB", UUID.randomUUID().toString())));
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_PARAMETER");
    }

    @Test
    void reportsDatasetNotFound() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(datasetRepository.findById(any(), eq(a))).thenReturn(Optional.empty());

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", a.toString(), "datasetIdB", b.toString())));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("DATASET_NOT_FOUND");
    }

    @Test
    void ranksCandidatesAgainstFreyjaAndFiltersByThreshold() throws Exception {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        Dataset dsA = new Dataset(idA, UUID.randomUUID(), "papers.csv", "d", "snap-a",
                List.of(column(idA, "author_id", false)));
        Dataset dsB = new Dataset(idB, UUID.randomUUID(), "researchers.json", "d", "snap-b",
                List.of(column(idB, "researcher_id", false)));
        when(datasetRepository.findById(any(), eq(idA))).thenReturn(Optional.of(dsA));
        when(datasetRepository.findById(any(), eq(idB))).thenReturn(Optional.of(dsB));

        server.createContext("/operators/distance", ex -> {
            try { respondJson(ex, "{\"distances\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
        server.createContext("/operators/rank", ex -> {
            try {
                respondJson(ex, """
                    {"ranking":[
                      {"candidate_column":"researcher_id","score":0.94},
                      {"candidate_column":"low_confidence","score":0.10}
                    ]}
                    """);
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover",
                Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString(), "threshold", "0.5")));

        assertThat(result.success()).isTrue();
        List<Map<String, Object>> candidates = new ObjectMapper().readValue(
                result.data().get("candidates"), new TypeReference<List<Map<String, Object>>>() {});
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0)).containsEntry("columnB", "researcher_id");
    }

    /**
     * Audit JOIN_DISCOVERY-1. After any compile a dataset also carries WRAPPER_OUTPUT profiles
     * (Phase 3B — column names are full property IRIs) and, for a harvested relational source,
     * HARVESTED rows (Phase 4B). Discovery is defined over the raw snapshot's physical columns:
     * the other generations duplicate a column under a second name and mint {@code iriA}/{@code
     * iriB} that no source graph can resolve, so they must never reach freyja.
     */
    @Test
    @DisplayName("each column's value-hash sample reaches /distance — the exact score has nothing else to estimate overlap from")
    void shipsEachColumnsValueHashSampleToFreyja() throws Exception {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        ColumnProfile base = column(idA, "customer_id", false);
        ColumnProfile sampledA = new ColumnProfile(
                base.id(), idA, "customer_id", 0, "string",
                20, 3.0, 1.0, 0.0,
                1, 1, 1, 0, 0,
                0, 0, 0, 0,
                10, 3, 6,
                1, 1, 1, 1, 0,
                null, null,
                false, false,
                List.of(), List.of(),
                ColumnProfile.SUBJECT_SNAPSHOT, null,
                List.of("00411460f7c92d21", "006f52e9102a8d3b"));
        ColumnProfile sampledB = new ColumnProfile(
                UUID.randomUUID(), idB, "id", 0, "string",
                20, 3.0, 1.0, 0.0,
                1, 1, 1, 0, 0,
                0, 0, 0, 0,
                10, 3, 6,
                1, 1, 1, 1, 0,
                null, null,
                false, false,
                List.of(), List.of(),
                ColumnProfile.SUBJECT_SNAPSHOT, null,
                List.of("00411460f7c92d21", "00ac8ed3b4327bdd"));
        when(datasetRepository.findById(any(), eq(idA))).thenReturn(Optional.of(
                new Dataset(idA, UUID.randomUUID(), "orders.csv", "d", "snap-a", List.of(sampledA))));
        when(datasetRepository.findById(any(), eq(idB))).thenReturn(Optional.of(
                new Dataset(idB, UUID.randomUUID(), "customers.csv", "d", "snap-b", List.of(sampledB))));

        List<String> distanceBodies = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/operators/distance", ex -> {
            try {
                distanceBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respondJson(ex, "{\"distances\":[]}");
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        server.createContext("/operators/rank", ex -> {
            try { respondJson(ex, "{\"ranking\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString())));

        assertThat(distanceBodies).hasSize(1);
        JsonNode body = new ObjectMapper().readTree(distanceBodies.get(0));
        List<String> querySample = new ObjectMapper().convertValue(
                body.path("query").path("metrics").path("value_hash_sample"), new TypeReference<>() {});
        List<String> candidateSample = new ObjectMapper().convertValue(
                body.path("candidates").path(0).path("metrics").path("value_hash_sample"), new TypeReference<>() {});
        assertThat(querySample).containsExactly("00411460f7c92d21", "006f52e9102a8d3b");
        assertThat(candidateSample).containsExactly("00411460f7c92d21", "00ac8ed3b4327bdd");
    }

    @Test
    @DisplayName("only SNAPSHOT profiles reach freyja — WRAPPER_OUTPUT and HARVESTED rows are skipped on both sides")
    void sendsOnlySnapshotProfilesToFreyja() throws Exception {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        Dataset dsA = new Dataset(idA, UUID.randomUUID(), "papers.csv", "d", "snap-a", List.of(
                column(idA, "author_id", false),
                column(idA, "http://ex/ClassA/author_id", "WRAPPER_OUTPUT", "http://ex/ClassA"),
                column(idA, "author_id", "HARVESTED", null)));
        Dataset dsB = new Dataset(idB, UUID.randomUUID(), "researchers.json", "d", "snap-b", List.of(
                column(idB, "researcher_id", false),
                column(idB, "http://ex/ClassB/researcher_id", "WRAPPER_OUTPUT", "http://ex/ClassB"),
                column(idB, "researcher_id", "HARVESTED", null)));
        when(datasetRepository.findById(any(), eq(idA))).thenReturn(Optional.of(dsA));
        when(datasetRepository.findById(any(), eq(idB))).thenReturn(Optional.of(dsB));

        List<String> distanceBodies = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/operators/distance", ex -> {
            try {
                distanceBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respondJson(ex, "{\"distances\":[]}");
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        server.createContext("/operators/rank", ex -> {
            try { respondJson(ex, "{\"ranking\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString())));

        assertThat(result.success()).isTrue();
        assertThat(distanceBodies)
                .as("one /distance call per SNAPSHOT column of A — not one per profile row")
                .hasSize(1);

        JsonNode body = new ObjectMapper().readTree(distanceBodies.get(0));
        assertThat(body.path("query").path("column_name").asText()).isEqualTo("author_id");

        List<String> candidateNames = new ArrayList<>();
        body.path("candidates").forEach(c -> candidateNames.add(c.path("column_name").asText()));
        assertThat(candidateNames)
                .as("candidates are B's SNAPSHOT columns only")
                .containsExactly("researcher_id");
    }

    @Test
    void skipsEmptyColumnsWithoutCallingFreyja() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        Dataset dsA = new Dataset(idA, UUID.randomUUID(), "papers.csv", "d", "snap-a",
                List.of(column(idA, "empty_col", true)));
        Dataset dsB = new Dataset(idB, UUID.randomUUID(), "researchers.json", "d", "snap-b",
                List.of(column(idB, "researcher_id", false)));
        when(datasetRepository.findById(any(), eq(idA))).thenReturn(Optional.of(dsA));
        when(datasetRepository.findById(any(), eq(idB))).thenReturn(Optional.of(dsB));

        // No /operators/distance or /operators/rank context registered — a request to
        // either would 404 (turned into "continue" by the operator), proving the
        // isEmpty() guard actually skipped the column rather than calling out anyway.
        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("candidates")).isEqualTo("[]");
    }

    // ── HARVESTED rows are estimates, not measurements (V36 coexistence) ──────────────────────
    //
    // Flyway V36 (V40/V44) widened column_profile's uniqueness key to include `subject`, so a
    // materialized relational dataset now carries freyja's SNAPSHOT profile AND the catalog
    // harvest's HARVESTED estimate for the same physical column. Every metric this operator sends
    // to freyja is a structural zero on the harvested row, and nothing here deduplicates
    // (columnA, columnB) before the sort — so without a subject filter one real pair came out as
    // four, three of them ranked off numbers nobody measured.

    /**
     * The real writer rather than a hand-rolled row: if {@code HarvestedColumnProfiles} ever
     * started filling in the metrics freyja needs — or stopped stamping the subject — these tests
     * would quietly stop testing what they claim to.
     */
    private static ColumnProfile harvested(UUID datasetId, String table, String column) {
        return HarvestedColumnProfiles.forTable(
                        new Harvest(Map.of(table, 900L),
                                Map.of(new Harvest.ColKey(table, column), 880L),
                                List.of()),
                        datasetId, table)
                .get(0);
    }

    @Test
    @DisplayName("a harvested row alongside the measured one does not duplicate the candidate")
    void aHarvestedRowAlongsideTheMeasuredOneDoesNotDuplicateTheCandidate() throws Exception {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        // Harvested row FIRST on both sides — the order a re-harvest after profiling produces.
        Dataset dsA = new Dataset(idA, UUID.randomUUID(), "papers", "d", "snap-a",
                List.of(harvested(idA, "papers", "author_id"), column(idA, "author_id", false)));
        Dataset dsB = new Dataset(idB, UUID.randomUUID(), "researchers", "d", "snap-b",
                List.of(harvested(idB, "researchers", "researcher_id"),
                        column(idB, "researcher_id", false)));
        when(datasetRepository.findById(any(), eq(idA))).thenReturn(Optional.of(dsA));
        when(datasetRepository.findById(any(), eq(idB))).thenReturn(Optional.of(dsB));

        List<String> distanceBodies = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        server.createContext("/operators/distance", ex -> {
            try {
                distanceBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respondJson(ex, "{\"distances\":[]}");
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        server.createContext("/operators/rank", ex -> {
            try { respondJson(ex, "{\"ranking\":[{\"candidate_column\":\"researcher_id\",\"score\":0.94}]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover",
                Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString(), "threshold", "0.5")));

        assertThat(result.success()).isTrue();
        List<Map<String, Object>> candidates = new ObjectMapper().readValue(
                result.data().get("candidates"), new TypeReference<List<Map<String, Object>>>() {});
        assertThat(candidates)
                .as("one measured column pair is one candidate — it was four before the subject filter")
                .hasSize(1);
        assertThat(candidates.get(0))
                .containsEntry("columnA", "author_id")
                .containsEntry("columnB", "researcher_id");

        // The query side ran once (one measured column on A), and the candidate list it carried
        // held only B's measured column — the harvested row never reached freyja at all.
        assertThat(distanceBodies).hasSize(1);
        JsonNode sent = new ObjectMapper().readTree(distanceBodies.get(0));
        assertThat(sent.get("candidates")).hasSize(1);
        assertThat(sent.get("candidates").get(0).get("column_name").asText()).isEqualTo("researcher_id");
    }

    @Test
    @DisplayName("a dataset carrying only harvested rows produces no candidates and never calls freyja")
    void aDatasetCarryingOnlyHarvestedRowsProducesNoCandidates() {
        // The case that predates V36: a FEDERATED relational dataset has no snapshot to profile, so
        // a harvest is the only evidence it ever carries. Discovering over it used to ship those
        // zeros to freyja and rank whatever came back — candidates a user could accept as joins,
        // built from measurements nobody made. No candidate is the honest answer.
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        Dataset dsA = new Dataset(idA, UUID.randomUUID(), "papers", "d", null,
                List.of(harvested(idA, "papers", "author_id")));
        Dataset dsB = new Dataset(idB, UUID.randomUUID(), "researchers", "d", "snap-b",
                List.of(column(idB, "researcher_id", false)));
        when(datasetRepository.findById(any(), eq(idA))).thenReturn(Optional.of(dsA));
        when(datasetRepository.findById(any(), eq(idB))).thenReturn(Optional.of(dsB));

        // Handlers ARE registered, and they would hand back a high-scoring candidate. An empty
        // result therefore proves the filter skipped the column, rather than proving freyja 404'd:
        // the operator turns a 404 into "continue" too, so an unregistered handler would pass this
        // test whether or not the fix exists.
        java.util.List<String> distanceCalls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        server.createContext("/operators/distance", ex -> {
            try {
                distanceCalls.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respondJson(ex, "{\"distances\":[]}");
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        server.createContext("/operators/rank", ex -> {
            try { respondJson(ex, "{\"ranking\":[{\"candidate_column\":\"researcher_id\",\"score\":0.99}]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("candidates")).isEqualTo("[]");
        assertThat(distanceCalls).as("freyja must not be asked about a column nobody measured").isEmpty();
    }

    // ── J3-F5: a run that could not score is not a run that found nothing ─────────────────────
    //
    // With freyja down every column's /distance call failed, each failure was logged and skipped,
    // and the operator answered success with "[]" — byte-identical to two sources that genuinely
    // share no values, so the UI advised lowering the threshold. The result now says what was
    // compared and what failed, on the ConstraintDiscovery precedent (degraded + reason, present
    // only when something failed).

    private static void respondStatus(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void stubDatasets(UUID idA, UUID idB, List<ColumnProfile> colsA, List<ColumnProfile> colsB) {
        when(datasetRepository.findById(any(), eq(idA))).thenReturn(Optional.of(
                new Dataset(idA, UUID.randomUUID(), "papers.csv", "d", "snap-a", colsA)));
        when(datasetRepository.findById(any(), eq(idB))).thenReturn(Optional.of(
                new Dataset(idB, UUID.randomUUID(), "researchers.csv", "d", "snap-b", colsB)));
    }

    @Test
    @DisplayName("freyja answering 503 is a degraded run with the reason, never a clean empty result")
    void aFailingSidecarIsReportedAsDegradedWithItsReason() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        stubDatasets(idA, idB, List.of(column(idA, "author_id", false)), List.of(column(idB, "rid", false)));
        server.createContext("/operators/distance", ex -> {
            try { respondJson(ex, "{\"distances\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
        server.createContext("/operators/rank", ex -> {
            try { respondStatus(ex, 503, "{\"detail\":\"Gradient Boosting model is not loaded in sidecar\"}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover",
                Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString(), "method", "ml")));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("candidates", "[]");
        assertThat(result.data()).containsEntry("degraded", "true");
        assertThat(result.data().get("reason"))
                .contains("503")
                .contains("Gradient Boosting model is not loaded in sidecar");
        assertThat(result.data()).containsEntry("failedColumns", "[\"author_id\"]");
        assertThat(result.data()).containsEntry("pairsScored", "0");
    }

    @Test
    @DisplayName("an unreachable sidecar is a degraded run that says it could not be reached")
    void anUnreachableSidecarIsReportedAsDegraded() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        stubDatasets(idA, idB, List.of(column(idA, "author_id", false)), List.of(column(idB, "rid", false)));
        server.stop(0);

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("degraded", "true");
        assertThat(result.data().get("reason")).containsIgnoringCase("could not be reached");
        assertThat(result.data()).containsEntry("pairsScored", "0");
    }

    @Test
    @DisplayName("a clean run reports how many pairs it compared, and no degraded key at all")
    void aCleanRunReportsWhatItComparedAndIsNotDegraded() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        stubDatasets(idA, idB,
                List.of(column(idA, "venue_code", false), column(idA, "title", false)),
                List.of(column(idB, "rid", false), column(idB, "name", false), column(idB, "email", false)));
        // Every pair MEASURED — an overlap of zero, not a missing one.
        server.createContext("/operators/distance", ex -> {
            try {
                respondJson(ex, """
                    {"distances":[
                      {"candidate_column":"rid","candidate_index":0,"metric_distances":{"exact_mj":0.0,"exact_k":0.5},"name_distance":3.0},
                      {"candidate_column":"name","candidate_index":1,"metric_distances":{"exact_mj":0.0,"exact_k":0.5},"name_distance":3.0},
                      {"candidate_column":"email","candidate_index":2,"metric_distances":{"exact_mj":0.0,"exact_k":0.5},"name_distance":3.0}
                    ]}
                    """);
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        server.createContext("/operators/rank", ex -> {
            try {
                respondJson(ex, """
                    {"ranking":[
                      {"candidate_column":"rid","score":0.0},
                      {"candidate_column":"name","score":0.0},
                      {"candidate_column":"email","score":0.0}
                    ]}
                    """);
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("candidates", "[]");
        assertThat(result.data()).doesNotContainKey("degraded");
        assertThat(result.data()).containsEntry("pairsScored", "6");
        assertThat(result.data()).containsEntry("unsampledPairs", "0");
        assertThat(result.data()).containsEntry("profiledColumnsA", "2");
        assertThat(result.data()).containsEntry("profiledColumnsB", "3");
        assertThat(result.data()).containsEntry("datasetNameA", "papers.csv");
        assertThat(result.data()).containsEntry("datasetNameB", "researchers.csv");
    }

    @Test
    @DisplayName("one failing column degrades the run but keeps the candidates the other columns produced")
    void onePartialFailureKeepsTheOtherCandidatesAndNamesTheFailedColumn() throws Exception {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        stubDatasets(idA, idB,
                List.of(column(idA, "author_id", false), column(idA, "broken", false)),
                List.of(column(idB, "rid", false)));
        server.createContext("/operators/distance", ex -> {
            try {
                JsonNode body = new ObjectMapper().readTree(ex.getRequestBody().readAllBytes());
                if ("broken".equals(body.path("query").path("column_name").asText())) {
                    respondStatus(ex, 500, "{\"detail\":\"boom\"}");
                } else {
                    respondJson(ex, "{\"distances\":[]}");
                }
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        server.createContext("/operators/rank", ex -> {
            try { respondJson(ex, "{\"ranking\":[{\"candidate_column\":\"rid\",\"score\":0.4}]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString())));

        assertThat(result.success()).isTrue();
        List<Map<String, Object>> candidates = new ObjectMapper().readValue(
                result.data().get("candidates"), new TypeReference<List<Map<String, Object>>>() {});
        assertThat(candidates).extracting(c -> c.get("columnA")).containsExactly("author_id");
        assertThat(result.data()).containsEntry("degraded", "true");
        assertThat(result.data()).containsEntry("failedColumns", "[\"broken\"]");
        assertThat(result.data()).containsEntry("pairsScored", "1");
        assertThat(result.data().get("reason")).contains("1 of 2");
    }

    @Test
    @DisplayName("no measured profile on one side is its own answer: nothing compared, freyja never called, not degraded")
    void noMeasuredProfileIsReportedAsZeroProfiledColumnsWithoutCallingFreyja() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        stubDatasets(idA, idB, List.of(), List.of(column(idB, "rid", false)));
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/operators/distance", ex -> {
            calls.add("distance");
            try { respondJson(ex, "{\"distances\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("candidates", "[]");
        assertThat(result.data()).containsEntry("profiledColumnsA", "0");
        assertThat(result.data()).containsEntry("profiledColumnsB", "1");
        assertThat(result.data()).containsEntry("pairsScored", "0");
        assertThat(result.data()).doesNotContainKey("degraded");
        assertThat(calls).isEmpty();
    }

    // ── J3-F6: the evidence a person needs to refute a candidate travels with it ──────────────

    private static ColumnProfile measured(UUID datasetId, String name, String type, long distinct,
                                          double uniqueness) {
        return new ColumnProfile(
                UUID.randomUUID(), datasetId, name, 0, type,
                distinct, 3.0, uniqueness, 0.0,
                1, 1, 1, 0, 0,
                0, 0, 0, 0,
                10, 3, 6,
                1, 1, 1, 1, 0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    @Test
    @DisplayName("each candidate carries its evidence: estimated value overlap, cardinality ratio, uniqueness, types")
    void eachCandidateCarriesItsEvidence() throws Exception {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        stubDatasets(idA, idB,
                List.of(measured(idA, "author_id", "BIGINT", 300, 0.3)),
                List.of(measured(idB, "rid", "BIGINT", 1000, 1.0)));
        server.createContext("/operators/distance", ex -> {
            try {
                respondJson(ex, """
                    {"distances":[{"candidate_column":"rid","candidate_index":0,
                      "metric_distances":{"exact_mj":0.2,"exact_k":0.3},"name_distance":7.0}]}
                    """);
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        server.createContext("/operators/rank", ex -> {
            try { respondJson(ex, "{\"ranking\":[{\"candidate_column\":\"rid\",\"score\":0.31}]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString())));

        List<Map<String, Object>> candidates = new ObjectMapper().readValue(
                result.data().get("candidates"), new TypeReference<List<Map<String, Object>>>() {});
        assertThat(candidates).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) candidates.get(0).get("evidence");
        assertThat(evidence).isNotNull();
        // freyja's MJ is |A∩B|/(|A|+|B|) = J/(1+J); an MJ of 0.2 is a Jaccard of 0.25 — a quarter
        // of the two columns' distinct values in common.
        assertThat(((Number) evidence.get("valueOverlap")).doubleValue())
                .isCloseTo(0.25, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(((Number) evidence.get("cardinalityRatio")).doubleValue())
                .isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(((Number) evidence.get("distinctA")).longValue()).isEqualTo(300L);
        assertThat(((Number) evidence.get("distinctB")).longValue()).isEqualTo(1000L);
        assertThat(((Number) evidence.get("uniquenessA")).doubleValue()).isEqualTo(0.3);
        assertThat(((Number) evidence.get("uniquenessB")).doubleValue()).isEqualTo(1.0);
        assertThat(evidence).containsEntry("typeA", "BIGINT").containsEntry("typeB", "BIGINT");
        // |A∩B| = MJ·(300+1000) = 260 shared values, 260 of author_id's 300.
        assertThat(((Number) evidence.get("containment")).doubleValue())
                .isCloseTo(260.0 / 300.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(evidence).containsEntry("containmentOf", "A");
    }

    @Test
    @DisplayName("a pair freyja could not sample carries no overlap estimate rather than a zero")
    void anUnsampledPairCarriesNoOverlapEstimate() throws Exception {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        stubDatasets(idA, idB, List.of(column(idA, "author_id", false)), List.of(column(idB, "rid", false)));
        server.createContext("/operators/distance", ex -> {
            try {
                respondJson(ex, """
                    {"distances":[{"candidate_column":"rid","candidate_index":0,
                      "metric_distances":{"exact_k":0.5},"name_distance":7.0}]}
                    """);
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        server.createContext("/operators/rank", ex -> {
            try { respondJson(ex, "{\"ranking\":[{\"candidate_column\":\"rid\",\"score\":0.6}]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover",
                Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString(), "method", "ml")));

        List<Map<String, Object>> candidates = new ObjectMapper().readValue(
                result.data().get("candidates"), new TypeReference<List<Map<String, Object>>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) candidates.get(0).get("evidence");
        assertThat(evidence).containsKey("valueOverlap");
        assertThat(evidence.get("valueOverlap")).isNull();
    }

    // ── an unmeasured overlap is not a measured absence ─────────────────────────────────────

    private void stubDistanceAndRank(String distances, String ranking) {
        server.createContext("/operators/distance", ex -> {
            try { respondJson(ex, distances); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
        server.createContext("/operators/rank", ex -> {
            try { respondJson(ex, ranking); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
    }

    @Test
    @DisplayName("exact: pairs freyja ranked without an overlap (no value sample) are counted as unsampled, not as sharing nothing")
    void pairsWithoutAnOverlapAreCountedAsUnsampled() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        stubDatasets(idA, idB, List.of(column(idA, "author_ref", false)),
                List.of(column(idB, "rid", false), column(idB, "name", false)));
        // rid has no exact_mj (a pre-V45 profile of a >10-value key); name was measured at zero.
        stubDistanceAndRank("""
                {"distances":[
                  {"candidate_column":"rid","candidate_index":0,"metric_distances":{"exact_k":0.9},"name_distance":7.0},
                  {"candidate_column":"name","candidate_index":1,"metric_distances":{"exact_mj":0.0,"exact_k":0.9},"name_distance":7.0}
                ]}
                """, """
                {"ranking":[{"candidate_column":"rid","score":0.0},{"candidate_column":"name","score":0.0}]}
                """);

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("candidates", "[]");
        assertThat(result.data()).containsEntry("pairsScored", "2");
        assertThat(result.data()).containsEntry("unsampledPairs", "1");
        // Not a failure of the sidecar: the run completed, it just could not measure one pair.
        assertThat(result.data()).doesNotContainKey("degraded");
    }

    @Test
    @DisplayName("ml: no unsampled count — the model scores from every distance, so a missing overlap does not zero a pair")
    void theMlMethodReportsNoUnsampledCount() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        stubDatasets(idA, idB, List.of(column(idA, "author_ref", false)), List.of(column(idB, "rid", false)));
        stubDistanceAndRank("""
                {"distances":[{"candidate_column":"rid","candidate_index":0,"metric_distances":{"exact_k":0.9},"name_distance":7.0}]}
                """, """
                {"ranking":[{"candidate_column":"rid","score":0.0}]}
                """);

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover",
                Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString(), "method", "ml")));

        assertThat(result.data()).containsEntry("pairsScored", "1");
        assertThat(result.data()).doesNotContainKey("unsampledPairs");
    }

    @Test
    @DisplayName("a foreign key wholly inside a larger key reads as ~100% contained, where the Jaccard says 30%")
    void containmentSaysWhatTheJaccardUndersells() throws Exception {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        stubDatasets(idA, idB,
                List.of(measured(idA, "author_ref", "VARCHAR", 300, 0.3)),
                List.of(measured(idB, "rid", "VARCHAR", 1000, 1.0)));
        // All 300 author_ref values are among rid's 1000: J = 300/1000 = 0.3, MJ = J/(1+J).
        double mj = 0.3 / 1.3;
        stubDistanceAndRank("""
                {"distances":[{"candidate_column":"rid","candidate_index":0,
                  "metric_distances":{"exact_mj":%s,"exact_k":0.3},"name_distance":7.0}]}
                """.formatted(mj), """
                {"ranking":[{"candidate_column":"rid","score":0.2}]}
                """);

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover", Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString())));

        List<Map<String, Object>> candidates = new ObjectMapper().readValue(
                result.data().get("candidates"), new TypeReference<List<Map<String, Object>>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) candidates.get(0).get("evidence");
        assertThat(((Number) evidence.get("valueOverlap")).doubleValue())
                .isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(((Number) evidence.get("containment")).doubleValue())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(evidence).containsEntry("containmentOf", "A");
    }

    @Test
    @DisplayName("containment is null, not zero, when the overlap was not measured")
    void containmentIsNullWhenTheOverlapIsUnmeasured() throws Exception {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        stubDatasets(idA, idB,
                List.of(measured(idA, "author_ref", "VARCHAR", 300, 0.3)),
                List.of(measured(idB, "rid", "VARCHAR", 1000, 1.0)));
        stubDistanceAndRank("""
                {"distances":[{"candidate_column":"rid","candidate_index":0,"metric_distances":{"exact_k":0.3},"name_distance":7.0}]}
                """, """
                {"ranking":[{"candidate_column":"rid","score":0.4}]}
                """);

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "discover",
                Map.of("datasetIdA", idA.toString(), "datasetIdB", idB.toString(), "method", "ml")));

        List<Map<String, Object>> candidates = new ObjectMapper().readValue(
                result.data().get("candidates"), new TypeReference<List<Map<String, Object>>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) candidates.get(0).get("evidence");
        assertThat(evidence).containsKey("containment");
        assertThat(evidence.get("containment")).isNull();
        assertThat(evidence.get("containmentOf")).isNull();
    }
}
