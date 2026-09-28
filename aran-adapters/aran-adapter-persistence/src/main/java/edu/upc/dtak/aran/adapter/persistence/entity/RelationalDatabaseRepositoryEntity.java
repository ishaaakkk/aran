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
@DiscriminatorValue("RELATIONAL_DB")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationalDatabaseRepositoryEntity extends DataRepositoryEntity {

    @Column(name = "engine")
    private String engine;

    @Column(name = "host")
    private String host;

    @Column(name = "port")
    private Integer port;

    @Column(name = "database_name")
    private String database;

    @Column(name = "username")
    private String username;

    @Column(name = "password")
    @Convert(converter = CredentialEncryptionConverter.class)
    private String password;

    @Column(name = "schema_name")
    private String schema;

    @Column(name = "federated", nullable = false)
    private boolean federated = false;

    public RelationalDatabaseRepositoryEntity(UUID id, UUID projectId, UUID tenantId, String name, String description,
                                              String engine, String host, Integer port, String database,
                                              String username, String password, String schema, boolean federated) {
        super(id, projectId, tenantId, name, description);
        this.engine = engine;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.schema = schema;
        this.federated = federated;
    }
}
