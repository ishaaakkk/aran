package edu.upc.dtak.aran.core.domain;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A repository that holds the files uploaded to it — CSV, JSON, Parquet — and nothing else.
 *
 * <p>Upload-only since D1: there is no root path. The kind used to name a server directory that
 * Aran listed and read, which made it an outbound-read surface over the backend's own filesystem
 * with no ingestion behind it (a listed file could not be registered). The {@code root_path}
 * column V6 created stays in the table, unmapped and unread, on V44's rows-only precedent.
 */
@NullMarked
public record FileSystemRepository(
        UUID id,
        UUID projectId,
        UUID tenantId,
        String name,
        String description
) implements DataRepository {

    @Override
    public RepositoryType type() {
        return RepositoryType.FILE_SYSTEM;
    }
}
