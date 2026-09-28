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

@Entity
@DiscriminatorValue("OBJECT_STORAGE")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObjectStorageRepositoryEntity extends DataRepositoryEntity {

    @Column(name = "provider")
    private String provider;

    @Column(name = "endpoint")
    private String endpoint;

    @Column(name = "bucket_name")
    private String bucketName;

    @Column(name = "access_key")
    private String accessKey;

    @Column(name = "secret_key")
    @Convert(converter = CredentialEncryptionConverter.class)
    private String secretKey;

    @Column(name = "region")
    private String region;

    public ObjectStorageRepositoryEntity(UUID id, UUID projectId, UUID tenantId, String name, String description,
                                         String provider, String endpoint, String bucketName,
                                         String accessKey, String secretKey, String region) {
        super(id, projectId, tenantId, name, description);
        this.provider = provider;
        this.endpoint = endpoint;
        this.bucketName = bucketName;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }
}
