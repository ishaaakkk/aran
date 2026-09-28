package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence port for retrieving, saving, and deleting {@link Dataset}s.
 *
 * <p>Strictly tenant-scoped: every database access signature threads an
 * explicit {@link TenantContext} to enforce vertical tenant boundary isolation.
 */
@NullMarked
public interface DatasetRepository {

    List<Dataset> findAllByRepository(TenantContext tenant, UUID repositoryId);

    Optional<Dataset> findById(TenantContext tenant, UUID datasetId);

    /** Persist (insert or update) a dataset. */
    Dataset save(TenantContext tenant, Dataset dataset);

    void delete(TenantContext tenant, UUID datasetId);
}
