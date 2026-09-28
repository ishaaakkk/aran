package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The scan expression + connection-setup statements that bind one FEDERATED dataset for
 * <b>profiling</b> — the same binding a query gets, composed from the same single authorities the
 * query engine's snapshot resolver uses ({@link FederatedScanExpression}/{@link FederatedScan},
 * {@link IcebergScanExpression}, {@link DuckDbS3Config}, {@link DuckDbHttpSecret}), so profiling
 * and execution cannot read different tables or authenticate differently.
 *
 * <p>Exists because federated profiling is the design ruling's "suite of SQL over the
 * wrapper-as-view": freyja executes the profiling queries on its own DuckDB over this binding, the
 * source does the scanning and filtering, and only aggregates cross the wire — nothing is ever
 * staged to the lake. The query engine cannot build this for the profiling operator (an operator
 * result is a loggable, HTTP-returnable surface, and this carrier holds credentials), so the
 * profiling operator builds it here, in core, where both sides' authorities already live.
 *
 * <h2>Credential hygiene</h2>
 *
 * <p>{@link #setupSql()} can carry keys and passwords ({@code CREATE SECRET}); the expression of
 * every binding built today carries none. The one legitimate destination is
 * the freyja profile request's {@code federated_scan} field — the same trust plane that already
 * receives a DSN via {@code LakeClient.startImport}. Never log either, never put them in an
 * exception or an {@code OperatorResult}; {@link #toString()} redacts to counts for the same
 * reason {@code ConnectionSetup}'s does.
 */
@NullMarked
public final class FederatedProfilingBinding {

    private final String expression;
    private final List<String> setupSql;

    private FederatedProfilingBinding(String expression, List<String> setupSql) {
        this.expression = expression;
        this.setupSql = List.copyOf(setupSql);
    }

    /** The scan expression — may carry a DSN; see the class-level hygiene note. */
    public String expression() {
        return expression;
    }

    /**
     * Ordered INSTALL/LOAD/CREATE SECRET statements, plus an attached catalog's SET and ATTACH — may
     * carry keys; same hygiene note.
     */
    public List<String> setupSql() {
        return setupSql;
    }

    /**
     * The binding for one dataset, or {@code null} when this repository kind has no live binding
     * to profile through (the caller then falls back to its snapshot path, or skips honestly):
     *
     * <ul>
     *   <li><b>Relational</b> — the {@link FederatedScan} the drift probe and query resolver share:
     *       an attached Postgres or MySQL catalog's table plus the settings, secret and
     *       {@code ATTACH} that bind it. {@code datasetName} is the remote
     *       relation, the same invariant those callers pin.</li>
     *   <li><b>Iceberg</b> — {@code iceberg_scan} on the metadata location the catalog reports
     *       <em>now</em> (fetched per call, exactly like the query resolver: a commit moves it),
     *       plus httpfs/iceberg extensions and the data-plane secret ({@code s3://} bucket-scoped,
     *       or an HTTP bearer for http(s) locations). {@code datasetName} IS the
     *       {@code namespace.table} identifier — the liveDescriptor invariant. Throws when the
     *       catalog cannot be reached; a caller that got here believed the dataset was live, so a
     *       silent null would misreport an outage as "kind not supported".</li>
     *   <li><b>Object storage</b> — {@code null} for now: its live read expression needs the
     *       object key from the schema graph ({@code nx:iterator}), which this builder deliberately
     *       does not read, and no production path can yet enroll a federated object-storage class
     *       anyway (V35: {@code liveDescriptor} has no object-storage branch).</li>
     * </ul>
     */
    public static @Nullable FederatedProfilingBinding forDataset(
            @Nullable DataRepository repository, String datasetName,
            @Nullable IcebergCatalogClient icebergCatalog) {
        if (repository instanceof RelationalDatabaseRepository relational) {
            FederatedScan scan = FederatedScanExpression.forRepository(relational, datasetName);
            if (scan == null) {
                return null;
            }
            return new FederatedProfilingBinding(scan.expression(), scan.setupSql());
        }
        if (repository instanceof IcebergRestRepository iceberg) {
            if (icebergCatalog == null) {
                throw new IllegalStateException(
                        "this build has no Iceberg catalog client on its classpath");
            }
            String metadataLocation =
                    icebergCatalog.loadTable(iceberg, datasetName).metadataLocation();
            // Same extension set as DuckDbExecutor.ensureFederationExtensions' iceberg branch:
            // iceberg_scan reads metadata/manifests/files itself over httpfs whatever the scheme.
            List<String> setup = new ArrayList<>(
                    List.of("INSTALL httpfs", "LOAD httpfs", "INSTALL iceberg", "LOAD iceberg"));
            String scope = IcebergScanExpression.s3Scope(metadataLocation);
            if (scope != null) {
                setup.add(new DuckDbS3Config(iceberg.provider(), iceberg.endpoint(),
                        iceberg.accessKey(), iceberg.secretKey(), iceberg.region())
                        .createSecretSql("aran_ice_profile", scope));
            } else if (metadataLocation.startsWith("http")
                    && iceberg.token() != null && !iceberg.token().isBlank()) {
                // A catalog that serves its files over HTTP (another Aran's produce surface does)
                // authenticates file reads with the same bearer as the catalog itself — the same
                // rule as the query resolver's.
                setup.add(DuckDbHttpSecret.createSecretSql(
                        "aran_ice_profile_http", iceberg.token(), null));
            }
            return new FederatedProfilingBinding(
                    IcebergScanExpression.forMetadataLocation(metadataLocation), setup);
        }
        return null;
    }

    /** Redacts everything but counts. See the class-level credential-hygiene note. */
    @Override
    public String toString() {
        return "FederatedProfilingBinding[setupStatements=" + setupSql.size() + ']';
    }
}
