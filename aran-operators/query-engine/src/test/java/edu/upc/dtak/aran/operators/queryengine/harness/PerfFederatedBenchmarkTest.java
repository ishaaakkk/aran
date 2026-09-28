package edu.upc.dtak.aran.operators.queryengine.harness;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression;
import edu.upc.dtak.aran.core.source.FederatedScan;
import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The federated execution tier, measured — and the λ-calibration instrument the placement design
 * names as A4.
 *
 * <pre>
 * make perf-federated
 * ./gradlew :aran-operators:query-engine:test --tests '*PerfFederatedBenchmarkTest' -Daran.perf=true
 * </pre>
 *
 * <p><b>What it does:</b> generates one dataset's rows once, serves them both ways — the ordinary
 * snapshot resolver (local Parquet) and a live attached-catalog read against a Testcontainers
 * Postgres holding the same rows — and runs the same OQL through {@code PerfRun.execute} (the
 * production wiring: prune, prepare, run) on each side, at a full scan and at a selective
 * predicate. The ratio of the two run times at the full scan is a measured stand-in for
 * λ_live/λ_local (both sides move every row, so per-row weights are directly comparable); the
 * selective workload shows what predicate pushdown into the scanner buys, which is exactly the
 * effect {@code PlacementDecider} prices with {@code effRows}. Results go to
 * {@code build/perf/federated-report.md} — <b>nothing here asserts a duration</b>, same rule as
 * {@link PerfBenchmarkTest}.
 *
 * <p><b>Gating, twice over:</b> {@code @Tag("docker")} (a live database; dropped by
 * {@code -PexcludeTags=docker}, which validate.sh --offline passes) and
 * {@code -Daran.perf=true} (a benchmark has no business inside the ordinary gate). The parse
 * check below runs on every build so the tier cannot rot unnoticed — the same standing answer
 * {@code gridIsWellFormed} gives for the main grid. Beyond the daemon, the run needs DuckDB to
 * INSTALL its postgres extension, so the machine must reach the extension repository.
 *
 * <p><b>Honest limits:</b> the container shares the benchmark's machine, so the wire is a
 * loopback — a λ calibrated here is a floor, not a WAN number, and the report says so. Postgres
 * only, exactly like the harvest (V36's scope). The loaded table stores every non-measure column
 * as TEXT, which matches what the snapshot side's Parquet holds for them.
 */
@Tag("docker")
class PerfFederatedBenchmarkTest {

    private static final long ROWS = Long.getLong("aran.perf.federated.rows", 100_000L);
    private static final int WARMUP = 1;
    private static final int ITERATIONS = 5;

    private static final String DATASET = "c0s0";

    private static final String Q_FULL = "SELECT C0.key FROM C0";
    /** amount is {@code i % 997}, so {@code > 990} keeps 6/997 ≈ 0.6% of the rows. */
    private static final String Q_SELECTIVE = "SELECT C0.key FROM C0 WHERE C0.amount > 990";

    /**
     * Runs on every build (no docker, no flag): the tier's queries parse and the scan-expression
     * authority still produces a live read for a Postgres repository — its attached catalog's
     * table, plus the setup that attaches it. Without this, an opt-in tier that only executes on
     * demand could stop compiling its inputs unnoticed.
     */
    @Test
    @DisplayName("the federated tier's queries parse and its scan expression still builds")
    void tierIsWellFormed() {
        assertThatCode(() -> OqlParser.parse(Q_FULL)).doesNotThrowAnyException();
        assertThatCode(() -> OqlParser.parse(Q_SELECTIVE)).doesNotThrowAnyException();
        FederatedScan scan = FederatedScanExpression.forRepository(
                repository("localhost", 5432, "db", "u", "p"), DATASET);
        assertThat(scan).isNotNull();
        assertThat(scan.expression()).startsWith(FederatedScan.ATTACH_ALIAS_PREFIX).contains(DATASET);
        assertThat(scan.setupSql()).last().asString().startsWith("ATTACH ");
    }

    @EnabledIfSystemProperty(named = "aran.perf", matches = "true")
    @Test
    @DisplayName("snapshot vs live Postgres — the λ-calibration measurement (writes federated-report.md)")
    void snapshotVersusLive() throws Exception {
        try (PostgreSQLContainer<?> pg =
                     new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))) {
            pg.start();

            // One class, one source — the placement decision's own shape (a dual-access leaf).
            var knobs = PerfCatalogs.Knobs.benchmark()
                    .withClasses(1).withSources(1).withRows(ROWS);
            PerfCatalogs.Built snapshot = PerfCatalogs.star(knobs);
            Path parquet = PerfData.parquetFor(snapshot.specsByDataset().get(DATASET));
            loadIntoPostgres(pg, parquet);

            RelationalDatabaseRepository repo = repository(
                    pg.getHost(), pg.getMappedPort(5432), pg.getDatabaseName(),
                    pg.getUsername(), pg.getPassword());
            FederatedScan scan = FederatedScanExpression.forRepository(repo, DATASET);
            assertThat(scan).isNotNull();
            List<String> asked = new CopyOnWriteArrayList<>();
            DuckDbExecutor.SnapshotResolver liveResolver = ds -> {
                asked.add(ds);
                if (!DATASET.equals(ds)) {
                    throw new IllegalArgumentException("Unknown dataset: " + ds);
                }
                return scan.expression();
            };
            PerfCatalogs.Built live = new PerfCatalogs.Built(snapshot.catalog(),
                    snapshot.enforced(), liveResolver, asked, snapshot.specsByDataset(),
                    knobs, snapshot.expectedUnionRows(), snapshot.expectedGroups(),
                    snapshot.expectedCastableRows(), scan.setupSql());

            Map<String, List<PerfRun.Sample>> samples = new LinkedHashMap<>();
            for (var workload : List.of(Map.entry("full-scan", Q_FULL),
                                        Map.entry("selective", Q_SELECTIVE))) {
                for (var side : List.of(Map.entry("snapshot", snapshot),
                                        Map.entry("live", live))) {
                    List<PerfRun.Sample> cell = new ArrayList<>();
                    for (int i = 0; i < WARMUP; i++) {
                        PerfRun.execute(side.getValue(), workload.getKey(), workload.getValue(),
                                true, false);
                    }
                    for (int i = 0; i < ITERATIONS; i++) {
                        cell.add(PerfRun.execute(side.getValue(), workload.getKey(),
                                workload.getValue(), true, false));
                    }
                    samples.put(workload.getKey() + "/" + side.getKey(), cell);
                }
            }

            // Both sides must have answered with the same rows — a benchmark comparing a right
            // answer against a wrong one calibrates nothing.
            for (String workload : List.of("full-scan", "selective")) {
                assertThat(rowsOf(samples, workload + "/live"))
                        .as("%s: live and snapshot answered differently", workload)
                        .isEqualTo(rowsOf(samples, workload + "/snapshot"));
            }

            writeReport(samples);
        }
    }

    private static int rowsOf(Map<String, List<PerfRun.Sample>> samples, String key) {
        return samples.get(key).get(0).rows();
    }

    private static RelationalDatabaseRepository repository(String host, int port, String db,
                                                           String user, String password) {
        return new RelationalDatabaseRepository(new UUID(0L, 1L), new UUID(0L, 2L),
                new UUID(0L, 3L), "perf", "", "POSTGRESQL", host, port, db, user, password,
                "public", true);
    }

    /**
     * Loads the generated Parquet's rows into the container, one column type mapped from what
     * DuckDB read: integral → BIGINT, floating → DOUBLE PRECISION, everything else TEXT.
     */
    private static void loadIntoPostgres(PostgreSQLContainer<?> pg, Path parquet) throws Exception {
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
             Statement ds = duck.createStatement();
             ResultSet rs = ds.executeQuery(
                     "SELECT * FROM read_parquet('" + parquet.toAbsolutePath() + "')");
             Connection pgc = DriverManager.getConnection(
                     pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {

            ResultSetMetaData md = rs.getMetaData();
            StringBuilder create = new StringBuilder("CREATE TABLE " + DATASET + " (");
            StringBuilder insert = new StringBuilder("INSERT INTO " + DATASET + " VALUES (");
            for (int c = 1; c <= md.getColumnCount(); c++) {
                String type = switch (md.getColumnType(c)) {
                    case java.sql.Types.TINYINT, java.sql.Types.SMALLINT,
                         java.sql.Types.INTEGER, java.sql.Types.BIGINT -> "BIGINT";
                    case java.sql.Types.FLOAT, java.sql.Types.REAL,
                         java.sql.Types.DOUBLE -> "DOUBLE PRECISION";
                    default -> "TEXT";
                };
                create.append(c > 1 ? ", " : "").append('"')
                        .append(md.getColumnName(c)).append("\" ").append(type);
                insert.append(c > 1 ? ", ?" : "?");
            }
            try (Statement st = pgc.createStatement()) {
                st.execute(create.append(')').toString());
            }
            pgc.setAutoCommit(false);
            try (PreparedStatement ps = pgc.prepareStatement(insert.append(')').toString())) {
                int pending = 0;
                while (rs.next()) {
                    for (int c = 1; c <= md.getColumnCount(); c++) {
                        ps.setObject(c, rs.getObject(c));
                    }
                    ps.addBatch();
                    if (++pending == 5_000) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    ps.executeBatch();
                }
            }
            pgc.commit();
        }
    }

    private static void writeReport(Map<String, List<PerfRun.Sample>> samples) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# Aran federated execution report (λ calibration — A4)\n\n");
        md.append("_Generated by `PerfFederatedBenchmarkTest` over a Testcontainers Postgres on ")
          .append("the SAME machine: the wire is a loopback, so the live/local ratio here is a ")
          .append("**floor** for λ_live/λ_local — a WAN source only widens it. Nothing asserts ")
          .append("these numbers._\n\n");
        md.append("Rows loaded: ").append(ROWS).append("\n\n");
        md.append("| workload | side | n | run p50 (ms) | run p90 (ms) | prepare p50 (ms) | rows returned |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|\n");
        for (Map.Entry<String, List<PerfRun.Sample>> e : samples.entrySet()) {
            List<Long> runs = e.getValue().stream().map(PerfRun.Sample::runNanos).toList();
            List<Long> preps = e.getValue().stream().map(PerfRun.Sample::prepareNanos).toList();
            String[] parts = e.getKey().split("/");
            md.append("| ").append(parts[0]).append(" | ").append(parts[1])
              .append(" | ").append(e.getValue().size())
              .append(" | ").append(String.format("%.1f", PerfRun.percentile(runs, 0.50) / 1e6))
              .append(" | ").append(String.format("%.1f", PerfRun.percentile(runs, 0.90) / 1e6))
              .append(" | ").append(String.format("%.1f", PerfRun.percentile(preps, 0.50) / 1e6))
              .append(" | ").append(e.getValue().get(0).rows())
              .append(" |\n");
        }
        double liveFull = PerfRun.percentile(
                samples.get("full-scan/live").stream().map(PerfRun.Sample::runNanos).toList(), 0.50);
        double snapFull = PerfRun.percentile(
                samples.get("full-scan/snapshot").stream().map(PerfRun.Sample::runNanos).toList(), 0.50);
        md.append("\nAt the full scan both sides move every row, so per-row weights compare ")
          .append("directly: **suggested λ_live with λ_local = 1 is ")
          .append(String.format("%.1f", snapFull == 0 ? 0 : liveFull / snapFull))
          .append("** on this machine (shipped default: 25 — `aran.query.placement.live-lambda`).\n");
        md.append("The selective workload shows what filter pushdown into the Postgres scanner buys — ")
          .append("the effect `PlacementDecider` prices as `effRows`.\n");

        Path dir = PerfRun.reportRoot();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("federated-report.md"), md.toString());
        System.out.println("[perf] federated report: "
                + dir.resolve("federated-report.md").toAbsolutePath());
    }
}
