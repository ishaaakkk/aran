package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.ApiToken;
import edu.upc.dtak.aran.core.domain.ApiTokenRepository;
import edu.upc.dtak.aran.core.domain.AuditEvent;
import edu.upc.dtak.aran.core.domain.AuditPort;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.domain.UsageEventRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a tenant owner sees on the admin screen.
 *
 * <p>Read-only and owner-gated. Everything it returns is scoped to the caller's own tenant, and
 * no method takes a tenant id — there is no case where an owner inspects a tenant other than
 * theirs.
 *
 * <h2>Four separate questions, deliberately not merged</h2>
 *
 * <p><b>People</b> (who exists, what may they do) is mutable state. <b>Credentials</b> (what can
 * act on their behalf) are secrets, and are write-only — nothing here can read a token back.
 * <b>Activity</b> (what happened) is append-only history. <b>Usage</b> (how much) is a
 * measurement. Admin screens that blur these end up letting somebody "edit" an audit record.
 */
@Service
@NullMarked
public class TenantAdminOverviewService {

    /** The window every rate on this screen is measured over, unless the caller says otherwise. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final TenantRepository tenants;
    private final UserRepository users;
    private final UsageEventRepository usage;
    private final ApiTokenRepository tokens;
    private final AuditPort audit;

    public TenantAdminOverviewService(TenantRepository tenants, UserRepository users,
                                      UsageEventRepository usage, ApiTokenRepository tokens,
                                      AuditPort audit) {
        this.tenants = tenants;
        this.users = users;
        this.usage = usage;
        this.tokens = tokens;
        this.audit = audit;
    }

    /**
     * A member with their consumption over the window.
     *
     * @param userId null identifies the synthetic row for work with no attributable human — a
     *               scheduled refresh, a system job. Shown rather than hidden, because a tenant
     *               whose consumption is mostly unattributed should be able to see that.
     */
    public record MemberUsage(
            @Nullable UUID userId,
            String displayName,
            String email,
            @Nullable TenantRole role,
            @Nullable Instant lastSeenAt,
            long events,
            long rows,
            long bytesIn,
            long bytesOut,
            @Nullable Long p95DurationMs
    ) {}

    /**
     * @param status  {@code active}, {@code expired}, {@code revoked} or {@code owner_removed} —
     *                computed here, like the holder's own list does, so the admin table cannot
     *                show a dead token as live (audit J7-F8: expired and orphaned tokens looked
     *                live and counted as active). {@code owner_removed} is a token whose holder
     *                has left the tenant without it being revoked, which only removals made
     *                before UI-OD9 left behind; it cannot authenticate.
     * @param revoked kept beside {@code status} for clients that predate it
     */
    public record TokenSummary(
            UUID id, UUID userId, String ownerName, String name, String prefix,
            String scope, @Nullable Instant lastUsedAt, @Nullable Instant expiresAt,
            boolean revoked, Instant createdAt, String status
    ) {}

    /** The four answers {@link TokenSummary#status} gives, in the order they take precedence. */
    static String statusOf(ApiToken token, boolean holderIsMember, Instant now) {
        if (token.revokedAt() != null) {
            return "revoked";
        }
        if (!holderIsMember) {
            return "owner_removed";
        }
        return token.isUsable(now) ? "active" : "expired";
    }

    public record Overview(
            String tenantSlug,
            String tenantName,
            Instant since,
            long memberCount,
            long activeTokenCount,
            long totalEvents,
            long totalRows,
            long totalBytesIn,
            long totalBytesOut,
            long totalBytesStored,
            List<MemberUsage> members
    ) {}

