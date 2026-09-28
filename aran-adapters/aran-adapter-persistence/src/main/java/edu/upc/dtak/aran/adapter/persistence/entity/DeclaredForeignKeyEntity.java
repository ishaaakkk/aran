package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for a foreign key harvested live from a relational source's own catalog (V33, Phase
 * 4B) — counterpart of {@code edu.upc.dtak.aran.core.domain.DeclaredForeignKey}.
 *
 * <p>{@code fromCols}/{@code toCols} store a small ordered string list as {@code jsonb} via
 * {@code @JdbcTypeCode(SqlTypes.JSON)} rather than a join table: nothing queries these columns by
 * value, only reads them back ordinal-paired with the other side (see the domain record's javadoc
 * for why the pairing itself must never be re-sorted), so a join table would buy indexable columns
 * this entity has no use for.
 */
@Entity
@Table(name = "declared_foreign_key")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DeclaredForeignKeyEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "repository_id", nullable = false, updatable = false)
    private UUID repositoryId;

    @Column(name = "from_dataset_id", nullable = false, updatable = false)
    private UUID fromDatasetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "from_cols", columnDefinition = "jsonb", nullable = false)
    private List<String> fromCols = new ArrayList<>();

    @Column(name = "to_dataset_id", nullable = false, updatable = false)
    private UUID toDatasetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "to_cols", columnDefinition = "jsonb", nullable = false)
    private List<String> toCols = new ArrayList<>();
}
