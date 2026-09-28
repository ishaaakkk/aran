package edu.upc.dtak.aran.adapter.persistence.entity;

import edu.upc.dtak.aran.adapter.persistence.jpa.CredentialEncryptionConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for a Singer tap repository (T6).
 */
@Entity
@DiscriminatorValue("SINGER_TAP")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SingerTapRepositoryEntity extends DataRepositoryEntity {

    /** Not the command — an id into the deployment's catalogue. See {@code SingerTapCatalog}. */
    @Column(name = "tap_id")
    private String tapId;

    /**
     * The user's settings as JSON, encrypted because the tap decides which of its keys are
     * credentials and the column cannot: one map holds both {@code base} and {@code api_key}.
     * Encrypting the whole object is the only mapping that is right whatever the tap declares.
     */
    @Column(name = "tap_config")
    @Convert(converter = CredentialEncryptionConverter.class)
    private String tapConfig;

    public SingerTapRepositoryEntity(UUID id, UUID projectId, UUID tenantId, String name,
                                     String description, String tapId, String tapConfig) {
        super(id, projectId, tenantId, name, description);
        this.tapId = tapId;
        this.tapConfig = tapConfig;
    }
}
