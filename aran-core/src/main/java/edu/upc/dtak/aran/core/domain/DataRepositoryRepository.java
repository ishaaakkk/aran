package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence port for retrieving, saving, and deleting {@link DataRepository}s.
 *
 * <p>Strictly tenant-scoped: every database access signature threads an
 * explicit {@link TenantContext} to enforce vertical tenant boundary isolation.
 */
@NullMarked
public interface DataRepositoryRepository {

    List<DataRepository> findAllByProject(TenantContext tenant, UUID projectId);

    Optional<DataRepository> findById(TenantContext tenant, UUID repositoryId);

    /**
     * Persist (insert or update) a repository, recording when its configuration was saved — the
     * line {@link ConnectionCheckRepository#findAllByRepositoryIds} draws between checks that
     * describe it and checks that describe what it replaced.
     */
    DataRepository save(TenantContext tenant, DataRepository repository);

    void delete(TenantContext tenant, UUID repositoryId);
}
