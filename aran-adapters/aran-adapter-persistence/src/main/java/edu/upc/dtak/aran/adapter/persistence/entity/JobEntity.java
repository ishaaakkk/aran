package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import edu.upc.dtak.aran.core.domain.JobStatus;

/**
 * JPA entity for a job — counterpart of {@code edu.upc.dtak.aran.core.domain.Job}.
 *
 * <p>{@code parameters} and {@code result} are JSONB, held here as their JSON
 * text; {@code JpaJobRepository} owns the conversion, exactly as
 * {@code JpaAuditAdapter} does for audit metadata.
 *
 * <p>Tenant-filtered: the {@code tenantFilter} definition lives on
 * {@code ProjectEntity}; this entity carries the {@link Filter} referencing it,
 * so a job read under tenant A can never return tenant B's row.
 */
@Entity
@Table(name = "job")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class JobEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "job_type", nullable = false, updatable = false)
    private String jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String parameters;

    @Column(name = "progress_stage")
    private String progressStage;

    @Column(name = "progress_percent")
    private Integer progressPercent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
