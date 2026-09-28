package edu.upc.dtak.aran.core.domain.drift;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.source.FederatedScan;
import edu.upc.dtak.aran.core.source.RelationalEngines;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The DuckDB live read for a federated table.
 *
 * <p>Extracted so the query engine and the drift probe cannot disagree. Two copies of
 * credential-string assembly is exactly the hazard {@code Names} warns about for view names: a
 * drifted copy here would not produce a compile error, it would produce a probe that connects
 * somewhere subtly different from where queries go — and then reports no drift, forever.
 *
 * <p>Now a facade. The per-engine knowledge moved to {@link
 * edu.upc.dtak.aran.core.source.RelationalEngine} so that adding an engine is one class rather
 * than edits to the switches that used to live here, the bulk-extract gate in
 * {@code DatasetService}, and the quoting rule in {@code DataRepositoryService}. The signatures
 * stay because two operators call them statically and the indirection is worth nothing to them.
 */
@NullMarked
public final class FederatedScanExpression {

    private FederatedScanExpression() {}

    /** The DuckDB extension a scan needs, or null when the engine is not federatable. */
    public static @Nullable String extensionFor(String engine) {
        return RelationalEngines.defaults().find(engine)
                .flatMap(e -> e.duckDbExtension())
                .orElse(null);
    }

    /**
     * The live read for one table — an attached Postgres or MySQL catalog's table plus the setup
     * that attaches it — or null when the repository's engine has
     * no federated scanner. A caller that binds it must run {@link FederatedScan#setupSql()}
     * before the expression: for an attached catalog the expression alone names nothing.
     *
     * @param table the remote table name — for Aran datasets this is {@code dataset.name()}
     */
    public static @Nullable FederatedScan forRepository(RelationalDatabaseRepository repo,
                                                        String table) {
        return RelationalEngines.defaults().find(repo.engine())
                .flatMap(e -> e.federatedScan(repo, table))
                .orElse(null);
    }
}
