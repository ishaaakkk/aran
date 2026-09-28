package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code restrictFilesystemTo} is the layer under the SQL screen: it decides what a query can
 * reach even when the screen has already let the statement through.
 *
 * <p>The tests are written against a statement the screen would refuse, run through
 * {@link DuckDbExecutor} directly. That is deliberate — the point is what happens when the
 * screen is <em>wrong</em>, and the only honest way to test that is to bypass it.
 */
class DuckDbLockdownTest {

    /** A one-class catalog whose single source is {@code file}. */
    private static MappingCatalog catalogOver(Path file) {
        MappingCatalog.SourceView view = new MappingCatalog.SourceView(
                "ds", "SELECT * FROM ${SNAPSHOT}", Map.of("col", "col"));
        return new MappingCatalog(
                Map.of("urn:C", new MappingCatalog.IntegratedClass("urn:C", List.of(view))),
                List.of(), List.of(), "v1");
    }

    /** A one-class catalog over dataset {@code ds}, read through a caller-supplied wrapper SQL. */
    private static MappingCatalog catalogWithWrapper(String wrapperSql) {
        MappingCatalog.SourceView view = new MappingCatalog.SourceView(
                "ds", wrapperSql, Map.of("col", "col"));
        return new MappingCatalog(
                Map.of("urn:C", new MappingCatalog.IntegratedClass("urn:C", List.of(view))),
                List.of(), List.of(), "v1");
    }

    private static DuckDbExecutor.SnapshotResolver resolving(Path file) {
        return datasetId -> "read_csv_auto('" + file.toAbsolutePath() + "')";
    }

