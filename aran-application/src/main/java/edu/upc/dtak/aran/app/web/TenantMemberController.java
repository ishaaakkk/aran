package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.service.MemberConflictException;
import edu.upc.dtak.aran.core.service.MemberDepartureService;
import edu.upc.dtak.aran.core.service.TenantMemberService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A tenant owner administering their own tenant's members.
 *
 * <p>Every route acts on {@code the caller's own tenant} — there is no tenant id in any path,
 * because there is no case where an owner administers a tenant other than theirs. That is the
 * difference from {@code PlatformAdminController}, which takes a tenant id and is gated on a realm
 * role: before this existed, adding a colleague to your own organisation meant asking the Aran
 * operator.
 *
 * <p>Authorisation is the {@code OWNER} role on the caller's membership, checked in
 * {@link TenantMemberService} rather than here — the service is the thing that must not be callable
 * by a member, and a check in the controller would protect only this door.
 */
@RestController
@RequestMapping("/api/tenant/members")
@NullMarked
public class TenantMemberController {

    /**
     * @param role {@code OWNER} or {@code MEMBER}; defaults to {@code MEMBER}, because the
     *             common case is adding a colleague and the safe default is the lesser power
     */
    public record CreateMemberRequest(
            @NotBlank @Email String email,
            @NotBlank String displayName,
            @Nullable String role) {}

    public record ChangeRoleRequest(@NotBlank String role) {}

    /**
     * The password is present exactly once, in the response to the call that created it. Aran
     * stores no copy and cannot show it again — a reset is the only way to get another.
     *
     * @param temporaryPassword a new one on a reinstatement too: no credential from before the
     *                          removal works again
     * @param reinstated        true when the email was of someone this organization removed, whose
     *                          account came back rather than being created (UI-OD9)
     */
    public record CreatedMemberResponse(TenantMemberService.MemberView member,
                                        String temporaryPassword,
                                        boolean reinstated) {}

    /**
     * What a removal took away, and which projects changed hands so none was left unreachable.
     *
     * @param projectsHandedOver projects only the removed member belonged to, now shared with the
     *                           owner who removed them — sorted by name
     */
    public record RemovedMemberResponse(int tokensRevoked, int projectAccessRemoved,
                                        List<MemberDepartureService.ProjectRef> projectsHandedOver) {}

    private final TenantMemberService members;
    private final CurrentUserResolver currentUser;

    public TenantMemberController(TenantMemberService members, CurrentUserResolver currentUser) {
        this.members = members;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<TenantMemberService.MemberView> list() {
        return members.listMembers(currentUser.require());
    }

    @PostMapping
    public ResponseEntity<CreatedMemberResponse> create(
            @Valid @RequestBody CreateMemberRequest body) {
        TenantMemberService.CreatedMember created;
        try {
            created = members.createMember(
                    currentUser.require(), body.email(), body.displayName(), parseRole(body.role()));
        } catch (MemberConflictException e) {
            // 409 with the service's own sentence — "already a member", "belongs to another
            // organization". Before this the identity provider's refusal reached the client as a
            // bare 500 on every retry (audit J7-F2).
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }

        return ResponseEntity
                .created(URI.create("/api/tenant/members/" + created.member().userId()))
                .body(new CreatedMemberResponse(created.member(), created.temporaryPassword(),
                        created.reinstated()));
    }

    @PutMapping("/{userId}/role")
    public TenantMemberService.MemberView changeRole(@PathVariable UUID userId,
                                                     @Valid @RequestBody ChangeRoleRequest body) {
        return members.changeRole(currentUser.require(), userId, parseRole(body.role()));
    }

    /** Returns the new temporary password once — the admin-side half of "I forgot mine". */
    @PostMapping("/{userId}/password")
    public ResponseEntity<CreatedMemberResponse> resetPassword(@PathVariable UUID userId) {
        String password = members.resetPassword(currentUser.require(), userId);
        return ResponseEntity.ok(new CreatedMemberResponse(
                members.listMembers(currentUser.require()).stream()
                        .filter(m -> m.userId().equals(userId))
                        .findFirst()
                        .orElseThrow(),
                password, false));
    }

    /**
     * 200 with what the removal did, rather than 204: the owner is told which projects are now
     * theirs to look after, which a bare "done" could not say.
     */
    @DeleteMapping("/{userId}")
    public RemovedMemberResponse remove(@PathVariable UUID userId) {
        MemberDepartureService.Departure departure =
                members.removeMember(currentUser.require(), userId);
        return new RemovedMemberResponse(departure.tokensRevoked(),
                departure.projectAccessRemoved(), departure.projectsHandedOver());
    }

    private static TenantRole parseRole(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return TenantRole.MEMBER;
        }
        try {
            return TenantRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("role must be OWNER or MEMBER, got: " + raw);
        }
    }
}
