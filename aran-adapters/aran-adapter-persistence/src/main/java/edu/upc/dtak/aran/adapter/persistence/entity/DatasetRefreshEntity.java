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

/**
 * JPA entity for a dataset's refresh policy (V28) — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.DatasetRefresh}.
 *
 * <p>{@code datasetId} is the primary key, not a surrogate: a dataset has one upstream and one
 * policy, so a table that could hold two rows for it would need a uniqueness rule to say so.
 *
 * <p>{@code policy} and {@code intervalSeconds} are stored flat rather than as one encoded string
 * because the database enforces their relationship (the {@code ck_dataset_refresh_policy} check),
 * and a check constraint cannot see inside {@code "EVERY:PT1H"}.
 */
@Entity
@Table(name = "dataset_refresh")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DatasetRefreshEntity {

    @Id
    @Column(name = "dataset_id", nullable = false, updatable = false)
    private UUID datasetId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "repository_id", nullable = false, updatable = false)
    private UUID repositoryId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "source_table", nullable = false, updatable = false, length = 512)
    private String sourceTable;

    @Column(name = "policy", nullable = false, length = 32)
    private String policy;

    @Column(name = "interval_seconds")
    private Long intervalSeconds;

    @Column(name = "last_refresh_at")
    private Instant lastRefreshAt;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_error")
    private String lastError;
}