    @Test
    @DisplayName("a wrapper's s3:// string literal cannot disable the lockdown (sec-audit F-QUERY-2)")
    void wrapperLiteralCannotSkipLockdown() throws Exception {
        // The federation signal that skips the lockdown must come from the RESOLVER's src_ read
        // expression, not from user wrapper text. Here the source is a local CSV and the wrapper
        // merely PROJECTS an s3:// literal (data) — no external access happens — so the lockdown
        // must still apply. Before the fix, the literal 's3://' spliced into the w_ view DDL
        // tripped the federation substring test and dropped the second layer.
        Path allowed = Files.createTempDirectory("aran-lockdown-fedlit-");
        Path snapshot = allowed.resolve("snapshot.csv");
        Files.writeString(snapshot, "col\nvisible\n");

        Path secret = Files.createTempFile("aran-lockdown-fedlit-secret-", ".csv");
        Files.writeString(secret, "col\nSHOULD_NOT_BE_READABLE\n");

        MappingCatalog catalog = catalogWithWrapper("SELECT col, 's3://evil/x' AS note FROM ${SNAPSHOT}");
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolving(snapshot))) {
            exec.restrictFilesystemTo(List.of(allowed)).prepare();

            assertThat(exec.run("SELECT * FROM int_C").rows()).hasSize(1);
            assertThatThrownBy(() -> exec.run(
                    "SELECT * FROM '" + secret.toAbsolutePath() + "'"))
                    .hasMessageContaining("disabled by configuration");
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    @DisplayName("a file outside the allowed directories is unreadable, screen or no screen")
    void refusesReadsOutsideTheAllowlist() throws Exception {
        Path allowed = Files.createTempDirectory("aran-lockdown-allowed-");
        Path snapshot = allowed.resolve("snapshot.csv");
        Files.writeString(snapshot, "col\nvisible\n");

        Path secret = Files.createTempFile("aran-lockdown-secret-", ".csv");
        Files.writeString(secret, "col\nSHOULD_NOT_BE_READABLE\n");

        try (DuckDbExecutor exec = new DuckDbExecutor(catalogOver(snapshot), resolving(snapshot))) {
            exec.restrictFilesystemTo(List.of(allowed)).prepare();

            // The query the catalog exists to serve still works.
            assertThat(exec.run("SELECT * FROM int_C").rows()).hasSize(1);

            // Both shapes the screen refuses — named reader and replacement scan — now fail at
            // the engine as well. This is the belt to the screen's braces.
            assertThatThrownBy(() -> exec.run(
                    "SELECT * FROM read_csv_auto('" + secret.toAbsolutePath() + "')"))
                    .hasMessageContaining("disabled by configuration");
            assertThatThrownBy(() -> exec.run(
                    "SELECT * FROM '" + secret.toAbsolutePath() + "'"))
                    .hasMessageContaining("disabled by configuration");
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    @DisplayName("the locked configuration cannot be reopened by the statement being run")
    void refusesToUnlock() throws Exception {
        Path allowed = Files.createTempDirectory("aran-lockdown-relock-");
        Path snapshot = allowed.resolve("snapshot.csv");
        Files.writeString(snapshot, "col\nvisible\n");

        try (DuckDbExecutor exec = new DuckDbExecutor(catalogOver(snapshot), resolving(snapshot))) {
            exec.restrictFilesystemTo(List.of(allowed)).prepare();

            assertThatThrownBy(() -> exec.execute("SET enable_external_access=true"))
                    .hasMessageContaining("locked");
            assertThatThrownBy(() -> exec.execute("SET allowed_directories=['/']"))
                    .hasMessageContaining("locked");
        }
    }

    @Test
    @DisplayName("COPY still writes into an allowed directory — the materialiser depends on it")
    void allowsCopyIntoAllowedDirectory() throws Exception {
        Path allowed = Files.createTempDirectory("aran-lockdown-copy-");
        Path snapshot = allowed.resolve("snapshot.csv");
        Files.writeString(snapshot, "col\nvisible\n");
        Path target = allowed.resolve("out.parquet");

        try (DuckDbExecutor exec = new DuckDbExecutor(catalogOver(snapshot), resolving(snapshot))) {
            exec.restrictFilesystemTo(List.of(allowed)).prepare();
            exec.execute("COPY (SELECT * FROM int_C) TO '" + target + "' (FORMAT PARQUET)");
        }
        assertThat(Files.exists(target)).isTrue();
    }

    @Test
    @DisplayName("without an allowlist nothing is restricted — tests and explain keep working")
    void noAllowlistMeansNoLockdown() throws Exception {
        Path allowed = Files.createTempDirectory("aran-lockdown-off-");
        Path snapshot = allowed.resolve("snapshot.csv");
        Files.writeString(snapshot, "col\nvisible\n");

        Path elsewhere = Files.createTempFile("aran-lockdown-elsewhere-", ".csv");
        Files.writeString(elsewhere, "col\nreadable\n");

        try (DuckDbExecutor exec = new DuckDbExecutor(catalogOver(snapshot), resolving(snapshot))) {
            exec.prepare();
            assertThat(exec.run("SELECT * FROM '" + elsewhere.toAbsolutePath() + "'").rows())
                    .hasSize(1);
        } finally {
            Files.deleteIfExists(elsewhere);
        }
    }

    @Test
    @DisplayName("a federated query is left unlocked — the setting that confines it also cuts it off")
    void federatedQueriesSkipTheLockdown() {
        // enable_external_access=false is what confines the filesystem AND what postgres_scan
        // needs to reach the remote database. One setting, both jobs, so a federated query
        // cannot have the second layer. lockDown and ensureFederationExtensions must therefore
        // read the DDL the same way; this pins the predicate they share.
        assertThat(DuckDbExecutor.isFederated(List.of(
                "CREATE OR REPLACE VIEW src_x AS SELECT * FROM postgres_scan('host=db','public','t')")))
                .isTrue();
        // An attached MySQL catalog's table: no function name, so its reserved alias is the signal.
        assertThat(DuckDbExecutor.isFederated(List.of("CREATE OR REPLACE VIEW src_x AS SELECT * FROM "
                + "aran_attach_0f1e2d3c4b5a69788796a5b4c3d2e1f0.\"shop\".\"orders\"")))
                .isTrue();
        assertThat(DuckDbExecutor.isFederated(List.of(
                "CREATE OR REPLACE VIEW src_x AS SELECT * FROM read_parquet('/cache/a.parquet')")))
                .isFalse();
    }

    @Test
    @DisplayName("an s3:// read is federated too — httpfs reaches the network, so it cannot be locked down")
    void objectStorageReadsAreFederated() {
        // The object-storage resolver emits read_parquet('s3://bucket/key') (or read_csv_auto /
        // read_json_auto). httpfs is external access, exactly like postgres_scan — the same
        // enable_external_access=false that would confine the filesystem also cuts off the s3 read,
        // so lockDown must recognise it and skip. A local snapshot path never contains s3://.
        assertThat(DuckDbExecutor.isFederated(List.of(
                "CREATE OR REPLACE VIEW src_x AS SELECT * FROM read_parquet('s3://bucket/data.parquet')")))
                .isTrue();
        assertThat(DuckDbExecutor.isFederated(List.of(
                "CREATE OR REPLACE VIEW src_x AS SELECT * FROM read_csv_auto('s3://bucket/data.csv')")))
                .isTrue();
    }
}
