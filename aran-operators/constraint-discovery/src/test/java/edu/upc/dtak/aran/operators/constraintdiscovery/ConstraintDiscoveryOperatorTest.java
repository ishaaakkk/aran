package edu.upc.dtak.aran.operators.constraintdiscovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The wrapper {@code ConstraintDiscoveryOperator} (parameter validation, LakeClient
 * interaction, error mapping) had zero coverage — the sole existing test in this
 * module (edu.upc.lima.ConstraintDiscoveryTest) exercises the LIMA algorithm itself,
 * one layer down. Reuses that test's proven minimal-CSV shape for the happy path here.
 */
class ConstraintDiscoveryOperatorTest {

    // A real UUID, not just any string: the operator does UUID.fromString(tenant.value())
    // itself (to call LakeClient), so a non-UUID tenant id fails there rather than
    // exercising whatever a test actually means to check.
    private static final String TENANT = UUID.randomUUID().toString();

    private final LakeClient lakeClient = mock(LakeClient.class);
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final ConstraintDiscoveryOperator operator =
            new ConstraintDiscoveryOperator(lakeClient, datasetRepository, new ObjectMapper());

    @Test
    void manifestDeclaresPingAndDiscoverWithOneRequiredParameter() {
        var manifest = operator.manifest();
        assertThat(manifest.operatorId()).isEqualTo("constraint-discovery");
        assertThat(manifest.operations().stream().map(OperationSpec::name))
                .containsExactlyInAnyOrder("ping", "discover");
        OperationSpec discover = manifest.operations().stream()
                .filter(op -> op.name().equals("discover")).findFirst().orElseThrow();
        assertThat(discover.parameters().stream().filter(p -> p.required()).map(p -> p.name()))
                .containsExactly("datasetId");
    }

