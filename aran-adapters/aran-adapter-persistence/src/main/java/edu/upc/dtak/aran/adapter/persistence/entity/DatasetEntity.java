package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import edu.upc.dtak.aran.core.domain.DatasetStatus;

/**
 * JPA entity for a dataset.
 *
 * <p>Uses the shared-schema tenant boundary filter to enforce isolation.
 */
@Entity
@Table(
        name = "dataset",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_dataset_repository_name",
                columnNames = {"repository_id", "name"}
        )
)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DatasetEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "repository_id", nullable = false, updatable = false)
    private UUID repositoryId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = true)
    private String description;

    @Column(name = "snapshot_id", nullable = true)
    private String snapshotId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph_json", columnDefinition = "jsonb")
    private String graphJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "bootstrap_status")
    private DatasetStatus bootstrapStatus;

    /**
     * Column profiles for this dataset.
     *
     * <p>Kept EAGER because {@code EntityMapper.toDomain} maps them into the {@code Dataset}
     * record on every load, so making them lazy would either throw outside a transaction or
     * simply move the N+1 elsewhere.
     *
     * <p>{@code SUBSELECT} is what removes the N+1: without it Hibernate issues one SELECT per
     * dataset row to hydrate this collection, so listing a repository's datasets cost
     * 1 + N queries and pulled every column profile in the tenant. With it, all collections for
     * the datasets loaded by a query are fetched in a single follow-up subselect — two queries
     * regardless of row count.
     *
     * <p>The better end state is LAZY plus an explicit fetch on the paths that actually need
     * profiles (the dataset detail panel), which also stops list endpoints loading them at all.
     * That requires changing {@code EntityMapper} and is worth doing under integration tests.
     */
    @OneToMany(mappedBy = "dataset", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    private List<ColumnProfileEntity> columns = new ArrayList<>();

    public DatasetEntity(UUID id, UUID repositoryId, UUID tenantId, String name, String description, String snapshotId) {
        this.id = id;
        this.repositoryId = repositoryId;
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.snapshotId = snapshotId;
        this.columns = new ArrayList<>();
    }
}
