package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.ApiToken;
import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.ApiTokenService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A user's own API tokens.
 *
 * <p>Scoped to the caller throughout — even an OWNER cannot reach a colleague's token through
 * these routes. A token is a personal credential, and a door here that could revoke a colleague's
 * would lock out a teammate through a UI nobody expects to be dangerous. The owner's lever is a
 * separate, owner-gated and audited route, {@code DELETE /api/tenant/admin/tokens/{id}} (audit
 * J7-F8), and removing someone's membership revokes all of theirs (UI-OD9).
 */
@RestController
@RequestMapping("/api/tokens")
public class ApiTokenController {

    /** Body of {@code POST /api/tokens}. */
    public record CreateTokenRequest(
            @NotBlank String name,
            @Nullable String scope,
            @Nullable Integer expiryDays) {}

    /**
     * A token as the API describes it — never the secret.
     *
     * @param prefix the displayable head, so two tokens are distinguishable in a list
     * @param status {@code active}, {@code expired} or {@code revoked}, computed here so every
     *               client agrees on what a row means
     */
    public record ApiTokenDto(
            UUID id, String name, String prefix, String scope, String status,
            @Nullable Instant expiresAt, @Nullable Instant lastUsedAt,
            @Nullable Instant revokedAt, Instant createdAt) {

        static ApiTokenDto from(ApiToken t) {
            String status = t.revokedAt() != null ? "revoked"
                    : t.isUsable(Instant.now()) ? "active" : "expired";
            return new ApiTokenDto(t.id(), t.name(), t.tokenPrefix(), t.scope().name(), status,
                    t.expiresAt(), t.lastUsedAt(), t.revokedAt(), t.createdAt());
        }
    }

    /**
     * The response to creating a token — the only time {@code secret} is ever populated.
     *
     * <p>A separate type from {@link ApiTokenDto} on purpose: the secret cannot be returned by
     * accident from the list endpoint if the listed shape has no field to put it in.
     */
    public record CreatedTokenDto(ApiTokenDto token, String secret) {}

    private final ApiTokenService tokens;
    private final CurrentUserResolver currentUser;

    public ApiTokenController(ApiTokenService tokens, CurrentUserResolver currentUser) {
        this.tokens = tokens;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ApiTokenDto> list() {
        AuthenticatedUser caller = currentUser.require();
        return tokens.list(tenant(caller), caller.userId()).stream().map(ApiTokenDto::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedTokenDto create(@RequestBody CreateTokenRequest body) {
        AuthenticatedUser caller = currentUser.require();
        ApiTokenScope scope = parseScope(body.scope());
        ApiTokenService.IssuedToken issued = tokens.create(
                tenant(caller), caller.userId(), body.name(), scope, body.expiryDays());
        return new CreatedTokenDto(ApiTokenDto.from(issued.token()), issued.secret());
    }

    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID tokenId) {
        AuthenticatedUser caller = currentUser.require();
        tokens.revoke(tenant(caller), caller.userId(), tokenId);
        return ResponseEntity.noContent().build();
    }

    private static ApiTokenScope parseScope(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return ApiTokenScope.READ;
        }
        try {
            return ApiTokenScope.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("scope must be READ or FULL, got: " + raw);
        }
    }

    private static TenantContext tenant(AuthenticatedUser caller) {
        return new TenantContext(caller.tenantId().toString());
    }
}
