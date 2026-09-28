package edu.upc.dtak.aran.operators.profiling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Prior to this test, the profiling module (a single class) had zero test coverage —
 * everything below exercises real branches: parameter validation, the freyja HTTP
 * round-trip (via a throwaway JDK HttpServer — no test infra needed), and the
 * optional column-profile persistence path.
 */
class ProfilingOperatorTest {

    private static final String FREYJA_SECRET = "test-freyja-secret";

    private HttpServer server;
    private DatasetRepository datasetRepository;
    private String freyjaUrl;
    private ProfilingOperator operator;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        datasetRepository = mock(DatasetRepository.class);
        freyjaUrl = "http://localhost:" + server.getAddress().getPort();
        operator = new ProfilingOperator(freyjaUrl, FREYJA_SECRET, new ObjectMapper(), datasetRepository);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void manifestDeclaresEveryParameterOptional() {
        var manifest = operator.manifest();
        assertThat(manifest.operatorId()).isEqualTo("profiling");
        assertThat(manifest.operations()).hasSize(1);
        OperationSpec spec = manifest.operations().get(0);
        assertThat(spec.name()).isEqualTo("profile");
        assertThat(spec.longRunning()).isTrue();
        // snapshotId is optional in the MANIFEST (OperatorInvocationController enforces required
        // params from it, and a federated=true call carries none) — execute() itself requires one
        // of snapshotId / federated+datasetId, asserted by rejectsAMissingSnapshotId below.
        assertThat(spec.parameters().stream().filter(p -> p.required())).isEmpty();
        // Phase 3B (Task 3): wrapperSql/classIri let the same op profile a wrapper's resolved
        // output instead of the raw snapshot, and stamp the persisted rows accordingly.
        // federated (Gap 2 follow-up) routes the suite over the dataset's live binding.
        // S5-12: `subject` is deliberately NOT here — it is derived from federated/wrapperSql, so
        // there is nothing for a caller to choose. A disagreeing value is still refused rather than
        // ignored (refusesACallerSubjectThatDisagrees), which is why it is absent, not rejected
        // outright.
        assertThat(spec.parameters().stream().map(p -> p.name()))
                .containsExactlyInAnyOrder("snapshotId", "varcharOnly",
                        "maxConcurrentColumnQueries", "datasetId",
                        "wrapperSql", "classIri", "federated");
    }

    @Test
    void rejectsAnOperationOtherThanProfile() {
        OperatorRequest request = new OperatorRequest("tenant-1", "explain", Map.of());
        OperatorResult result = operator.execute(request);
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("UNSUPPORTED_OPERATION");
        verifyNoInteractions(datasetRepository);
    }

    @Test
    void rejectsAMissingSnapshotId() {
        OperatorRequest request = new OperatorRequest("tenant-1", "profile", Map.of());
        OperatorResult result = operator.execute(request);
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MISSING_PARAMETER");
    }

