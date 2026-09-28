package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Which {@link AccessMode} a dataset is actually served through.
 *
 * <h2>Why this is a function and not a field</h2>
 *
 * <p>A repository asking to be federated is a <em>request</em>, not a fact. It is only granted if
 * the engine has a scanner: {@code FederatedScanExpression} returns one for PostgreSQL, MySQL and
 * MariaDB and {@code null} for everything else. Before this class the request and the grant were
 * decided in the same expression inside the query engine's resolver, so nothing else could ask
 * the question — not the registration form, not the drift probe, not a capability endpoint.
 *
 * <p>Resolving it in one place also makes an existing sharp edge visible rather than latent. A
 * repository marked federated on an engine with no scanner resolves to {@link
 * AccessMode#MATERIALIZED}, and if nothing ever materialised it the query fails with "No snapshot
 * recorded for dataset" — a message about a missing snapshot for a source the user believed was
 * live. That behaviour is preserved here deliberately, because changing it belongs with the
 * capability work that can report it properly, not with a refactor.
 *
 * <h2>Where the dataset override goes</h2>
 *
 * <p>Access is a property of how a dataset is <em>used</em>, not of the connection: one database
 * can hold a fact table worth federating and a dimension table worth copying. The agreed model is
 * a repository default with a per-dataset override. That override lands as one extra check at the
 * top of {@link #resolve}, and nothing else in the system has to learn about it — which is the
 * reason for introducing this function before the column exists.
 */
@NullMarked
public final class SourceAccess {

    private SourceAccess() {
    }

    /**
     * The mode this dataset is served through, with no per-dataset copy known — the repository
     * default. Equivalent to {@link #resolve(DataRepository, String, boolean)} with {@code false}:
     * a relational source federates if asked and able, and an object-storage source is read live
     * over httpfs.
     */
    public static AccessMode resolve(@Nullable DataRepository repository, String datasetName) {
        return resolve(repository, datasetName, false);
    }

    /**
     * The mode this dataset is served through, given whether this <em>particular</em> dataset has
     * been copied into the lake.
     *
     * <p>This is the per-dataset override the class header anticipates. Access is a property of how a
     * dataset is <em>used</em>, not of the connection: one object store can hold an object worth
     * reading live and one worth copying, exactly as a relational database can hold a fact table
     * worth federating and a dimension table worth copying. So the repository supplies a default and
     * the dataset overrides it.
     *
     * <ul>
     *   <li><b>Relational</b> keys off the repository's {@code federated} flag (a request granted
     *       only when the engine has a scanner); {@code datasetHasLakeSnapshot} does not enter into
     *       it, because a federated relational repository never takes a copy.</li>
     *   <li><b>Object storage</b> is served live over httpfs by default
     *       ({@code read_parquet('s3://…')}); the override is {@code datasetHasLakeSnapshot}. A
     *       dataset that has been copied into the lake reads from that copy (MATERIALIZED); one that
     *       has not reads in place (FEDERATED). Object storage carries no repository-level
     *       {@code federated} flag — and adding one would need a registration-form field, which the
     *       SPI acceptance test forbids — so the per-dataset copy <em>is</em> the signal.</li>
     *   <li><b>Everything else</b> is MATERIALIZED.</li>
     * </ul>
     *
     * @param repository the dataset's repository; {@code null} when it cannot be loaded, which
     *                   resolves to {@link AccessMode#MATERIALIZED} because a source Aran cannot
     *                   identify is not one it can federate
     * @param datasetName the relation's name, needed to decide whether a scanner can actually be
     *                    built for it
     * @param datasetHasLakeSnapshot whether a lake snapshot has been materialized for this dataset —
     *                   the per-dataset override for object storage
     */
    public static AccessMode resolve(@Nullable DataRepository repository, String datasetName,
                                     boolean datasetHasLakeSnapshot) {
        if (repository instanceof RelationalDatabaseRepository relational && relational.federated()
                && FederatedScanExpression.forRepository(relational, datasetName) != null) {
            return AccessMode.FEDERATED;
        }
        if (repository instanceof ObjectStorageRepository) {
            return datasetHasLakeSnapshot ? AccessMode.MATERIALIZED : AccessMode.FEDERATED;
        }
        // Iceberg follows object storage's model exactly, and for the same reason: the per-dataset
        // lake copy IS the signal, because one catalog can hold a table worth reading live and a
        // table worth copying, and a repository-level flag would be the SPI's forbidden step 3.
        if (repository instanceof IcebergRestRepository) {
            return datasetHasLakeSnapshot ? AccessMode.MATERIALIZED : AccessMode.FEDERATED;
        }
        return AccessMode.MATERIALIZED;
    }

    /**
     * Whether this repository <em>could</em> serve any dataset federated — i.e. whether its engine
     * has a scanner at all, independent of what any repository has asked for.
     *
     * <p>This is the capability question, as opposed to {@link #resolve}'s configuration question.
     * A registration form needs this one: it is what separates "you have not turned federation on"
     * from "this engine cannot do it", and telling a user the first when the second is true is the
     * honesty gap that ran one level below Gate 0's fix.
     */
    public static boolean supportsFederation(DataRepository repository) {
        return repository instanceof RelationalDatabaseRepository relational
                && FederatedScanExpression.extensionFor(relational.engine()) != null;
    }
}
