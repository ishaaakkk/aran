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
 * JPA entity for a repository's last connection check (V46) — counterpart of
 * {@code edu.upc.dtak.aran.core.domain.ConnectionCheck}. Keyed by the repository: one row each.
 */
@Entity
@Table(name = "repository_connection_check")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RepositoryConnectionCheckEntity {

    @Id
    @Column(name = "repository_id", nullable = false, updatable = false)
    private UUID repositoryId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private boolean success;

    @Column(nullable = false)
    private String message;

    @Column
    private String details;

    @Column(name = "tested_at", nullable = false)
    private Instant testedAt;

    @Column(name = "tested_by")
    private UUID testedBy;
}
