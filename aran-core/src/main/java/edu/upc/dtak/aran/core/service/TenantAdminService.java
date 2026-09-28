package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import java.time.Instant;
import java.util.UUID;

import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import edu.upc.dtak.aran.core.licensing.LicenseExpiredException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant lifecycle and membership administration — the platform-admin paths.
 *
 * <p>Authorization is enforced at the controller layer (the {@code /api/admin}
 * filter chain requires the {@code platform-admin} Keycloak realm role). The
 * service intentionally does not re-check this — it would obscure the
 * intent: this service is <em>for</em> platform admins, full stop. Tenant
 * OWNER-level member management lives in {@link TenantOwnerService}.
 *
 * <p>Two invariants this service owns:
 * <ul>
 *   <li>Every tenant has a license. Tenant creation creates the tenant and
 *       inserts an initial license row in the <em>same</em> transaction
 *       (delegated to the {@code LicenseAdminPort}). Without this, a tenant
 *       briefly exists with no entitlements and every request through it
 *       would 402. The port is optional at wiring time — see the constructor —
 *       but the invariant is not: {@link #createTenant} refuses to run without
 *       it rather than leaving a tenant unlicensed.</li>
 *   <li>Tenant slugs are unique platform-wide and unicode-safe. The DB
 *       UNIQUE constraint is the final say; this service just rejects
 *       obviously-bad slugs early.</li>
 * </ul>
 */
@Service
@NullMarked
public class TenantAdminService {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final @Nullable LicenseAdminPort licenseAdmin;
    private final LicenseService licensing;
    private final AuditService audit;
    private final GraphStore graphStore;

    /**
     * {@code licenseAdmin} arrives as an {@link ObjectProvider} because it is the one dependency
     * here that a deployment mode may legitimately not register: {@code SignedTokenLicensingConfig}
     * provides a {@code LicenseProvider} only, on the grounds that a customer's running instance is
     * not the license-issuance authority. As a hard constructor argument this bean would then be
     * unsatisfiable, and the whole application context with it.
     *
     * <p>This was latent rather than active until {@code DatabaseLicensingConfig}'s beans were
     * moved behind {@code @Bean} methods: as nested {@code @Component}s in a scanned package they
     * ignored their own configuration's condition and registered an issuer in every mode, so the
     * argument always happened to be satisfiable. See that class for why that was worse, not
     * better.
     *
     * <p>The other paths on this service (member management, tenant listing) have no business being
     * unavailable just because licenses are issued elsewhere, which is why this widens the wiring
     * rather than making the whole service {@code @ConditionalOnBean}.
     */
    public TenantAdminService(TenantRepository tenants,
                              UserRepository users,
                              ObjectProvider<LicenseAdminPort> licenseAdmin,
                              LicenseService licensing,
                              AuditService audit,
                              GraphStore graphStore) {
        this.tenants = tenants;
        this.users = users;
        this.licenseAdmin = licenseAdmin.getIfAvailable();
        this.licensing = licensing;
        this.audit = audit;
        this.graphStore = graphStore;
    }

    /**
     * Create a tenant and its initial license atomically, and add the named
     * Keycloak user (by subject) as its first {@code OWNER} membership.
     *
     * <p>The {@code firstOwnerSubject} must already exist as a {@code User}
     * row (the user must have logged in at least once so JIT provisioning
     * created their record). The admin endpoint surfaces a clear 404 if not.
     *
     * @param slug          URL-safe tenant slug, unique platform-wide
     * @param name          display name
     * @param plan          initial license tier
     * @param firstOwnerSubject the Keycloak subject of the user who becomes
     *                          the first tenant {@code OWNER}
     * @return the created tenant
     */
    @Transactional
    public Tenant createTenant(String slug, String name,
                               LicensePlan plan,
                               String firstOwnerSubject) {
        // Checked before anything is written rather than at the issuance line below, so a mode
        // without an issuer cannot leave a half-built tenant behind. Unreachable over HTTP —
        // PlatformAdminController is @ConditionalOnBean(LicenseAdminPort.class) — so this guards
        // programmatic callers only.
        if (licenseAdmin == null) {
            throw new IllegalStateException(
                    "cannot create a tenant: no LicenseAdminPort is registered, so the tenant "
                    + "could not be licensed. This deployment mode does not issue licenses — see "
                    + "aran.licensing.mode.");
        }

        UUID tenantId = UUID.randomUUID();
        Tenant tenant = tenants.save(new Tenant(tenantId, slug, name, Instant.now()));

        // Provision dynamic graph store (Jena Fuseki dataset)
        graphStore.provision(new TenantContext(tenantId.toString()));

        // The first owner must already be a known user (their Keycloak login
        // provisions the User row JIT). Rejecting unknown subjects here keeps
        // the invariant that every tenant has at least one OWNER who can
        // actually log in to it.
        User owner = users.findBySubject(firstOwnerSubject)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "first owner has never signed in to Aran: " + firstOwnerSubject));

        tenants.saveMembership(new Membership(
                UUID.randomUUID(),
                owner.id(),
                tenantId,
                TenantRole.OWNER,
                Instant.now()
        ));

        // License row in the same transaction — no tenant exists without one.
        licenseAdmin.issueInitialLicense(tenantId, plan);

        // Audit, then invalidate any cached entitlements for safety (there
        // shouldn't be any for a brand-new tenant id, but invalidate is cheap).
        audit.tenantCreated(owner.id(), tenantId, name, plan);
        licensing.invalidate(tenantId);

        return tenant;
    }

    /**
     * Add a user to an existing tenant. The user must have logged in at least
     * once. Counts against the license {@code maxUsers} quota.
     */
    @Transactional
    public Membership addMember(UUID tenantId, String subject, TenantRole role) {
        // Same licensing gates as the user-facing path, just via the
        // tenant-id overloads since this admin context has no AuthenticatedUser.
        var entitlements = licensing.entitlementsFor(tenantId);
        if (!entitlements.allowsMutations()) {
            throw new LicenseExpiredException(
                    "tenant license is " + entitlements.status() + "; cannot add members");
        }
        licensing.requireWithinUserLimit(tenantId);

        User user = users.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "user has never signed in to Aran: " + subject));

        Membership membership = tenants.saveMembership(new Membership(
                UUID.randomUUID(),
                user.id(),
                tenantId,
                role,
                Instant.now()
        ));

        audit.tenantMemberAdded(null, tenantId, user.id(), role);
        return membership;
    }
}
