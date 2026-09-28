package edu.upc.dtak.aran.operators.queryengine.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The benchmark tier's output: {@code build/perf/report.json} and {@code build/perf/report.md}.
 *
 * <p>Both carry the environment they were produced in, and both say so at the top, because a
 * duration here describes one machine on one afternoon. The claims worth carrying between
 * machines are the <em>ratios</em> — cold against warm, pruned against unpruned, one scale
 * against the next — which is the same framing {@code s1-query-stress.spec.ts} settled on for
 * the browser suite.
 *
 * <p>Every raw sample is written out, not only the summary. A percentile that hides the one
 * outlier hides the interesting event.
 */
final class PerfReport {

    private PerfReport() {}

    /**
     * One cell of the grid: a workload at one setting of one dimension.
     *
     * @param outcome {@code "ok"}, or the throwable's class name when the cell did not finish.
     *                A benchmark whose finding is "this does not complete" has to be able to
     *                say so rather than taking the suite down with it.
     * @param note    why a cell stopped early — a budget hit, an escalation abandoned
     */
    record Entry(String dimension, String setting, String workload, String oql,
                 Map<String, Object> knobs, String outcome, String note,
                 List<PerfRun.Sample> samples) {}

    private static final List<String> PHASES = List.of(
            "parse", "plannerCtor", "lower", "optimize", "toSql", "prune", "prepare", "run",
            "placement", "total");

    private static long phase(PerfRun.Sample s, String name) {
        return switch (name) {
            case "parse"       -> s.parseNanos();
            case "plannerCtor" -> s.plannerCtorNanos();
            case "lower"       -> s.lowerNanos();
            case "optimize"    -> s.optimizeNanos();
            case "toSql"       -> s.toSqlNanos();
            case "prune"       -> s.pruneNanos();
            case "prepare"     -> s.prepareNanos();
            case "run"         -> s.runNanos();
            case "placement"   -> s.placementNanos();
            default            -> s.totalNanos();
        };
    }

    static Map<String, Object> environment() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        env.put("javaVersion", System.getProperty("java.version"));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("maxHeapMB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        env.put("duckdb", duckdbVersion());
        env.put("calcite", nullSafe(() ->
                org.apache.calcite.rel.RelNode.class.getPackage().getImplementationVersion()));
        env.put("gitSha", System.getenv().getOrDefault("GITHUB_SHA", "(not set)"));
        env.put("scale", System.getProperty("aran.perf.scale", "default"));
        // Generated Parquet is cached and never evicted, so a long run's footprint is worth
        // stating: it is the first thing to check when a large-scale run dies (V32).
        env.put("generatedDataMB", generatedDataMB());
        return env;
    }