    @Test
    @DisplayName("wrapperSql carrying a forbidden function call is rejected before freyja is ever called")
    void rejectsWrapperSqlWithForbiddenFunctionCall() {
        // This operator is directly reachable over HTTP by any authenticated tenant member
        // (POST /api/operators/profiling/invoke|submit) — OperatorInvocationController's
        // validateParameters only checks that REQUIRED params are present, never a parameter's
        // content, and wrapperSql is optional — so this is untrusted input at the point it
        // reaches freyja/the lake's real DuckDB connection, independent of whether some trusted
        // caller (IntegrationModelService's compile tail) already screened it.
        AtomicBoolean freyjaCalled = new AtomicBoolean(false);
        server.createContext("/operators/profile", ex -> {
            freyjaCalled.set(true);
            try { respondJson(ex, 200, "{\"columns\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorRequest request = new OperatorRequest("tenant-1", "profile", Map.of(
                "snapshotId", "snap-1",
                "wrapperSql", "SELECT * FROM read_csv_auto('/etc/passwd')"));
        OperatorResult result = operator.execute(request);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_WRAPPER_SQL");
        assertThat(result.errorMessage()).contains("read_csv_auto");
        assertThat(freyjaCalled.get())
                .as("freyja must never be called with a forbidden wrapperSql — this is the fail-closed gate")
                .isFalse();
    }

    @Test
    @DisplayName("wrapperSql with a string literal in table-reference position is rejected before freyja is ever called")
    void rejectsWrapperSqlWithStringLiteralInFromPosition() {
        // DuckDB's replacement scan turns `FROM '/etc/passwd.csv'` into a read_csv with no
        // function call anywhere in the text — a name-only screen is blind to it (see
        // WrapperSqlValidator's own javadoc). Same fail-closed-gate reasoning as above.
        AtomicBoolean freyjaCalled = new AtomicBoolean(false);
        server.createContext("/operators/profile", ex -> {
            freyjaCalled.set(true);
            try { respondJson(ex, 200, "{\"columns\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorRequest request = new OperatorRequest("tenant-1", "profile", Map.of(
                "snapshotId", "snap-1",
                "wrapperSql", "SELECT * FROM '/etc/passwd.csv'"));
        OperatorResult result = operator.execute(request);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_WRAPPER_SQL");
        assertThat(result.errorMessage()).contains("string literal");
        assertThat(freyjaCalled.get())
                .as("freyja must never be called with a forbidden wrapperSql — this is the fail-closed gate")
                .isFalse();
    }

    @Test
    void sendsTheConfiguredFreyjaBearerToken() {
        // freyja rejects unauthenticated callers once its own secret is set, so this
        // header is the thing that keeps profiling working. Assert it is on the wire
        // rather than trusting the plumbing.
        java.util.concurrent.atomic.AtomicReference<String> seenAuth =
                new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/operators/profile", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            try { respondJson(ex, 200, "{\"row_count\":1,\"columns\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        operator.execute(new OperatorRequest("tenant-1", "profile", Map.of("snapshotId", "snap-1")));

        assertThat(seenAuth.get()).isEqualTo("Bearer " + FREYJA_SECRET);
    }

    @Test
    void omitsTheAuthorizationHeaderWhenNoSecretIsConfigured() {
        // An empty secret must send no header at all, so a sidecar started before the
        // secret existed keeps working rather than receiving a malformed "Bearer ".
        ProfilingOperator noSecret =
                new ProfilingOperator(freyjaUrl, "", new ObjectMapper(), datasetRepository);
        java.util.concurrent.atomic.AtomicReference<String> seenAuth =
                new java.util.concurrent.atomic.AtomicReference<>("SENTINEL");
        server.createContext("/operators/profile", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            try { respondJson(ex, 200, "{\"row_count\":1,\"columns\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        noSecret.execute(new OperatorRequest("tenant-1", "profile", Map.of("snapshotId", "snap-1")));

        assertThat(seenAuth.get()).isNull();
    }

    @Test
    void surfacesANonTwoHundredFreyjaResponseAsProfilingError() {
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 500, "{\"detail\":\"boom\"}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorRequest request = new OperatorRequest("tenant-1", "profile", Map.of("snapshotId", "snap-1"));
        OperatorResult result = operator.execute(request);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROFILING_ERROR");
    }

    @Test
    @DisplayName("a freyja failure naming a federated DSN never carries its password into the"
            + " operator's error message")
    void aFreyjaFailureNeverCarriesTheDsnPassword() throws IOException {
        // DuckDB's refusal line as freyja relayed it before it redacted anything, JSON-encoded as
        // FastAPI sends it — which doubles the backslash of libpq's \' escape on the wire.
        String detail = "binding the federated scan failed: IO Error: Unable to connect to"
                + " Postgres at \"dbname='db' user='user' password='hunter2 x\\'y' host='db-host'"
                + " port=5432\": connection refused";
        String body = new ObjectMapper().writeValueAsString(Map.of("detail", detail));
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 502, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorResult result = operator.execute(
                new OperatorRequest("tenant-1", "profile", Map.of("snapshotId", "snap-1")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage())
                .doesNotContain("hunter2")
                .doesNotContain("y'")
                .contains("password=***")
                .contains("db-host")
                .contains("connection refused");
    }

    @Test
    void returnsTheRawFreyjaBodyOnSuccessWithoutADatasetId() {
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, "{\"columns\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        OperatorRequest request = new OperatorRequest("tenant-1", "profile", Map.of("snapshotId", "snap-1"));
        OperatorResult result = operator.execute(request);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("profile", "{\"columns\":[]}");
        verifyNoInteractions(datasetRepository);
    }

    @Test
    void persistsColumnProfilesWhenADatasetIdIsSupplied() {
        String body = """
            {"columns":[
              {"column_name":"title","column_index":0,"source_type":"string",
               "metrics":{"cardinality":12,"uniqueness":0.9,"incompleteness":0.0}}
            ]}
            """;
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "desc", "snap-1", java.util.List.of());
        when(datasetRepository.findById(any(), eq(datasetId)))
                .thenReturn(Optional.of(existing));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        OperatorRequest request = new OperatorRequest(
                "tenant-1", "profile",
                Map.of("snapshotId", "snap-1", "datasetId", datasetId.toString()));
        OperatorResult result = operator.execute(request);

        assertThat(result.success()).isTrue();
        verify(datasetRepository).save(any(), argThat((Dataset ds) -> ds.columns().size() == 1
                && ds.columns().get(0).columnName().equals("title")
                && ds.columns().get(0).cardinality() == 12));
    }

    @Test
    @DisplayName("freyja's value-hash sample is persisted on the profile — join discovery's exact score estimates overlap from it")
    void persistsTheValueHashSample() {
        String body = """
            {"columns":[
              {"column_name":"id","column_index":0,"source_type":"string",
               "metrics":{"cardinality":1000,"uniqueness":1.0,"incompleteness":0.0,
                          "freq_word_containment":["1","10","100"],
                          "value_hash_sample":["00411460f7c92d21","006f52e9102a8d3b"]}}
            ]}
            """;
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "customers.csv", "desc", "snap-1", List.of());
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        OperatorResult result = operator.execute(new OperatorRequest("tenant-1", "profile",
                Map.of("snapshotId", "snap-1", "datasetId", datasetId.toString())));

        assertThat(result.success()).isTrue();
        verify(datasetRepository).save(any(), argThat((Dataset ds) -> ds.columns().get(0).valueHashSample()
                .equals(List.of("00411460f7c92d21", "006f52e9102a8d3b"))));
    }

    @Test
    @DisplayName("persisted profiles default to subject=SNAPSHOT and classIri=null when not requested — pre-3B compatibility")
    void persistedProfilesDefaultToSnapshotSubject() {
        String body = """
            {"columns":[
              {"column_name":"title","column_index":0,"source_type":"string",
               "metrics":{"cardinality":12,"uniqueness":0.9,"incompleteness":0.0}}
            ]}
            """;
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "desc", "snap-1", List.of());
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        operator.execute(new OperatorRequest("tenant-1", "profile",
                Map.of("snapshotId", "snap-1", "datasetId", datasetId.toString())));

        verify(datasetRepository).save(any(), argThat((Dataset ds) -> {
            ColumnProfile cp = ds.columns().get(0);
            return "SNAPSHOT".equals(cp.subject()) && cp.classIri() == null;
        }));
    }

    @Test
    @DisplayName("a persistence failure is a failure result, not silent success (audit PROFILING-5)")
    void persistenceFailureFailsTheOperation() {
        String body = """
            {"columns":[
              {"column_name":"title","column_index":0,"source_type":"string",
               "metrics":{"cardinality":12,"uniqueness":0.9,"incompleteness":0.0}}
            ]}
            """;
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "desc", "snap-1", List.of());
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any(), any()))
                .thenThrow(new RuntimeException("duplicate key value violates \"uq_column_profile_identity\""));

        OperatorResult result = operator.execute(new OperatorRequest("tenant-1", "profile",
                Map.of("snapshotId", "snap-1", "datasetId", datasetId.toString())));

        // Callers that check result.success() — the compile tail above all — must learn the
        // evidence did NOT land; a swallowed WARN here is what hid V40/V44.
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROFILE_PERSISTENCE_FAILED");
    }

    @Test
    @DisplayName("a column named in freyja's errors map is not persisted — its missing metrics would zero-fill (audit PROFILING-4)")
    void columnsListedInFreyjaErrorsAreNotPersisted() {
        // freyja emits a column whose distribution query failed scalar-only, naming it in the
        // response's errors map. Jackson's path(...).asDouble(0.0) would fabricate every missing
        // distribution metric as 0.0 — persisted indistinguishably from real measurements.
        String body = """
            {"columns":[
              {"column_name":"title","column_index":0,"source_type":"string",
               "metrics":{"cardinality":12,"uniqueness":0.9,"incompleteness":0.0}},
              {"column_name":"broken","column_index":1,"source_type":"string",
               "metrics":{"cardinality":5,"uniqueness":0.5,"incompleteness":0.0}}
            ],
             "errors":{"broken":"distribution query failed: out of memory"}}
            """;
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "desc", "snap-1", List.of());
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        OperatorResult result = operator.execute(new OperatorRequest("tenant-1", "profile",
                Map.of("snapshotId", "snap-1", "datasetId", datasetId.toString())));

        assertThat(result.success()).isTrue();
        verify(datasetRepository).save(any(), argThat((Dataset ds) -> ds.columns().size() == 1
                && ds.columns().get(0).columnName().equals("title")));
    }

    @Test
    @DisplayName("wrapperSql is forwarded to freyja as wrapper_sql when provided")
    void sendsWrapperSqlInTheFreyjaRequestBodyWhenProvided() throws IOException {
        AtomicReference<String> seenBody = new AtomicReference<>();
        server.createContext("/operators/profile", ex -> {
            try {
                seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respondJson(ex, 200, "{\"columns\":[]}");
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        // classIri rides along because a wrapper-output profile is stored per class (S5-12); it is
        // Aran-side bookkeeping and must not appear in the freyja body, asserted below.
        operator.execute(new OperatorRequest("tenant-1", "profile", Map.of(
                "snapshotId", "snap-1",
                "classIri", "http://ex/Invoice",
                "wrapperSql", "SELECT \"x\" FROM ${SNAPSHOT}")));

        ObjectMapper om = new ObjectMapper();
        assertThat(seenBody.get()).doesNotContain("classIri").doesNotContain("class_iri");
        assertThat(om.readTree(seenBody.get()).get("wrapper_sql").asText())
                .isEqualTo("SELECT \"x\" FROM ${SNAPSHOT}");
    }

    @Test
    @DisplayName("wrapper_sql is absent from the freyja request body when not provided — byte-identical to before Task 3")
    void omitsWrapperSqlFromTheFreyjaRequestBodyWhenNotProvided() throws IOException {
        AtomicReference<String> seenBody = new AtomicReference<>();
        server.createContext("/operators/profile", ex -> {
            try {
                seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respondJson(ex, 200, "{\"columns\":[]}");
            } catch (IOException e) { throw new RuntimeException(e); }
        });

        operator.execute(new OperatorRequest("tenant-1", "profile", Map.of("snapshotId", "snap-1")));

        ObjectMapper om = new ObjectMapper();
        assertThat(om.readTree(seenBody.get()).has("wrapper_sql")).isFalse();
    }

    @Test
    @DisplayName("persisted profiles are stamped with the requested subject and classIri")
    void stampsPersistedProfilesWithTheGivenSubjectAndClassIri() {
        String body = """
            {"columns":[
              {"column_name":"http://ex/amount","column_index":0,"source_type":"string",
               "metrics":{"cardinality":5,"uniqueness":1.0,"incompleteness":0.0}}
            ]}
            """;
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "ds", "desc", "snap-1", List.of());
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        OperatorRequest request = new OperatorRequest("tenant-1", "profile", Map.of(
                "snapshotId", "snap-1",
                "datasetId", datasetId.toString(),
                "wrapperSql", "SELECT \"amount\" AS \"http://ex/amount\" FROM ${SNAPSHOT}",
                "classIri", "http://ex/Invoice",
                "subject", "WRAPPER_OUTPUT"));
        OperatorResult result = operator.execute(request);

        assertThat(result.success()).isTrue();
        verify(datasetRepository).save(any(), argThat((Dataset ds) -> {
            ColumnProfile cp = ds.columns().get(0);
            return ds.columns().size() == 1
                    && "WRAPPER_OUTPUT".equals(cp.subject())
                    && "http://ex/Invoice".equals(cp.classIri());
        }));
    }

    // ── federated=true: the suite runs over the live binding (Gap 2 follow-up) ──

    private ProfilingOperator federatedOperator(
            edu.upc.dtak.aran.core.domain.DataRepositoryRepository repoStore) {
        return new ProfilingOperator(freyjaUrl, FREYJA_SECRET, new ObjectMapper(),
                datasetRepository, Optional.of(repoStore), Optional.empty());
    }

    private static edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository federatedPostgres(
            UUID repoId) {
        return new edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository(
                repoId, UUID.randomUUID(), UUID.randomUUID(),
                "repo", "", "POSTGRESQL", "db-host", 5432, "db", "user", "pw", "public", true);
    }

    @Test
    @DisplayName("federated=true sends freyja the live binding as federated_scan and no snapshot_id")
    void federatedRequestCarriesTheLiveBinding() {
        AtomicReference<String> freyjaBody = new AtomicReference<>();
        String body = """
            {"columns":[
              {"column_name":"http://ex/id","column_index":0,"source_type":"string",
               "metrics":{"cardinality":44000000,"uniqueness":1.0,"incompleteness":0.0}}
            ]}
            """;
        server.createContext("/operators/profile", ex -> {
            freyjaBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { respondJson(ex, 200, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, repoId, "people", "desc", null, List.of());
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        var repoStore = mock(edu.upc.dtak.aran.core.domain.DataRepositoryRepository.class);
        when(repoStore.findById(any(), eq(repoId))).thenReturn(Optional.of(federatedPostgres(repoId)));

        OperatorResult result = federatedOperator(repoStore).execute(
                new OperatorRequest(UUID.randomUUID().toString(), "profile", Map.of(
                        "federated", "true",
                        "datasetId", datasetId.toString(),
                        "wrapperSql", "SELECT * FROM ${SNAPSHOT}",
                        "classIri", "http://ex/People",
                        "subject", "FEDERATED_LIVE")));

        assertThat(result.success()).isTrue();
        // The exact same scan the query resolver would emit — the attached catalog's table plus
        // the setup that attaches it — and NOTHING staged: no snapshot_id anywhere in the request.
        assertThat(freyjaBody.get()).contains("\"federated_scan\"")
                .contains(edu.upc.dtak.aran.core.source.FederatedScan.attachAlias(repoId))
                .contains("INSTALL postgres")
                .contains("(TYPE postgres, SECRET ")
                .doesNotContain("snapshot_id");
        // The persisted evidence is stamped with the live subject, untouched from freyja.
        verify(datasetRepository).save(any(), argThat((Dataset ds) -> {
            ColumnProfile cp = ds.columns().get(0);
            return "FEDERATED_LIVE".equals(cp.subject())
                    && cp.cardinality() == 44_000_000L
                    && cp.uniqueness() == 1.0;
        }));
    }

    @Test
    @DisplayName("federated=true over a dataset that is not served federated is refused, before freyja")
    void federatedOverNonFederatedDatasetIsRefused() {
        AtomicBoolean freyjaCalled = new AtomicBoolean(false);
        server.createContext("/operators/profile", ex -> {
            freyjaCalled.set(true);
            respondJson(ex, 200, "{\"columns\":[]}");
        });

        UUID datasetId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, repoId, "people", "desc", null, List.of());
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));
        var repoStore = mock(edu.upc.dtak.aran.core.domain.DataRepositoryRepository.class);
        // federated flag OFF → SourceAccess resolves MATERIALIZED → no live binding to profile.
        when(repoStore.findById(any(), eq(repoId))).thenReturn(Optional.of(
                new edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository(
                        repoId, UUID.randomUUID(), UUID.randomUUID(), "repo", "",
                        "POSTGRESQL", "db-host", 5432, "db", "user", "pw", "public", false)));

        OperatorResult result = federatedOperator(repoStore).execute(
                new OperatorRequest(UUID.randomUUID().toString(), "profile", Map.of(
                        "federated", "true", "datasetId", datasetId.toString())));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("NOT_FEDERATED");
        assertThat(freyjaCalled).isFalse();

        // And a build with no repository store wired says so, rather than guessing a binding.
        OperatorResult noStore = new ProfilingOperator(freyjaUrl, FREYJA_SECRET,
                new ObjectMapper(), datasetRepository).execute(
                new OperatorRequest(UUID.randomUUID().toString(), "profile", Map.of(
                        "federated", "true", "datasetId", datasetId.toString())));
        assertThat(noStore.success()).isFalse();
        assertThat(noStore.errorCode()).isEqualTo("FEDERATED_PROFILING_UNAVAILABLE");
    }

    // ── S5-12: the subject is DERIVED from what was profiled, never taken on trust ──────────
    // A profile of a moving live source persisted as SNAPSHOT is "exact uniqueness" to
    // AranStatistic.isSingleColumnKey, which skips only HARVESTED and FEDERATED_LIVE — verified-key
    // evidence that, with a DECLARED edge, lets AranJoinRemoveRule remove a join. The operator is
    // reachable at POST /api/operators/profiling/invoke by any tenant member with a FULL-scope
    // credential, and validateParameters only checks that REQUIRED parameters are PRESENT, so
    // before this the label was whatever the caller typed.

    private String subjectOfOnlyPersistedProfile(Map<String, String> params) {
        server.createContext("/operators/profile", ex -> {
            try {
                respondJson(ex, 200, """
                    {"columns":[
                      {"column_name":"c","column_index":0,"source_type":"string",
                       "metrics":{"cardinality":5,"uniqueness":1.0,"incompleteness":0.0}}
                    ]}
                    """);
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        UUID datasetId = UUID.fromString(params.get("datasetId"));
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "ds", "desc", "snap-1", List.of());
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(existing));
        AtomicReference<String> seen = new AtomicReference<>();
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> {
            Dataset saved = inv.getArgument(1);
            seen.set(saved.columns().get(0).subject());
            return saved;
        });
        OperatorResult r = operator.execute(new OperatorRequest("tenant-1", "profile", params));
        assertThat(r.success()).as("%s / %s", r.errorCode(), r.errorMessage()).isTrue();
        return seen.get();
    }

    @Test
    @DisplayName("no wrapperSql and not federated derives SNAPSHOT")
    void derivesSnapshotForAPlainSnapshotProfile() {
        assertThat(subjectOfOnlyPersistedProfile(Map.of(
                "snapshotId", "snap-1",
                "datasetId", UUID.randomUUID().toString())))
                .isEqualTo(ColumnProfile.SUBJECT_SNAPSHOT);
    }

    @Test
    @DisplayName("wrapperSql present derives WRAPPER_OUTPUT, with no subject parameter given")
    void derivesWrapperOutputFromWrapperSql() {
        assertThat(subjectOfOnlyPersistedProfile(Map.of(
                "snapshotId", "snap-1",
                "datasetId", UUID.randomUUID().toString(),
                "wrapperSql", "SELECT \"c\" AS \"http://ex/c\" FROM ${SNAPSHOT}",
                "classIri", "http://ex/Invoice")))
                .isEqualTo("WRAPPER_OUTPUT");
    }

    @Test
    @DisplayName("federated=true derives FEDERATED_LIVE even with a wrapperSql, with no subject parameter given")
    void derivesFederatedLiveEvenWithAWrapper() {
        String body = """
            {"columns":[
              {"column_name":"http://ex/id","column_index":0,"source_type":"string",
               "metrics":{"cardinality":7,"uniqueness":1.0,"incompleteness":0.0}}
            ]}
            """;
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
        UUID datasetId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        when(datasetRepository.findById(any(), eq(datasetId)))
                .thenReturn(Optional.of(new Dataset(datasetId, repoId, "people", "desc", null, List.of())));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        var repoStore = mock(edu.upc.dtak.aran.core.domain.DataRepositoryRepository.class);
        when(repoStore.findById(any(), eq(repoId))).thenReturn(Optional.of(federatedPostgres(repoId)));

        OperatorResult result = federatedOperator(repoStore).execute(
                new OperatorRequest(UUID.randomUUID().toString(), "profile", Map.of(
                        "federated", "true",
                        "datasetId", datasetId.toString(),
                        "wrapperSql", "SELECT * FROM ${SNAPSHOT}",
                        "classIri", "http://ex/People")));

        assertThat(result.success()).isTrue();
        verify(datasetRepository).save(any(), argThat((Dataset ds) ->
                ColumnProfile.SUBJECT_FEDERATED_LIVE.equals(ds.columns().get(0).subject())));
    }

    @Test
    @DisplayName("a caller-supplied subject that disagrees with what is being profiled is refused, not silently overridden")
    void refusesACallerSubjectThatDisagrees() {
        AtomicBoolean freyjaCalled = new AtomicBoolean(false);
        server.createContext("/operators/profile", ex -> {
            freyjaCalled.set(true);
            respondJson(ex, 200, "{\"columns\":[]}");
        });

        // The wrong-rows shape: live numbers asked to be labelled SNAPSHOT, the one subject key
        // detection trusts.
        OperatorResult live = operator.execute(new OperatorRequest("tenant-1", "profile", Map.of(
                "federated", "true",
                "datasetId", UUID.randomUUID().toString(),
                "subject", ColumnProfile.SUBJECT_SNAPSHOT)));
        assertThat(live.success()).isFalse();
        assertThat(live.errorCode()).isEqualTo("INVALID_PARAMETER");
        assertThat(live.errorMessage()).contains("FEDERATED_LIVE").contains("SNAPSHOT");

        // And the wrapper-output shape, for the same reason.
        OperatorResult wrapper = operator.execute(new OperatorRequest("tenant-1", "profile", Map.of(
                "snapshotId", "snap-1",
                "datasetId", UUID.randomUUID().toString(),
                "wrapperSql", "SELECT * FROM ${SNAPSHOT}",
                "classIri", "http://ex/Invoice",
                "subject", ColumnProfile.SUBJECT_SNAPSHOT)));
        assertThat(wrapper.success()).isFalse();
        assertThat(wrapper.errorCode()).isEqualTo("INVALID_PARAMETER");

        // A HARVESTED claim has no derivation at all — this operator never produces one.
        OperatorResult harvested = operator.execute(new OperatorRequest("tenant-1", "profile", Map.of(
                "snapshotId", "snap-1",
                "datasetId", UUID.randomUUID().toString(),
                "subject", ColumnProfile.SUBJECT_HARVESTED)));
        assertThat(harvested.success()).isFalse();
        assertThat(harvested.errorCode()).isEqualTo("INVALID_PARAMETER");

        assertThat(freyjaCalled).as("refused before any work was done").isFalse();
        verifyNoInteractions(datasetRepository);
    }

    @Test
    @DisplayName("a caller-supplied subject that AGREES with the derivation is accepted (the three production call sites)")
    void acceptsACallerSubjectThatAgrees() {
        assertThat(subjectOfOnlyPersistedProfile(Map.of(
                "snapshotId", "snap-1",
                "datasetId", UUID.randomUUID().toString(),
                "wrapperSql", "SELECT * FROM ${SNAPSHOT}",
                "classIri", "http://ex/Invoice",
                "subject", "WRAPPER_OUTPUT")))
                .isEqualTo("WRAPPER_OUTPUT");
    }

    @Test
    @DisplayName("wrapperSql without classIri is refused — a WRAPPER_OUTPUT row with no class cannot be read back")
    void refusesWrapperSqlWithoutClassIri() {
        AtomicBoolean freyjaCalled = new AtomicBoolean(false);
        server.createContext("/operators/profile", ex -> {
            freyjaCalled.set(true);
            respondJson(ex, 200, "{\"columns\":[]}");
        });

        OperatorResult result = operator.execute(new OperatorRequest("tenant-1", "profile", Map.of(
                "snapshotId", "snap-1",
                "datasetId", UUID.randomUUID().toString(),
                "wrapperSql", "SELECT * FROM ${SNAPSHOT}")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MISSING_PARAMETER");
        assertThat(result.errorMessage()).contains("classIri");
        assertThat(freyjaCalled).isFalse();
    }

    private static ColumnProfile snapshotProfile(String columnName) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), columnName, 0, "VARCHAR",
                10, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    private static ColumnProfile wrapperOutputProfile(String columnName, String classIri) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), columnName, 0, "VARCHAR",
                10, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                "WRAPPER_OUTPUT", classIri);
    }

    @Test
    @DisplayName("persisting one (subject, classIri) generation preserves the dataset's other profiles"
            + " instead of replacing the whole column list")
    void persistingPreservesOtherSubjectClassGenerations() {
        String body = """
            {"columns":[
              {"column_name":"http://ex/amount","column_index":0,"source_type":"string",
               "metrics":{"cardinality":5,"uniqueness":1.0,"incompleteness":0.0}}
            ]}
            """;
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        ColumnProfile snapshotBaseline = snapshotProfile("raw_col");
        ColumnProfile otherClassWrapperOutput = wrapperOutputProfile("http://ex/other_col", "http://ex/OtherClass");
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "ds", "desc", "snap-1",
                List.of(snapshotBaseline, otherClassWrapperOutput));
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        operator.execute(new OperatorRequest("tenant-1", "profile", Map.of(
                "snapshotId", "snap-1",
                "datasetId", datasetId.toString(),
                "wrapperSql", "SELECT 1",
                "classIri", "http://ex/Invoice",
                "subject", "WRAPPER_OUTPUT")));

        verify(datasetRepository).save(any(), argThat((Dataset ds) ->
                ds.columns().size() == 3
                        && ds.columns().stream().anyMatch(c -> c.columnName().equals("raw_col"))
                        && ds.columns().stream().anyMatch(c -> c.columnName().equals("http://ex/other_col"))
                        && ds.columns().stream().anyMatch(c -> c.columnName().equals("http://ex/amount"))));
    }

    @Test
    @DisplayName("re-profiling the same (subject, classIri) replaces only that generation, not the whole dataset")
    void reprofilingTheSameGenerationReplacesOnlyIt() {
        String body = """
            {"columns":[
              {"column_name":"http://ex/new_col","column_index":0,"source_type":"string",
               "metrics":{"cardinality":5,"uniqueness":1.0,"incompleteness":0.0}}
            ]}
            """;
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        ColumnProfile snapshotBaseline = snapshotProfile("raw_col");
        ColumnProfile staleWrapperOutput = wrapperOutputProfile("http://ex/old_col", "http://ex/Invoice");
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "ds", "desc", "snap-1",
                List.of(snapshotBaseline, staleWrapperOutput));
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        operator.execute(new OperatorRequest("tenant-1", "profile", Map.of(
                "snapshotId", "snap-1",
                "datasetId", datasetId.toString(),
                "wrapperSql", "SELECT 1",
                "classIri", "http://ex/Invoice",
                "subject", "WRAPPER_OUTPUT")));

        verify(datasetRepository).save(any(), argThat((Dataset ds) ->
                ds.columns().size() == 2
                        && ds.columns().stream().anyMatch(c -> c.columnName().equals("raw_col"))
                        && ds.columns().stream().anyMatch(c -> c.columnName().equals("http://ex/new_col"))
                        && ds.columns().stream().noneMatch(c -> c.columnName().equals("http://ex/old_col"))));
    }

