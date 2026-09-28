package edu.upc.dtak.aran.core.domain.drift;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence <em>port</em> for schema fingerprints — tenant-scoped like every other repository
 * port, with the tenant passed explicitly rather than inferred.
 */
@NullMarked
public interface SchemaFingerprintRepository {

    /**
     * The newest fingerprint for a dataset captured the given way, or empty when the dataset has
     * never been fingerprinted.
     *
     * <p>Filtered by capture method on purpose: a live probe records only what a metadata query
     * can see, so comparing one against a full snapshot introspection would report the missing
     * richness as drift. Each method's history is its own baseline.
     */
    Optional<StoredSchemaFingerprint> findLatest(TenantContext tenant, UUID datasetId,
                                                 StoredSchemaFingerprint.CaptureMethod via);

    /** Append a capture. Rows are never updated — the history is the feature. */
    StoredSchemaFingerprint save(TenantContext tenant, StoredSchemaFingerprint fingerprint);

    /** Newest first, for "what has this source been doing lately". */
    List<StoredSchemaFingerprint> history(TenantContext tenant, UUID datasetId, int limit);
}
