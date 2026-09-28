package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/**
 * Which lake snapshots something in Aran still points at.
 *
 * <p>The lake stores bytes and knows whose they are; it has no idea what a data product or a
 * schema fingerprint is, and should not. Reachability is Aran's question, so Aran answers it and
 * the lake just hands over the inventory.
 *
 * <p>Its own port rather than a method on three repositories because the answer is a union across
 * tables and must stay one query — asking each repository separately would make it possible to
 * add a fourth table that references snapshots and forget this one, and the failure mode of that
 * is reporting live data as garbage.
 */
@NullMarked
public interface SnapshotReferenceRepository {

    /**
     * Every snapshot id reachable from the tenant's rows.
     *
     * <p>Deliberately over-inclusive where it is unsure. Counting a reference that does not really
     * pin the bytes only under-reports how much is reclaimable; missing one reports live data as
     * garbage, and the whole point of the number is to decide what may be deleted.
     */
    Set<String> referencedSnapshotIds(TenantContext tenant);
}
