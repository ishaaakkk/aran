package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Names objects by id, a batch at a time: what a list of jobs is about, in words (UI audit J8-F7).
 *
 * <p>A separate port rather than the objects' own repositories because a list names up to a
 * hundred objects and needs two facts about each — its name, and the project it lives in, which
 * is what decides whether the caller may see the name at all. Loading whole datasets for that
 * would read every column profile of each (the collection is eager); one projection per kind is
 * three queries for any number of jobs.
 *
 * <p>Every lookup is tenant-scoped; an id absent from the result does not exist in the tenant.
 */
@NullMarked
public interface NamedObjectRepository {

    /**
     * @param projectId the project the object lives in — for a dataset, its repository's
     * @param repositoryId a dataset's repository; null for other kinds
     */
    record NamedObject(UUID id, String name, UUID projectId, @Nullable UUID repositoryId) {
    }

    Map<UUID, NamedObject> datasets(TenantContext tenant, Collection<UUID> ids);

    Map<UUID, NamedObject> products(TenantContext tenant, Collection<UUID> ids);

    Map<UUID, NamedObject> projects(TenantContext tenant, Collection<UUID> ids);
}