    @Test
    void pingReportsOperational() {
        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "ping", Map.of()));
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("status", "ok");
        verifyNoInteractions(lakeClient, datasetRepository);
    }

    @Test
    void rejectsAnUnsupportedOperation() {
        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "explain", Map.of()));
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("UNSUPPORTED_OPERATION");
    }

    @Test
    void rejectsAMissingDatasetId() {
        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover", Map.of()));
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MISSING_PARAMETER");
    }

    @Test
    void rejectsANonUuidDatasetId() {
        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover", Map.of("datasetId", "not-a-uuid")));
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_PARAMETER");
    }

    @Test
    void reportsDatasetNotFound() {
        UUID datasetId = UUID.randomUUID();
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.empty());

        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover", Map.of("datasetId", datasetId.toString())));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("DATASET_NOT_FOUND");
    }

    @Test
    void reportsNoSnapshotWhenTheDatasetHasNeverBeenUploaded() {
        UUID datasetId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "unsnapshotted.csv", "d");
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));

        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover", Map.of("datasetId", datasetId.toString())));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("NO_SNAPSHOT");
        verifyNoInteractions(lakeClient);
    }

    @Test
    void surfacesALakeDownloadFailure() {
        UUID datasetId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "d", "snap-1");
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));
        when(lakeClient.downloadSnapshot(any(), eq("snap-1"))).thenThrow(new RuntimeException("lake unreachable"));

        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover", Map.of("datasetId", datasetId.toString())));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("LAKE_DOWNLOAD_FAILED");
    }

    @Test
    void discoversConstraintsFromASnapshotDownloadedFromTheLake() {
        // Same minimal, LIMA-friendly shape as edu.upc.lima.ConstraintDiscoveryTest:
        // colA determines colB, giving the sampler a real functional dependency to find.
        String csv = String.join("\n",
                "colA,colB,colC",
                "1,10.5,alpha",
                "1,10.5,beta",
                "2,20.0,gamma",
                "2,20.0,delta",
                "3,35.2,epsilon",
                "3,35.2,zeta",
                "");

        UUID datasetId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "d", "snap-1");
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));
        when(lakeClient.downloadSnapshot(any(), eq("snap-1")))
                .thenReturn(new LakeClient.SnapshotBytes(csv.getBytes(StandardCharsets.UTF_8), "text/csv"));

        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover", Map.of("datasetId", datasetId.toString())));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("constraints");
        // A well-formed JSON array, whatever LIMA happened to find for this shape — the
        // algorithm's own correctness is edu.upc.lima.ConstraintDiscoveryTest's job, not this
        // wrapper test's; this only proves the operator carries LIMA's output back intact.
        List<?> constraints = assertDoesNotThrowParsingAsJsonArray(result.data().get("constraints"));
        assertThat(constraints).isNotNull();
    }

    // ── Phase 3B (Task 4): LIMA discovers over the resolved WRAPPER OUTPUT ───────

    @Test
    void manifestDiscoverDeclaresOptionalWrapperSqlAndClassIri() {
        OperationSpec discover = operator.manifest().operations().stream()
                .filter(op -> op.name().equals("discover")).findFirst().orElseThrow();
        assertThat(discover.parameters().stream().filter(p -> !p.required()).map(p -> p.name()))
                .contains("wrapperSql", "classIri");
    }

    @Test
    void rejectsWrapperSqlWithAForbiddenFunctionCallBeforeAnySnapshotIsFetched() {
        // discover is reachable directly over the generic /api/operators/{id}/invoke endpoint,
        // which never screens an optional parameter's content — so the screen must live at THIS
        // operator's boundary (the Critical the reviewer caught in Task 3's ProfilingOperator). A
        // valid, snapshotted dataset is staged and the raw download stubbed so that, WITHOUT the
        // screen, control reaches execution and returns success: the screen is what changes the
        // outcome, and it must run before any tenant data is touched.
        UUID datasetId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "d", "snap-1");
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));
        when(lakeClient.downloadSnapshot(any(), eq("snap-1")))
                .thenReturn(new LakeClient.SnapshotBytes("a,b\n1,2\n2,3\n".getBytes(StandardCharsets.UTF_8), "text/csv"));

        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover", Map.of(
                "datasetId", datasetId.toString(),
                "wrapperSql", "SELECT * FROM read_csv_auto('/etc/passwd')")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_WRAPPER_SQL");
        // No snapshot fetched by EITHER path — the forbidden wrapper was rejected first, so nothing
        // ran against a DuckDB connection at all.
        verify(lakeClient, never()).downloadSnapshot(any(), any());
        verify(lakeClient, never()).downloadSnapshotTo(any(), any(), any());
    }

    @Test
    void rejectsWrapperSqlWithAStringLiteralInFromPositionBeforeAnySnapshotIsFetched() {
        // DuckDB's replacement scan reads `FROM '<file>'` as read_csv with NO function call in the
        // text — WrapperSqlValidator catches it on position, not spelling. Same guarantee as above.
        UUID datasetId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "d", "snap-1");
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));
        when(lakeClient.downloadSnapshot(any(), eq("snap-1")))
                .thenReturn(new LakeClient.SnapshotBytes("a,b\n1,2\n2,3\n".getBytes(StandardCharsets.UTF_8), "text/csv"));

        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover", Map.of(
                "datasetId", datasetId.toString(),
                "wrapperSql", "SELECT * FROM '/etc/passwd.csv'")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_WRAPPER_SQL");
        verify(lakeClient, never()).downloadSnapshot(any(), any());
        verify(lakeClient, never()).downloadSnapshotTo(any(), any(), any());
    }

    @Test
    void discoversAConstraintOverTheWrapperOutputThatDoesNotHoldOnTheRawSnapshot() {
        // The proof that the subject is genuinely the WRAPPER OUTPUT and not the raw snapshot.
        //
        // colA determines colB ONLY among the keep='y' rows. The keep='n' rows pair the same colA
        // values with different colB values, so the FD colA->colB is violated on the raw snapshot.
        // A wrapper that filters to keep='y' restores the FD — so discovering it can only mean LIMA
        // saw the filtered output, never the raw rows. (The perfect keep='y' subset is the very
        // shape edu.upc.lima.ConstraintDiscoveryTest proves LIMA discovers a colA/colB FD from.)
        //
        // colD duplicates colB on EVERY row, so the FD colB<->colD holds on the raw snapshot too:
        // that makes the raw run discover real constraints (not an empty result), so the "colA->colB
        // is absent from the raw run" assertion below is a genuine absence, not a vacuous noneMatch
        // over an empty list. The wrapper projects only colA, colB, so colD never reaches its output.
        String csv = String.join("\n",
                "colA,colB,colD,keep",
                "1,100,100,y", "1,100,100,y",
                "2,200,200,y", "2,200,200,y",
                "3,300,300,y", "3,300,300,y",
                "1,700,700,n", "2,700,700,n", "3,700,700,n",
                "1,800,800,n", "2,800,800,n", "3,800,800,n",
                "");

        UUID datasetId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "d", "snap-1");
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));
        // Wrapper path streams to a file (downloadSnapshotTo); raw path downloads bytes. Stub both
        // over the SAME csv so the only difference between the two runs is the wrapper.
        when(lakeClient.downloadSnapshotTo(any(), eq("snap-1"), any(Path.class))).thenAnswer(inv -> {
            Files.write(inv.getArgument(2, Path.class), csv.getBytes(StandardCharsets.UTF_8));
            return "text/csv";
        });
        when(lakeClient.downloadSnapshot(any(), eq("snap-1")))
                .thenReturn(new LakeClient.SnapshotBytes(csv.getBytes(StandardCharsets.UTF_8), "text/csv"));

        // Over the WRAPPER OUTPUT: the FD holds → it IS discovered.
        OperatorResult wrapped = operator.execute(new OperatorRequest(TENANT, "discover", Map.of(
                "datasetId", datasetId.toString(),
                "classIri", "http://example.org/Paper",
                "wrapperSql", "SELECT colA, colB FROM ${SNAPSHOT} WHERE keep = 'y'")));
        assertThat(wrapped.success()).isTrue();
        assertThat(wrapped.data()).containsEntry("subject", "WRAPPER_OUTPUT");
        assertThat(wrapped.data()).containsEntry("classIri", "http://example.org/Paper");
        assertThat(parseConstraints(wrapped.data().get("constraints")))
                .as("the FD colA->colB holds on the filtered wrapper output")
                .anyMatch(ConstraintDiscoveryOperatorTest::isColADeterminesColB);

        // Over the RAW snapshot: the keep='n' rows break the FD → it is NOT discovered.
        OperatorResult raw = operator.execute(new OperatorRequest(TENANT, "discover", Map.of(
                "datasetId", datasetId.toString())));
        assertThat(raw.success()).isTrue();
        List<String> rawDcs = parseConstraints(raw.data().get("constraints"));
        assertThat(rawDcs)
                .as("the raw run really did discover constraints (colB<->colD holds on every row),"
                        + " so the absence below is genuine, not a vacuous noneMatch over []")
                .isNotEmpty();
        assertThat(rawDcs)
                .as("the FD colA->colB is violated on the raw snapshot, so it must be absent")
                .noneMatch(ConstraintDiscoveryOperatorTest::isColADeterminesColB);
    }

    @Test
    void wrapperOutputDiscoveryIsResilientAndReturnsNoConstraintsWhenTheWrapperCannotRun() {
        // A wrapper that references a column the snapshot lacks fails inside DuckDB. Discovery is a
        // best-effort cost/quality input, so that must never break a caller: the contract is
        // constraints:[] + WARN, a SUCCESS with no constraints, never a failure — but the result
        // must SAY it degraded (audit CONSTRAINT_DISCOVERY-12), otherwise an outage is indexed by
        // the caller as "discovery complete, nothing found".
        String csv = "colA,colB\n1,100\n2,200\n";
        UUID datasetId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "d", "snap-1");
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));
        when(lakeClient.downloadSnapshotTo(any(), eq("snap-1"), any(Path.class))).thenAnswer(inv -> {
            Files.write(inv.getArgument(2, Path.class), csv.getBytes(StandardCharsets.UTF_8));
            return "text/csv";
        });

        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover", Map.of(
                "datasetId", datasetId.toString(),
                "wrapperSql", "SELECT no_such_column FROM ${SNAPSHOT}")));

        assertThat(result.success()).isTrue();
        assertThat(parseConstraints(result.data().get("constraints"))).isEmpty();
        assertThat(result.data()).containsEntry("degraded", "true");
        assertThat(result.data().get("reason"))
                .as("names what actually broke, so the WARN is not the only record of it")
                .isNotBlank();
    }

    @Test
    void anEmptyWrapperOutputIsACleanZeroAndIsNotReportedAsDegraded() {
        // The other half of CONSTRAINT_DISCOVERY-12: a genuinely empty subject must stay
        // distinguishable from an outage, or the degraded flag tells the caller nothing.
        String csv = "colA,colB\n1,100\n2,200\n";
        UUID datasetId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "d", "snap-1");
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));
        when(lakeClient.downloadSnapshotTo(any(), eq("snap-1"), any(Path.class))).thenAnswer(inv -> {
            Files.write(inv.getArgument(2, Path.class), csv.getBytes(StandardCharsets.UTF_8));
            return "text/csv";
        });

        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover", Map.of(
                "datasetId", datasetId.toString(),
                "wrapperSql", "SELECT colA, colB FROM ${SNAPSHOT} WHERE colA = 999")));

        assertThat(result.success()).isTrue();
        assertThat(parseConstraints(result.data().get("constraints"))).isEmpty();
        assertThat(result.data()).doesNotContainKey("degraded");
    }

    // ── CONSTRAINT_DISCOVERY-11: a one-row subject has no tuple pairs to sample ──────────

    @Test
    void aOneRowSnapshotYieldsNoConstraintsInsteadOfCrashingTheSampler() {
        // LIMA states constraints over PAIRS of distinct tuples: CSVDataset.sample draws
        // `random.nextInt(size - 1)`, which is nextInt(0) — an IllegalArgumentException — for a
        // one-row table. The `nrow <= 0` guard let that row through; a table with fewer than two
        // rows has no pair to state anything about, so it is an empty result, not a failure.
        String csv = "colA,colB\n1,100\n";
        UUID datasetId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "one-row.csv", "d", "snap-1");
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));
        when(lakeClient.downloadSnapshot(any(), eq("snap-1")))
                .thenReturn(new LakeClient.SnapshotBytes(csv.getBytes(StandardCharsets.UTF_8), "text/csv"));

        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover",
                Map.of("datasetId", datasetId.toString())));

        assertThat(result.success()).isTrue();
        assertThat(parseConstraints(result.data().get("constraints"))).isEmpty();
    }

    @Test
    void aOneRowWrapperOutputYieldsACleanZeroRatherThanADegradedResult() {
        // Same guard on the wrapper path — and it must land as the CLEAN zero, not as the
        // exception-swallowing degraded branch, since nothing actually failed.
        String csv = "colA,colB\n1,100\n2,200\n";
        UUID datasetId = UUID.randomUUID();
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "papers.csv", "d", "snap-1");
        when(datasetRepository.findById(any(), eq(datasetId))).thenReturn(Optional.of(dataset));
        when(lakeClient.downloadSnapshotTo(any(), eq("snap-1"), any(Path.class))).thenAnswer(inv -> {
            Files.write(inv.getArgument(2, Path.class), csv.getBytes(StandardCharsets.UTF_8));
            return "text/csv";
        });

        OperatorResult result = operator.execute(new OperatorRequest(TENANT, "discover", Map.of(
                "datasetId", datasetId.toString(),
                "wrapperSql", "SELECT colA, colB FROM ${SNAPSHOT} WHERE colA = 1")));

        assertThat(result.success()).isTrue();
        assertThat(parseConstraints(result.data().get("constraints"))).isEmpty();
        assertThat(result.data()).doesNotContainKey("degraded");
    }

    /** The FD colA->colB renders as {@code !(colA EQ colA & colB NE colB)} — see Predicate.toString. */
    private static boolean isColADeterminesColB(String dc) {
        return dc.contains("colA EQ colA") && dc.contains("colB NE colB");
    }

    private static List<String> parseConstraints(String json) {
        try {
            return new ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new AssertionError("constraints was not a JSON array of strings: " + json, e);
        }
    }

    private static List<?> assertDoesNotThrowParsingAsJsonArray(String json) {
        try {
            return new ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            throw new AssertionError("constraints was not a JSON array: " + json, e);
        }
    }
}
