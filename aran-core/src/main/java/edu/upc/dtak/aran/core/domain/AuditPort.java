package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence <em>port</em> for {@link AuditEvent}.
 *
 * <p>Append-only by contract: there is no {@code update} or {@code delete}.
 * Implementations must reject attempts to mutate existing rows at the
 * database level (revoking UPDATE/DELETE on the table is the cleanest move,
 * but a CHECK or trigger works too).
 *
 * <p>Tenant-scoped: every write carries its tenant, and reads cannot cross
 * tenants. The Hibernate tenant filter applies here exactly as it does for
 * {@link Project}.
 */
@NullMarked
public interface AuditPort {

    /**
     * Append an audit event. Must be called from inside the same transaction
     * as the action it audits.
     */
    AuditEvent record(TenantContext tenant, AuditEvent event);

    /**
     * Recent audit events for a tenant, newest first. Capped to {@code limit}
     * entries; callers paginating beyond that should add their own pagination
     * port when the need arises.
     */
    List<AuditEvent> recent(TenantContext tenant, int limit);

    /**
     * Whether this tenant has recorded {@code action} with {@code metadataKey} equal to
     * {@code metadataValue} — "has this organization ever removed that user?". Tenant-scoped like
     * every read here: another tenant's lines never answer it.
     */
    boolean exists(TenantContext tenant, String action, String metadataKey, String metadataValue);

    /**
     * Recent events that belong to one of {@code projectIds}, newest first, capped to
     * {@code limit}: those targeting the project itself, and those carrying it as their
     * {@code projectId} metadata (a promote of that project's model run).
     *
     * <p>The dashboard feed for a member who does not own the tenant (UI-OD11). Filtering in the
     * query rather than after a {@link #recent} page is what keeps the feed full: a tenant whose
     * last hundred events are all in projects this member cannot see would otherwise hand them
     * an empty list that reads as "nothing happened". An empty {@code projectIds} answers empty.
     */
    List<AuditEvent> recentForProjects(TenantContext tenant, java.util.Collection<java.util.UUID> projectIds,
                                       int limit);
}
