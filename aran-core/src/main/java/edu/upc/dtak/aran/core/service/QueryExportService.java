package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ad-hoc export of an OQL result, uncapped.
 *
 * <p>The workbench caps a result at 1,000 rows, which is right for a JSON payload rendered into an
 * HTML table and wrong as the only way to get data out. Before this, there was no other way: the
 * data-product path materialises without a cap but then only ever serves a 100-row preview, so a
 * query returning 50,000 rows was unreachable past the first thousand by any route.
 *
 * <p>This runs the <em>same</em> {@code materialize-oql} operation a data product runs — same
 * planner, same constraint enforcement, no row cap — and hands back the file it wrote. It does not
 * create a product: an export is a one-off answer to "give me this result", not a named, pinned,
 * versioned artefact, and conflating the two would litter the product list.
 *
 * <p>The caller owns the returned file and must delete it. {@link Export#delete()} is provided so
 * a controller can stream it and clean up in a {@code finally} without importing NIO.
 */
@Service
@NullMarked
public class QueryExportService {

    /**
     * A materialised export.
     *
     * @param path      a server-local temp file the caller must delete
     * @param format    {@code PARQUET} or {@code CSV}
     * @param rowCount  rows written — the true total, not a capped page
     */
    public record Export(Path path, String format, long rowCount) {
        /** The name to offer the browser. */
        public String filename() {
            return "aran-export-" + java.time.LocalDate.now() + "."
                    + format.toLowerCase(Locale.ROOT);
        }

        public String contentType() {
            return "CSV".equals(format) ? "text/csv" : "application/vnd.apache.parquet";
        }

        public void delete() {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignore) {
                // Best effort: a leftover temp file costs disk, never correctness.
            }
        }
    }

    private final OperatorOrchestrator orchestrator;
    private final UsageService usage;
    private final LicenseService licenses;

    public QueryExportService(OperatorOrchestrator orchestrator, UsageService usage,
                              LicenseService licenses) {
        this.orchestrator = orchestrator;
        this.usage = usage;
        this.licenses = licenses;
    }

    /**
     * Execute {@code oql} with no row cap and write the whole result to a temp file.
     *
     * @param format {@code CSV} or {@code PARQUET}; anything else is rejected
     * @throws IllegalArgumentException on an empty query, an unknown format, or a query the engine
     *                                  refuses — the message is the engine's own, so a bad column
     *                                  name reads the same here as it does in the workbench
     */
    public Export export(TenantContext tenant, @Nullable UUID actor, String oql, String format) {
        if (oql == null || oql.isBlank()) {
            throw new IllegalArgumentException("OQL query is empty.");
        }
        String normalised = format == null || format.isBlank()
                ? "CSV" : format.trim().toUpperCase(Locale.ROOT);
        if (!normalised.equals("CSV") && !normalised.equals("PARQUET")) {
            throw new IllegalArgumentException("format must be CSV or PARQUET, got: " + format);
        }

        // The uncapped read path, so the one that runs into an egress ceiling first. Measured
        // over completed exports, so the request that crosses the line still finishes — a
        // truncated parquet a client cannot distinguish from a whole one is worse than one byte
        // over budget.
        licenses.requireWithinEgressLimit(UUID.fromString(tenant.value()));

        long started = System.currentTimeMillis();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("oql", oql);
        params.put("format", normalised);
        OperatorResult result = orchestrator.execute("query-engine",
                new OperatorRequest(tenant.value(), actor, "materialize-oql", params));

        if (!result.success()) {
            throw new IllegalArgumentException(result.errorMessage() != null
                    ? result.errorMessage() : "export failed");
        }
        String path = result.data().get("path");
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("materialize-oql returned no file path");
        }
        long rowCount = Long.parseLong(result.data().getOrDefault("rowCount", "0"));

        // Attributed to the caller, which is the whole point of recording it: this is the
        // uncapped path, so it is where a runaway export shows up.
        usage.record(UUID.fromString(tenant.value()), actor,
                edu.upc.dtak.aran.core.domain.UsageKind.EXPORT,
                System.currentTimeMillis() - started, rowCount, sizeOrNull(Path.of(path)));

        return new Export(Path.of(path), normalised, rowCount);
    }

    /**
     * The file's size, or null if it cannot be read.
     *
     * <p>Null rather than zero: "we did not measure it" and "nothing was transferred" are
     * different facts, and a stat that averages them is worse than one with a gap.
     */
    private static @Nullable Long sizeOrNull(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return null;
        }
    }
}