    @Transactional(readOnly = true)
    public Overview overview(AuthenticatedUser actor, Duration window) {
        requireOwner(actor);
        UUID tenantId = actor.tenantId();
        Instant since = Instant.now().minus(window);

        List<Membership> memberships = tenants.findMembershipsForTenant(tenantId);
        Map<UUID, Membership> byUser = new HashMap<>();
        memberships.forEach(m -> byUser.put(m.userId(), m));

        Map<UUID, Instant> lastSeen = users.lastSeenFor(byUser.keySet());
        Map<UUID, UsageEventRepository.UsagePerUser> consumption = new HashMap<>();
        UsageEventRepository.UsagePerUser unattributed = null;
        for (UsageEventRepository.UsagePerUser row : usage.usagePerUser(tenantId, since)) {
            if (row.userId() == null) {
                unattributed = row;
            } else {
                consumption.put(row.userId(), row);
            }
        }

        List<MemberUsage> rows = new ArrayList<>();
        for (Membership membership : memberships) {
            Optional<User> user = users.findById(membership.userId());
            UsageEventRepository.UsagePerUser used = consumption.get(membership.userId());
            rows.add(new MemberUsage(
                    membership.userId(),
                    user.map(User::displayName).orElse("(deleted user)"),
                    user.map(User::email).orElse(""),
                    membership.role(),
                    lastSeen.get(membership.userId()),
                    used == null ? 0 : used.events(),
                    used == null ? 0 : used.rows(),
                    used == null ? 0 : used.bytesIn(),
                    used == null ? 0 : used.bytesOut(),
                    used == null ? null : used.p95DurationMs()));
        }
        // Busiest first: the question this screen is opened to answer is "who is running the
        // expensive things", and alphabetical would bury it.
        rows.sort((a, b) -> Long.compare(b.events(), a.events()));

        if (unattributed != null && unattributed.events() > 0) {
            rows.add(new MemberUsage(null, "Scheduled and system work", "", null, null,
                    unattributed.events(), unattributed.rows(), unattributed.bytesIn(),
                    unattributed.bytesOut(), unattributed.p95DurationMs()));
        }

        UsageEventRepository.UsageTotals totals = usage.totals(tenantId, since);
        var tenant = tenants.findById(tenantId);
        // A token whose holder left the tenant cannot authenticate, whatever its own fields say.
        Instant now = Instant.now();
        long activeTokens = tokens.findByTenant(new TenantContext(tenantId.toString())).stream()
                .filter(t -> "active".equals(statusOf(t, byUser.containsKey(t.userId()), now)))
                .count();

        return new Overview(
                tenant.map(t -> t.slug()).orElse(""),
                tenant.map(t -> t.name()).orElse(""),
                since,
                memberships.size(),
                activeTokens,
                totals.events(), totals.rows(),
                totals.bytesIn(), totals.bytesOut(), totals.bytesStored(),
                rows);
    }

    /**
     * Every token in the tenant, as summaries.
     *
     * <p>Never the secret, and never enough to reconstruct one: the prefix is the same
     * displayable head the owner's own token list shows. This closes the audit gap — an owner
     * could always end access by removing a membership, but could not see what existed.
     */
    @Transactional(readOnly = true)
    public List<TokenSummary> tokens(AuthenticatedUser actor) {
        requireOwner(actor);
        Map<UUID, String> names = new HashMap<>();
        java.util.Set<UUID> members = new java.util.HashSet<>();
        tenants.findMembershipsForTenant(actor.tenantId()).forEach(m -> members.add(m.userId()));
        Instant now = Instant.now();
        return tokens.findByTenant(new TenantContext(actor.tenantId().toString())).stream()
                .map(t -> new TokenSummary(
                        t.id(), t.userId(),
                        names.computeIfAbsent(t.userId(), id ->
                                users.findById(id).map(User::displayName).orElse("(deleted user)")),
                        t.name(), t.tokenPrefix(), t.scope().name(),
                        t.lastUsedAt(), t.expiresAt(), t.revokedAt() != null, t.createdAt(),
                        statusOf(t, members.contains(t.userId()), now)))
                .toList();
    }

    /** Recent governance events — who was added, removed, promoted. */
    @Transactional(readOnly = true)
    public List<AuditEvent> activity(AuthenticatedUser actor, int limit) {
        requireOwner(actor);
        return audit.recent(new TenantContext(actor.tenantId().toString()),
                Math.max(1, Math.min(limit, 500)));
    }

    private static void requireOwner(AuthenticatedUser actor) {
        if (!actor.canAdministerTenant()) {
            throw new AccessDeniedException("only a tenant owner can view the admin overview");
        }
    }
}
