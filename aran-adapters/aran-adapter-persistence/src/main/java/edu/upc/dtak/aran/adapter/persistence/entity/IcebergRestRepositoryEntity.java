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
 * JPA entity for an Iceberg REST catalog repository (Gap 2).
 *
 * <p>The data-plane columns ({@code provider}/{@code endpoint}/{@code access_key}/
 * {@code secret_key}/{@code region}) are the V6 object-storage columns, reused: same concept,
 * same encryption converter. Only the control-plane columns are new (V40).
 */
@Entity
@DiscriminatorValue("ICEBERG_REST")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IcebergRestRepositoryEntity extends DataRepositoryEntity {

    @Column(name = "catalog_uri")
    private String catalogUri;

    @Column(name = "warehouse")
    private String warehouse;

    @Column(name = "catalog_token")
    @Convert(converter = CredentialEncryptionConverter.class)
    private String catalogToken;

    @Column(name = "provider")
    private String provider;

    @Column(name = "endpoint")
    private String endpoint;

    @Column(name = "access_key")
    private String accessKey;

    @Column(name = "secret_key")
    @Convert(converter = CredentialEncryptionConverter.class)
    private String secretKey;

    @Column(name = "region")
    private String region;

    public IcebergRestRepositoryEntity(UUID id, UUID projectId, UUID tenantId, String name,
                                       String description, String catalogUri, String warehouse,
                                       String catalogToken, String provider, String endpoint,
                                       String accessKey, String secretKey, String region) {
        super(id, projectId, tenantId, name, description);
        this.catalogUri = catalogUri;
        this.warehouse = warehouse;
        this.catalogToken = catalogToken;
        this.provider = provider;
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }
}
