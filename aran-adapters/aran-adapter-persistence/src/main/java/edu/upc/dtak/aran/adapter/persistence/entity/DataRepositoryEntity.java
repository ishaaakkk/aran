package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

/**
 * Base abstract JPA entity for registered data repositories using SINGLE_TABLE inheritance.
 */
@Entity
@Table(name = "data_repository")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class DataRepositoryEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    /**
     * When this repository's configuration was last saved (V46) — stamped by every save, read by
     * nothing but the connection-check query, which drops a check that began before it: a test
     * racing a save describes the configuration the save replaced (J2-F19).
     */
    @Column(name = "config_saved_at")
    private java.time.Instant configSavedAt;

    protected DataRepositoryEntity(UUID id, UUID projectId, UUID tenantId, String name, String description) {
        this.id = id;
        this.projectId = projectId;
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
    }
}
