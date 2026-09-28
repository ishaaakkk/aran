package edu.upc.dtak.aran.operators.queryengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * What a query's filesystem lockdown allowlists, and that it holds between tenants.
 *
 * <p>The lockdown is the second layer under the SQL screen: the connection is what refuses the
 * read the screen misses. Both caches used to be allowlisted by their root, one directory shared by
 * every tenant, so inside the lockdown a statement that slipped the screen (a quoted path in
 * {@code FROM}, say) could still read every other tenant's cached snapshots and wrapper outputs —
 * the lockdown was a boundary to the host, and a cross-tenant boundary only in name. The shared
 * working area {@code aran-query-work} was allowlisted whole for the same reason, exposing every
 * running query's downloads and every result waiting to be streamed; each query now works in its
 * own {@link QueryWorkspace}.
 */
class QueryFileRootsTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @TempDir Path snapshotDir;
    @TempDir Path wrapperDir;

    @Test
    @DisplayName("a query's allowlist names its own tenant's cache directories, never a shared cache root")
    void allowlistNamesOnlyTheTenantsOwnCacheDirectories() throws Exception {
        SnapshotCache snapshots = new SnapshotCache(mock(LakeClient.class), snapshotDir.toString(),
                100_000_000L);
        WrapperOutputCache wrappers = new WrapperOutputCache(wrapperDir.toString(), 64L << 20);
        QueryEngineOperator operator = operator(snapshots, wrappers);

        try (QueryWorkspace workspace = QueryWorkspace.create()) {
            List<Path> roots = operator.queryFileRoots(TENANT_A, workspace.dir());

            assertThat(roots).containsExactlyInAnyOrder(workspace.dir(),
                    snapshots.tenantRoot(TENANT_A).orElseThrow(),
                    wrappers.tenantRoot(TENANT_A).orElseThrow());
            assertThat(roots).doesNotContain(QueryTempDir.get(), snapshotDir, wrapperDir,
                    snapshots.tenantRoot(TENANT_B).orElseThrow(),
                    wrappers.tenantRoot(TENANT_B).orElseThrow());
        }
    }

    @Test
    @DisplayName("locked down with its own workspace, a query cannot read another query's files in the shared working area")
    void lockdownRefusesAnotherQuerysWorkingFiles() throws Exception {
        QueryEngineOperator operator = operator(
                new SnapshotCache(mock(LakeClient.class), snapshotDir.toString(), 0L),
                WrapperOutputCache.disabled());

        try (QueryWorkspace mine = QueryWorkspace.create();
             QueryWorkspace theirs = QueryWorkspace.create()) {
            Path myDownload = mine.createFile("aran-snap-", ".csv");
            Path theirDownload = theirs.createFile("aran-snap-", ".csv");
            // A result another query handed off, waiting in the shared directory to be streamed.
            Path handedOff = theirs.handOff(theirs.createFile("aran-product-", ".csv"),
                    "aran-product-", ".csv");
            for (Path p : List.of(myDownload, theirDownload, handedOff)) {
                Files.writeString(p, "v\nROW\n");
            }
            try {
                try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                    try (Statement st = conn.createStatement()) {
                        DuckDbExecutor.lockDownFilesystem(st,
                                operator.queryFileRoots(TENANT_A, mine.dir()));
                    }
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery(
                                 "SELECT v FROM read_csv('" + myDownload + "')")) {
                        assertThat(rs.next()).isTrue();
                    }
                    for (Path other : List.of(theirDownload, handedOff)) {
                        try (Statement st = conn.createStatement()) {
                            assertThatThrownBy(() -> st.executeQuery(
                                    "SELECT v FROM read_csv('" + other + "')"))
                                    .as(other.toString())
                                    .hasMessageContaining("disabled by configuration");
                        }
                    }
                }
            } finally {
                Files.deleteIfExists(handedOff);
            }
        }
    }

    @Test
    @DisplayName("a workspace is gone once its query is over, and a handed-off result survives it")
    void workspaceIsRemovedAndHandOffSurvives() throws Exception {
        Path dir;
        Path result;
        try (QueryWorkspace workspace = QueryWorkspace.create()) {
            dir = workspace.dir();
            workspace.createFile("aran-snap-", ".json");
            Path staged = workspace.createFile("aran-product-", ".parquet");
            Files.writeString(staged, "rows");
            result = workspace.handOff(staged, "aran-product-", ".parquet");
        }
        try {
            assertThat(dir).doesNotExist();
            assertThat(result).hasContent("rows");
            assertThat(result.getParent()).isEqualTo(QueryTempDir.get());
        } finally {
            Files.deleteIfExists(result);
        }
    }

    @Test
    @DisplayName("locked down with tenant A's allowlist, the connection refuses tenant B's cached file")
    void lockdownRefusesAnotherTenantsCachedFile() throws Exception {
        SnapshotCache snapshots = new SnapshotCache(mock(LakeClient.class), snapshotDir.toString(),
                100_000_000L);
        WrapperOutputCache wrappers = new WrapperOutputCache(wrapperDir.toString(), 64L << 20);
        QueryEngineOperator operator = operator(snapshots, wrappers);

        Path mine = snapshots.tenantRoot(TENANT_A).orElseThrow().resolve("mine.csv");
        Path theirs = snapshots.tenantRoot(TENANT_B).orElseThrow().resolve("theirs.csv");
        Path theirWrapper = wrappers.tenantRoot(TENANT_B).orElseThrow().resolve("theirs.csv");
        Files.writeString(mine, "v\nMINE\n");
        Files.writeString(theirs, "v\nTHEIRS\n");
        Files.writeString(theirWrapper, "v\nTHEIRS\n");

        try (QueryWorkspace workspace = QueryWorkspace.create();
             Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                DuckDbExecutor.lockDownFilesystem(st,
                        operator.queryFileRoots(TENANT_A, workspace.dir()));
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT v FROM read_csv('" + mine + "')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("MINE");
            }
            for (Path other : List.of(theirs, theirWrapper)) {
                try (Statement st = conn.createStatement()) {
                    assertThatThrownBy(() -> st.executeQuery(
                            "SELECT v FROM read_csv('" + other + "')"))
                            .as(other.toString())
                            .hasMessageContaining("disabled by configuration");
                }
            }
        }
    }

    private static QueryEngineOperator operator(SnapshotCache snapshots, WrapperOutputCache wrappers) {
        return new QueryEngineOperator(mock(GraphStore.class), mock(MappingCatalogOperator.class),
                mock(LakeClient.class), new ObjectMapper(), mock(IntegrationModelRepository.class),
                mock(DatasetRepository.class), mock(DataRepositoryRepository.class), snapshots,
                mock(DataProductRepository.class), Optional.empty(), wrappers);
    }
}
