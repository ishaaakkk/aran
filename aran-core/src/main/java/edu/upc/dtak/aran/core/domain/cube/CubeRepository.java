package edu.upc.dtak.aran.core.domain.cube;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Port for persisting cubes, implemented by the persistence adapter. Shaped like
 * {@code DataProductRepository}, and deliberately no wider: v1 creates, reads and deletes a cube
 * — there is no update, so there is no method for one.
 */
@NullMarked
public interface CubeRepository {

    /** A project's cubes, newest first. */
    List<CubeRecord> list(TenantContext tenant, UUID projectId);

    Optional<CubeRecord> find(TenantContext tenant, UUID id);

    CubeRecord save(TenantContext tenant, CubeRecord cube);

    void delete(TenantContext tenant, UUID id);
}
