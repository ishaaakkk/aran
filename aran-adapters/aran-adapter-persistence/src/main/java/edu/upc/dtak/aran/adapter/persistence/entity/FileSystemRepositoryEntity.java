package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * An upload-only repository: no columns of its own. {@code root_path} (V6) is deliberately
 * unmapped — ddl-auto: validate ignores a column no entity names, and nothing reads it (D1).
 */
@Entity
@DiscriminatorValue("FILE_SYSTEM")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FileSystemRepositoryEntity extends DataRepositoryEntity {

    public FileSystemRepositoryEntity(UUID id, UUID projectId, UUID tenantId, String name, String description) {
        super(id, projectId, tenantId, name, description);
    }
}
