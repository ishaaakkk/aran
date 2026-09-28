package edu.upc.dtak.aran.core.domain;

import java.util.UUID;

/**
 * A data repository registered within a project.
 */
public sealed interface DataRepository permits
        RelationalDatabaseRepository,
        ObjectStorageRepository,
        FileSystemRepository,
        SingerTapRepository,
        IcebergRestRepository {

    UUID id();

    UUID projectId();

    /** The tenant that owns the project (the isolation boundary). */
    UUID tenantId();

    String name();

    String description();

    RepositoryType type();
}
