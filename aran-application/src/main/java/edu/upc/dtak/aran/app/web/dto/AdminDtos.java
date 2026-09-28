package edu.upc.dtak.aran.app.web.dto;

import edu.upc.dtak.aran.core.domain.LicenseEntitlements;
import edu.upc.dtak.aran.core.domain.LicenseFeature;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.LicenseStatus;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * DTOs for the licensing- and tenant-administration endpoints.
 *
 * <p>Two distinct surfaces share this file:
 * <ul>
 *   <li>request DTOs for the {@code /api/admin/**} platform-admin paths
 *       (tenant creation, admin-driven member additions);</li>
 *   <li>response DTOs for the user-facing {@code /api/me} license display
 *       and the tenant-owner {@code /api/tenants/{id}/members} paths.</li>
 * </ul>
 *
 * <p>Kept together because they share value types ({@link LicensePlan},
 * {@link TenantRole}) and a tenant/license response shape that several
 * endpoints reuse.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    // --- Requests -----------------------------------------------------------

    /** Body for {@code POST /api/admin/tenants}. */
    public record CreateTenantRequest(
            @NotBlank
            @Pattern(regexp = "^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$",
                    message = "slug must be lowercase alphanumeric with optional hyphens, 1–64 chars")
            String slug,

            @NotBlank
            @Size(max = 256)
            String name,

            @NotNull
            LicensePlan plan,

            @NotBlank
            String firstOwnerSubject
    ) {
    }

    /** Body for {@code POST /api/admin/tenants/{id}/members}. */
    public record AdminAddMemberRequest(
            @NotBlank String subject,
            @NotNull  TenantRole role
    ) {
    }

    /** Body for {@code POST /api/tenants/{id}/members} (tenant-OWNER path). */
    public record AddTenantMemberRequest(
            @NotBlank String subject,
            @NotNull  TenantRole role
    ) {
    }

    // --- Responses ----------------------------------------------------------

    public record TenantResponse(
            UUID id,
            String slug,
            String name,
            Instant createdAt
    ) {
        public static TenantResponse from(Tenant t) {
            return new TenantResponse(t.id(), t.slug(), t.name(), t.createdAt());
        }
    }

    /**
     * Response view of the active license + current usage for a tenant —
     * surfaced to tenant {@code OWNER}s so they can see what they have and
     * how close they are to limits.
     */
    public record LicenseSummaryResponse(
            UUID tenantId,
            LicensePlan plan,
            LicenseStatus status,
            boolean mutationsAllowed,
            int maxUsers,
            long currentUsers,
            int maxProjects,
            long currentProjects,
            Set<LicenseFeature> features,
            Instant expiresAt
    ) {
        public static LicenseSummaryResponse from(LicenseEntitlements e,
                                                  long currentUsers,
                                                  long currentProjects) {
            return new LicenseSummaryResponse(
                    e.tenantId(), e.plan(), e.status(), e.allowsMutations(),
                    e.maxUsers(), currentUsers,
                    e.maxProjects(), currentProjects,
                    e.features(), e.expiresAt()
            );
        }
    }
}
