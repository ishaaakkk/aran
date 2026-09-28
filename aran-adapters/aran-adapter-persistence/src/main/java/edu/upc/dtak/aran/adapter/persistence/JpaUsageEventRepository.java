package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.UsageEventEntity;
import edu.upc.dtak.aran.core.domain.UsageEvent;
import edu.upc.dtak.aran.core.domain.UsageEventRepository;
import edu.upc.dtak.aran.core.domain.UsageKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link UsageEventRepository} over JPA.
 *
 * <p>The aggregates are native queries rather than JPQL because they use
 * {@code percentile_cont}, which JPQL has no way to express and which is the difference between
 * "the average query took 400ms" — a number one runaway export can make meaningless — and a p95
 * that describes what people actually experience.
 */
@Repository
@NullMarked
public class JpaUsageEventRepository implements UsageEventRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public UsageEvent save(UsageEvent event) {
        entityManager.persist(new UsageEventEntity(
                event.id(), event.tenantId(), event.userId(), event.kind().name(),
                event.occurredAt(), event.durationMs(), event.rowCount(), event.byteCount()));
        return event;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<UsagePerUser> usagePerUser(UUID tenantId, Instant since) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT user_id,
                       COUNT(*)                                                     AS events,
                       COALESCE(SUM(row_count), 0)                                  AS rows_total,
                       COALESCE(SUM(byte_count) FILTER (WHERE kind IN (:inKinds)), 0)  AS bytes_in,
                       COALESCE(SUM(byte_count) FILTER (WHERE kind IN (:outKinds)), 0) AS bytes_out,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)    AS p95,
                       MAX(occurred_at)                                             AS last_at
                  FROM usage_event
                 WHERE tenant_id = :tenantId AND occurred_at >= :since
                 GROUP BY user_id
                 ORDER BY events DESC
                """)
                .setParameter("tenantId", tenantId)
                .setParameter("since", since)
                .setParameter("inKinds", UsageKind.namesWithDirection(UsageKind.Direction.INGRESS))
                .setParameter("outKinds", UsageKind.namesWithDirection(UsageKind.Direction.EGRESS))
                .getResultList();

        return rows.stream().map(r -> new UsagePerUser(
                (UUID) r[0],
                ((Number) r[1]).longValue(),
                ((Number) r[2]).longValue(),
                ((Number) r[3]).longValue(),
                ((Number) r[4]).longValue(),
                r[5] == null ? null : ((Number) r[5]).longValue(),
                toInstant(r[6])
        )).toList();
    }

    /**
     * {@code MAX(occurred_at)} over a {@code TIMESTAMPTZ}, whatever the stack hands back.
     *
     * <p>A native query has no mapped attribute to read the type from, so the value's Java class
     * is whatever Hibernate and the JDBC driver agree on for that column — {@link Instant} here,
     * {@link java.sql.Timestamp} on older drivers, {@link OffsetDateTime} when a driver preserves
     * the zone. This used to cast straight to {@code java.sql.Timestamp} and threw a
     * {@link ClassCastException} that took the whole admin overview down with it, so the mapping
     * now names every shape it accepts and fails loudly on one it does not.
     */
    private static @org.jspecify.annotations.Nullable Instant toInstant(@org.jspecify.annotations.Nullable Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            default -> throw new IllegalStateException(
                    "Unsupported timestamp type from usage_event.occurred_at: " + value.getClass());
        };
    }

    @Override
    @Transactional(readOnly = true)
    public UsageTotals totals(UUID tenantId, Instant since) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*),
                       COALESCE(SUM(row_count), 0),
                       COALESCE(SUM(byte_count) FILTER (WHERE kind IN (:inKinds)), 0),
                       COALESCE(SUM(byte_count) FILTER (WHERE kind IN (:outKinds)), 0),
                       COALESCE(SUM(byte_count) FILTER (WHERE kind IN (:storedKinds)), 0)
                  FROM usage_event
                 WHERE tenant_id = :tenantId AND occurred_at >= :since
                """)
                .setParameter("tenantId", tenantId)
                .setParameter("since", since)
                .setParameter("inKinds", UsageKind.namesWithDirection(UsageKind.Direction.INGRESS))
                .setParameter("outKinds", UsageKind.namesWithDirection(UsageKind.Direction.EGRESS))
                .setParameter("storedKinds", UsageKind.namesWithDirection(UsageKind.Direction.STORED))
                .getSingleResult();

        return new UsageTotals(((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(), ((Number) row[3]).longValue(),
                ((Number) row[4]).longValue());
    }
}
