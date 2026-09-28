package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * One reading of how much a tenant is holding.
 *
 * <p>A level, not an event. Two samples describe the same bytes measured twice, so they are
 * compared and never added — see {@code V25__storage_sample.sql} for why that distinction earns
 * its own table rather than living in {@code usage_event}.
 *
 * @param logicalBytes  sum over every snapshot — what the tenant believes it stored.
 * @param physicalBytes sum over distinct content hashes — what occupies the bucket, and so what
 *                      a storage bill is drawn against.
 */
@NullMarked
public record StorageSample(
        UUID id,
        UUID tenantId,
        Instant measuredAt,
        long logicalBytes,
        long physicalBytes,
        long snapshotCount
) {
}
