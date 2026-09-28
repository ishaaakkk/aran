package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Port interface for managing graph versioning metadata in Postgres.
 */
@NullMarked
public interface GraphVersionPort {
    GraphVersion save(TenantContext tenant, GraphVersion version);
    Optional<GraphVersion> findById(TenantContext tenant, UUID id);
    Optional<GraphVersion> findCurrentActive(TenantContext tenant);
    Optional<GraphVersion> findLatest(TenantContext tenant);

    /** All versions the tenant owns, newest version number first (M7 versions view). */
    List<GraphVersion> findAll(TenantContext tenant);
}