    private static long generatedDataMB() {
        Path root = PerfData.root();
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(f -> {
                try {
                    return Files.size(f);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum() / (1024 * 1024);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static String duckdbVersion() {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version()")) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return "(unavailable)";
        }
    }

    private static String nullSafe(java.util.function.Supplier<String> s) {
        try {
            String v = s.get();
            return v == null ? "(unknown)" : v;
        } catch (RuntimeException e) {
            return "(unknown)";
        }
    }

    static void write(List<Entry> entries) throws IOException {
        Path dir = PerfRun.reportRoot();
        Files.createDirectories(dir);
        writeJson(dir.resolve("report.json"), entries);
        Files.writeString(dir.resolve("report.md"), markdown(entries));
    }

    private static void writeJson(Path target, List<Entry> entries) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("environment", environment());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dimension", e.dimension());
            row.put("setting", e.setting());
            row.put("workload", e.workload());
            row.put("oql", e.oql());
            row.put("knobs", e.knobs());
            row.put("outcome", e.outcome());
            row.put("note", e.note());
            row.put("sampleCount", e.samples().size());

            Map<String, Object> summary = new LinkedHashMap<>();
            for (String p : PHASES) {
                List<Long> values = e.samples().stream().map(s -> phase(s, p)).toList();
                if (values.stream().anyMatch(v -> v > 0)) {
                    summary.put(p, PerfRun.summarise(values));
                }
            }
            row.put("summaryNanos", summary);
            if (!e.samples().isEmpty()) {
                PerfRun.Sample first = e.samples().get(0);
                Map<String, Object> counters = new LinkedHashMap<>();
                counters.put("ddlStatements", first.ddlStatements());
                counters.put("datasetsResolved", first.datasetsResolved());
                counters.put("viewsNamedBySql", first.viewsNamedBySql());
                counters.put("sqlLength", first.sqlLength());
                counters.put("rows", first.rows());
                counters.put("columns", first.columns());
                counters.put("nodeCounts", first.nodeCounts());
                counters.put("compactShape", first.compactShape());
                // D3 step 0: what the planner spent, per rule — deterministic, so one sample
                // says it all. This is the evidence the phase split is decided on.
                counters.put("productions", first.planning().productions());
                counters.put("productionsByRule", first.planning().productionsByRule());
                counters.put("planning", first.planning().degradation().name()
                        + (first.planning().tripped() == null ? "" : " (" + first.planning().tripped() + ")"));
                row.put("counters", counters);
            }
            row.put("samplesNanos", e.samples().stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String p : PHASES) {
                    m.put(p, phase(s, p));
                }
                m.put("allocatedBytes", s.allocatedBytes());
                return m;
            }).toList());
            rows.add(row);
        }
        root.put("entries", rows);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(target, mapper.writeValueAsString(root));
    }

    private static String markdown(List<Entry> entries) {
        StringBuilder md = new StringBuilder();
        md.append("# Aran query-engine performance report\n\n");
        md.append("_Generated by `PerfBenchmarkTest`. **These numbers describe the machine below, ")
          .append("on one run.** They are not comparable with another machine's and nothing in ")
          .append("the build asserts them — the ratios (cold vs warm, pruned vs unpruned, one ")
          .append("scale against the next) are what the code predicts and what is worth reading._\n\n");

        md.append("## Environment\n\n| | |\n|---|---|\n");
        environment().forEach((k, v) -> md.append("| ").append(k).append(" | `").append(v).append("` |\n"));
        md.append('\n');

        Map<String, List<Entry>> byDimension = new LinkedHashMap<>();
        for (Entry e : entries) {
            byDimension.computeIfAbsent(e.dimension(), k -> new ArrayList<>()).add(e);
        }

        for (Map.Entry<String, List<Entry>> group : byDimension.entrySet()) {
            md.append("## ").append(group.getKey()).append("\n\n");
            md.append("| setting | workload | n | outcome | optimize p50 | placement p50 | prune p50 | prepare p50 | run p50 | total p50 | total p90 | rows | scans |\n");
            md.append("|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            for (Entry e : group.getValue()) {
                md.append("| ").append(e.setting())
                  .append(" | ").append(e.workload())
                  .append(" | ").append(e.samples().size())
                  .append(" | ").append(e.outcome())
                  .append(" | ").append(ms(e, "optimize", 0.50))
                  .append(" | ").append(ms(e, "placement", 0.50))
                  .append(" | ").append(ms(e, "prune", 0.50))
                  .append(" | ").append(ms(e, "prepare", 0.50))
                  .append(" | ").append(ms(e, "run", 0.50))
                  .append(" | ").append(ms(e, "total", 0.50))
                  .append(" | ").append(ms(e, "total", 0.90))
                  .append(" | ").append(counter(e, PerfRun.Sample::rows))
                  .append(" | ").append(e.samples().isEmpty() ? "—"
                          : String.valueOf(e.samples().get(0).nodeCounts().getOrDefault("SCAN", 0)))
                  .append(" |\n");
            }
            md.append('\n');
            for (Entry e : group.getValue()) {
                if (!e.note().isBlank()) {
                    md.append("- **").append(e.setting()).append("** — ").append(e.note()).append('\n');
                }
            }
            md.append('\n');
        }

        md.append("""
                ## What this does not measure

                Stated here rather than in a footnote, because a report that is silent about its
                boundary invites being read as one that has none.

                - **Not bytes across engine boundaries.** There are none: every snapshot-backed
                  query collapses to one DuckDB statement by construction, which is the standing
                  invariant the gate asserts. The nearest honest measure available today is
                  `datasetsResolved` — a count of the snapshots that *would* have been fetched,
                  since in production the resolver is what downloads one.
                - **The placement λs are not calibrated here.** The `placement-decision`
                  dimension prices the post-pass itself (what deciding costs), not the weights it
                  decides with; calibrating λ_live/λ_local needs a live source, which is
                  `PerfFederatedBenchmarkTest`'s job (docker-gated, separate report).
                - **No network, lake, object store, snapshot cache or lake client.** The resolver
                  hands DuckDB a local path.
                - **No HTTP, no Avatica frames, no JSON serialisation, no thousand-row page cap,
                  no paging.** Those live in `QueryEngineOperator` and above, off this path.
                - **No Spring, no tenancy, no SPARQL catalog build.** The catalog is built in
                  memory; in production it is cached per schema version anyway.
                - **No federation in THIS report.** `postgres_scan`/`mysql_scan` need a live
                  database and an extension download, and they also disable the filesystem
                  lockdown. The docker-gated `PerfFederatedBenchmarkTest` covers that tier
                  against a real Postgres and writes `build/perf/federated-report.md` — the
                  λ-calibration instrument (register A4/V57).
                - **In-process concurrency only** — N threads over N independent in-memory
                  DuckDB instances. That is production's shape (one executor per query) but not
                  production's contention.
                - **Not JMH.** No fork isolation, no blackholes, no steady-state detection. The
                  timed phases each produce a value that is written out, so nothing is elided,
                  but a JMH run would be steadier.

                For the layers above this one, see `aran-web/e2e/specs/s1-query-stress.spec.ts`,
                which measures the snapshot cache, HTTP and browser path against a live stack.
                """);
        return md.toString();
    }

    private static String ms(Entry e, String phaseName, double p) {
        if (e.samples().isEmpty()) {
            return "—";
        }
        List<Long> values = e.samples().stream().map(s -> phase(s, phaseName)).toList();
        if (values.stream().allMatch(v -> v == 0)) {
            return "—";
        }
        return String.format("%.1f", PerfRun.percentile(values, p) / 1_000_000.0);
    }

    private static String counter(Entry e, Function<PerfRun.Sample, Integer> f) {
        return e.samples().isEmpty() ? "—" : String.valueOf(f.apply(e.samples().get(0)));
    }
}
