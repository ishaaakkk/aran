package edu.upc.dtak.aran.core.domain.drift;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A {@link SchemaFingerprint} as it is persisted (V22) — the durable "before" that a later check
 * compares against.
 *
 * @param snapshotId  the lake snapshot this was taken from, or null for a live probe against a
 *                    federated source, which has no snapshot to name
 * @param capturedVia how the shape was obtained; see {@link CaptureMethod}
 */
@NullMarked
public record StoredSchemaFingerprint(
        UUID id,
        UUID tenantId,
        UUID datasetId,
        String hash,
        String fingerprintJson,
        @Nullable String snapshotId,
        CaptureMethod capturedVia,
        Instant capturedAt) {

    public enum CaptureMethod {
        /**
         * Taken from a bootstrap's own candidate graph — the full shape, with format, iterator and
         * XSD ranges. Named for the usual source of that graph (snapshot bytes), and it stays the
         * right bucket for a snapshot-less federated table bootstrapped from its remote catalog:
         * what separates the two methods is the richness of the fingerprint, not where the schema
         * was read, and only fingerprints of equal richness may be compared.
         */
        SNAPSHOT,
        /**
         * Read from a live federated source with a metadata-only query. Carries column names and
         * types but not the iterator/format richness of a real bootstrap, so a fingerprint
         * captured this way is only ever compared against another live probe.
         */
        LIVE_PROBE
    }
}
