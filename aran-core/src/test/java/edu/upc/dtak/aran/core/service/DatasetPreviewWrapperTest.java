package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetPreview;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository;
import edu.upc.dtak.aran.core.fake.UncappedLicenses;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.tenant.TenantContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T3.6 (audit WRAPPER_VERIFY-3/-4/-5): {@code DatasetService.previewWrapper} executes
 * request-body SQL over a real snapshot on a real DuckDB connection and had no tests at all —
 * the audit called it the highest-risk untested stage of its pipeline, and
 * {@code DatasetBootstrapIntegrationIT} (~:132-140) records the same gap from the other side.
 *
 * <p>Each test here pins one of the three defects that were live in that method, plus the
 * screen that was already correct and must stay so.
 */
class DatasetPreviewWrapperTest {

    @TempDir
    Path tmp;

    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final DataRepositoryService repositoryService = mock(DataRepositoryService.class);
    private final LakeClient lakeClient = mock(LakeClient.class);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();

    private final AuthenticatedUser caller =
            new AuthenticatedUser(UUID.randomUUID(), "subject", tenantId, TenantRole.OWNER);

    private DatasetService service() {
        return new DatasetService(
                datasetRepository, repositoryService, lakeClient,
                new OperatorOrchestrator(List.of()), mock(JobService.class),
                new UsageService(new RecordingUsageEventRepository()),
                UncappedLicenses.create(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                mock(IntegrationModelService.class), Optional.empty());
    }

    /** A dataset with a snapshot the fake lake serves as {@code bytes} under {@code contentType}. */
    private void snapshotIs(String datasetName, byte[] bytes, String contentType) {
        Dataset dataset = new Dataset(datasetId, repositoryId, datasetName, "", "snap-1");
        when(datasetRepository.findById(any(TenantContext.class), eq(datasetId)))
                .thenReturn(Optional.of(dataset));
        when(lakeClient.downloadSnapshot(eq(tenantId), eq("snap-1")))
                .thenReturn(new LakeClient.SnapshotBytes(bytes, contentType));
    }

    private DatasetPreview preview(String wrapperSql) {
        return service().previewWrapper(caller, projectId, repositoryId, datasetId, wrapperSql);
    }

    /** A two-row parquet file, written by the same engine the preview reads it with. */
    private byte[] parquetBytes() throws Exception {
        Path file = tmp.resolve("fixture.parquet");
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT 1 AS id, 'ada' AS name UNION ALL SELECT 2, 'bob') TO '"
                    + file.toAbsolutePath() + "' (FORMAT PARQUET)");
        }
        return Files.readAllBytes(file);
    }

    @Test
    @DisplayName("WRAPPER_VERIFY-4: a parquet snapshot previews, recognised by its PAR1 magic alone")
    void previewsAParquetSnapshot() throws Exception {
        // Content type deliberately uninformative: a relational or object-storage import is the
        // reason parquet reaches this path, and the bytes are what must decide the reader.
        snapshotIs("orders", parquetBytes(), "application/octet-stream");

        DatasetPreview result = preview("SELECT * FROM ${SNAPSHOT}");

        assertThat(result.headers()).containsExactly("id", "name");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).containsExactly("1", "ada");
    }

    @Test
    @DisplayName("WRAPPER_VERIFY-5: a trailing -- comment cannot swallow the row cap")
    void trailingCommentCannotUncapThePreview() {
        String csv = "id,name\n" + IntStream.rangeClosed(1, 30)
                .mapToObj(i -> i + ",row" + i)
                .collect(Collectors.joining("\n")) + "\n";
        snapshotIs("people.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv");

        // Textual " LIMIT 20" appended after this comment is commented out; wrapping is not.
        DatasetPreview result = preview("SELECT * FROM ${SNAPSHOT} -- every row, please");

        assertThat(result.rows()).hasSize(20);
    }

    @Test
    @DisplayName("WRAPPER_VERIFY-5: the cap wraps rather than appends, so a comment or a semicolon survives")
    void theCapWrapsTheStatement() {
        // The other two shapes the old textual append got wrong. A trailing semicolon cannot
        // reach the engine today (Calcite's parser refuses it in the screen), so this pins the
        // helper itself rather than claiming an end-to-end behaviour the screen forbids.
        assertThat(DatasetService.capPreviewRows("SELECT * FROM src_snapshot -- all"))
                .isEqualTo("SELECT * FROM (\nSELECT * FROM src_snapshot -- all\n) AS _aran_preview LIMIT 20");
        assertThat(DatasetService.capPreviewRows("SELECT * FROM src_snapshot ;  "))
                .isEqualTo("SELECT * FROM (\nSELECT * FROM src_snapshot\n) AS _aran_preview LIMIT 20");
    }

    @Test
    @DisplayName("the wrapper screen refuses a file-reading preview before anything is downloaded")
    void fileReadingWrapperIsRefusedByTheScreen() {
        assertThatThrownBy(() -> preview("SELECT * FROM read_csv_auto('/etc/passwd')"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden");

        // Fail-closed: the screen runs before the repository is read or the lake is called.
        verifyNoInteractions(lakeClient);
    }

    @Test
    @DisplayName("the wrapper screen refuses a replacement-scan preview (bare literal in FROM)")
    void replacementScanWrapperIsRefusedByTheScreen() {
        assertThatThrownBy(() -> preview("SELECT * FROM '/etc/passwd'"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(lakeClient);
    }

    @Test
    @DisplayName("WRAPPER_VERIFY-3: the preview connection runs locked down, screen or no screen")
    void previewConnectionIsLockedDown() {
        snapshotIs("people.csv", "id,name\n1,ada\n".getBytes(StandardCharsets.UTF_8), "text/csv");

        // Asked of the connection the user's own SQL runs on — the second layer is either
        // installed there or it is nowhere.
        DatasetPreview result = preview(
                "SELECT current_setting('enable_external_access') AS locked FROM ${SNAPSHOT}");

        assertThat(result.rows().get(0)).containsExactly("false");
    }

    @Test
    @DisplayName("WRAPPER_VERIFY-3: the lockdown refuses a read outside the preview's own directory")
    void lockdownRefusesReadsOutsideTheScratchDirectory() throws Exception {
        // Written against a statement the screen would refuse and run past it, exactly as
        // DuckDbLockdownTest does: the point is what happens when the screen is wrong.
        Path allowed = Files.createDirectory(tmp.resolve("allowed"));
        Path snapshot = allowed.resolve("snapshot.csv");
        Files.writeString(snapshot, "col\nvisible\n");
        Path secret = Files.writeString(tmp.resolve("secret.csv"), "col\nSHOULD_NOT_BE_READABLE\n");

        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE OR REPLACE VIEW src_snapshot AS SELECT * FROM read_csv_auto('"
                        + snapshot.toAbsolutePath() + "')");
                DatasetService.lockDownFilesystem(st, allowed);
            }
            // A statement DuckDB refuses is also a statement DuckDB closes, so each assertion
            // gets its own — otherwise the second failure is "Statement was closed", not the
            // refusal being asserted.
            try (Statement st = conn.createStatement()) {
                assertThat(st.executeQuery("SELECT * FROM src_snapshot").next()).isTrue();
            }
            // Both shapes the screen refuses — named reader and replacement scan — now fail at
            // the engine as well. This is the belt to the screen's braces.
            assertThatThrownBy(() -> query(conn,
                    "SELECT * FROM read_csv_auto('" + secret.toAbsolutePath() + "')"))
                    .hasMessageContaining("disabled by configuration");
            assertThatThrownBy(() -> query(conn,
                    "SELECT * FROM '" + secret.toAbsolutePath() + "'"))
                    .hasMessageContaining("disabled by configuration");
            assertThatThrownBy(() -> query(conn, "SET enable_external_access=true"))
                    .hasMessageContaining("locked");
        }
    }

    @Test
    @DisplayName("WRAPPER_VERIFY-5: the snapshot bytes are deleted whether the preview works or throws")
    void scratchDirectoryIsAlwaysDeleted() {
        snapshotIs("people.csv", "id,name\n1,ada\n".getBytes(StandardCharsets.UTF_8), "text/csv");
        long before = scratchDirCount();

        assertThat(preview("SELECT * FROM ${SNAPSHOT}").rows()).hasSize(1);
        assertThat(scratchDirCount()).isEqualTo(before);

        // Passes the screen (it is a plain projection over ${SNAPSHOT}) and fails inside DuckDB.
        assertThatThrownBy(() -> preview("SELECT no_such_column FROM ${SNAPSHOT}"))
                .isInstanceOf(PreviewFailedException.class);
        assertThat(scratchDirCount()).isEqualTo(before);
    }

    /** Runs one statement on its own Statement, so a refusal cannot poison the next assertion. */
    private static void query(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** How many preview scratch directories the JVM's temp root is currently holding. */
    private static long scratchDirCount() {
        try (var entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return entries.filter(p -> p.getFileName().toString().startsWith("aran-wrapper-preview-"))
                    .count();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a JSON snapshot still previews — the pre-existing branch is not a casualty")
    void previewsAJsonSnapshot() {
        snapshotIs("people.json",
                "[{\"id\":1,\"name\":\"ada\"}]".getBytes(StandardCharsets.UTF_8),
                "application/json");

        assertThat(preview("SELECT * FROM ${SNAPSHOT}").rows()).hasSize(1);
    }

    @Test
    @DisplayName("a dataset with no snapshot is refused, not previewed")
    void refusesADatasetWithNoSnapshot() {
        when(datasetRepository.findById(any(TenantContext.class), eq(datasetId)))
                .thenReturn(Optional.of(new Dataset(datasetId, repositoryId, "no-snapshot", "")));

        // A 409 with a sentence since J2-F12 (an unmapped IllegalStateException used to be a 500).
        assertThatThrownBy(() -> preview("SELECT * FROM ${SNAPSHOT}"))
                .isInstanceOf(ConflictException.class);

        verifyNoInteractions(lakeClient);
    }

    @Test
    @DisplayName("a lake failure surfaces as PreviewFailedException, not a raw client error")
    void lakeFailureBecomesPreviewFailed() {
        Dataset dataset = new Dataset(datasetId, repositoryId, "people.csv", "", "snap-1");
        when(datasetRepository.findById(any(TenantContext.class), eq(datasetId)))
                .thenReturn(Optional.of(dataset));
        when(lakeClient.downloadSnapshot(eq(tenantId), anyString()))
                .thenThrow(new IllegalStateException("lake down"));

        assertThatThrownBy(() -> preview("SELECT * FROM ${SNAPSHOT}"))
                .isInstanceOf(PreviewFailedException.class);
    }
}