    @Test
    @DisplayName("persisting profiles preserves graphJson and bootstrapStatus rather than wiping them")
    void persistingProfilesPreservesOtherDatasetFields() {
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, "{\"columns\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "ds", "desc", "snap-1",
                List.of(), "{\"some\":\"graph\"}", DatasetStatus.READY);
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        operator.execute(new OperatorRequest("tenant-1", "profile",
                Map.of("snapshotId", "snap-1", "datasetId", datasetId.toString())));

        verify(datasetRepository).save(any(), argThat((Dataset ds) ->
                "{\"some\":\"graph\"}".equals(ds.graphJson())
                        && ds.bootstrapStatus() == DatasetStatus.READY));
    }

    // ── V40/V44: a lost write must not be reported as a successful profiling run ────────────────
    //
    // Live on 2026-08-27 the persist step failed on a duplicate key, the operator logged an ERROR
    // and still answered {"success": true} — so the compile that triggered it carried on believing
    // it had fresh cost evidence while the planner kept costing off whatever the first pass saw.
    // The loss was visible only in the server log, which is not where any caller looks.

    @Test
    @DisplayName("a failed persist is reported as a failure, not as success with an ERROR in the log")
    void persistenceFailureIsReportedAsFailure() {
        String body = """
            {"columns":[
              {"column_name":"title","column_index":0,"source_type":"string",
               "metrics":{"cardinality":12,"uniqueness":0.9,"incompleteness":0.0}}
            ]}
            """;
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, body); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "desc", "snap-1", List.of());
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any(), any()))
                .thenThrow(new IllegalStateException("duplicate key value violates unique constraint"));

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "profile",
                Map.of("snapshotId", "snap-1", "datasetId", datasetId.toString())));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROFILE_PERSISTENCE_FAILED");
        assertThat(result.errorMessage())
                .contains(datasetId.toString())
                .contains("duplicate key value violates unique constraint");
    }

    @Test
    @DisplayName("a persist that succeeds still reports success and the raw freyja body")
    void successfulPersistStillReportsSuccess() {
        server.createContext("/operators/profile", ex -> {
            try { respondJson(ex, 200, "{\"columns\":[]}"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });

        UUID datasetId = UUID.randomUUID();
        Dataset existing = new Dataset(datasetId, UUID.randomUUID(), "ds", "desc", "snap-1", List.of());
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(existing));
        when(datasetRepository.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        OperatorResult result = operator.execute(new OperatorRequest(
                "tenant-1", "profile",
                Map.of("snapshotId", "snap-1", "datasetId", datasetId.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("profile", "{\"columns\":[]}");
    }
}
