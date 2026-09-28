package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.ApiToken;
import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.fake.InMemoryApiTokenRepository;
import edu.upc.dtak.aran.core.fake.RecordingAuditPort;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A token is a credential that lives for months. Everything asserted here is a property the
 * security of that credential rests on, so each is checked as behaviour rather than trusted to
 * the implementation reading correctly.
 */
class ApiTokenServiceTest {

    private static final TenantContext TENANT =
            new TenantContext("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final InMemoryApiTokenRepository repository = new InMemoryApiTokenRepository();
    private final RecordingAuditPort auditTrail = new RecordingAuditPort();
    private final ApiTokenService service =
            new ApiTokenService(repository, new AuditService(auditTrail));

    private ApiTokenService.IssuedToken issue(String name) {
        return service.create(TENANT, USER, name, ApiTokenScope.READ, null);
    }

    // ── the secret ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the plaintext is never stored — only its hash and a display prefix")
    void secretIsNotPersisted() {
        ApiTokenService.IssuedToken issued = issue("notebook");

        ApiToken stored = repository.findByTokenHash(ApiTokenService.hash(issued.secret())).orElseThrow();
        assertThat(stored.tokenHash()).isNotEqualTo(issued.secret());
        assertThat(issued.secret()).doesNotContain(stored.tokenHash());
        // The prefix must be short enough to be useless as a credential.
        assertThat(stored.tokenPrefix()).hasSize(16);
        assertThat(service.authenticate(stored.tokenPrefix()))
                .as("the display prefix must not authenticate anything")
                .isEmpty();
    }

    @Test
    @DisplayName("tokens carry the greppable prefix and real entropy")
    void tokenShape() {
        ApiTokenService.IssuedToken issued = issue("ci");

        // The prefix exists so secret scanners can match on it; without it a leaked token is
        // just an opaque string nobody notices in a commit.
        assertThat(issued.secret()).startsWith("aran_pat_");
        // 32 random bytes, url-safe base64, unpadded.
        assertThat(issued.secret().substring("aran_pat_".length())).hasSize(43);
    }

    @Test
    @DisplayName("every token is distinct")
    void tokensAreUnique() {
        Set<String> secrets = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            secrets.add(issue("token-" + i).secret());
        }
        assertThat(secrets).hasSize(200);
    }

    // ── authentication ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a live token authenticates and records its use")
    void liveTokenAuthenticates() {
        ApiTokenService.IssuedToken issued = issue("notebook");
        assertThat(issued.token().lastUsedAt()).isNull();

        ApiToken authenticated = service.authenticate(issued.secret()).orElseThrow();

        assertThat(authenticated.userId()).isEqualTo(USER);
        assertThat(repository.findById(TENANT, issued.token().id()).orElseThrow().lastUsedAt())
                .as("a token nobody has touched in months should be visible as such")
                .isNotNull();
    }

    @Test
    @DisplayName("a revoked token stops working immediately")
    void revokedTokenFails() {
        ApiTokenService.IssuedToken issued = issue("leaked");
        service.revoke(TENANT, USER, issued.token().id());

        assertThat(service.authenticate(issued.secret())).isEmpty();
    }

    @Test
    @DisplayName("an expired token stops working")
    void expiredTokenFails() {
        ApiTokenService.IssuedToken issued = issue("stale");
        repository.put(new ApiToken(
                issued.token().id(), issued.token().tenantId(), USER, "stale",
                issued.token().tokenHash(), issued.token().tokenPrefix(), ApiTokenScope.READ,
                Instant.now().minus(1, ChronoUnit.DAYS), null, null, Instant.now()));

        assertThat(service.authenticate(issued.secret())).isEmpty();
    }

    @Test
    @DisplayName("revoked and expired are indistinguishable from unknown, so nothing is confirmed")
    void failuresAreIndistinguishable() {
        // Telling an unauthenticated client "that token expired" rather than "unauthorized"
        // confirms the token was real. All three return the same empty result.
        assertThat(service.authenticate("aran_pat_completely-made-up")).isEmpty();
        assertThat(service.authenticate("not-even-our-prefix")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
    }

    @Test
    @DisplayName("a value that is not one of our tokens never reaches the database")
    void nonTokensShortCircuit() {
        // A JWT arriving here must not cost a hash and a query on every request.
        assertThat(service.authenticate("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.abc.def")).isEmpty();
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("read-only is the default scope")
    void defaultScopeIsRead() {
        // A credential that lives for months should not also be able to destroy things.
        assertThat(service.create(TENANT, USER, "default", null, null).token().scope())
                .isEqualTo(ApiTokenScope.READ);
    }

    @Test
    @DisplayName("tokens expire in 90 days unless told otherwise")
    void defaultExpiry() {
        ApiToken token = issue("default-expiry").token();

        assertThat(token.expiresAt()).isNotNull();
        assertThat(ChronoUnit.DAYS.between(token.createdAt(), token.expiresAt())).isEqualTo(90);
    }

    @Test
    @DisplayName("a non-positive expiry means never, which is allowed but not the default")
    void neverExpires() {
        // A scheduled job that dies silently at 90 days is its own kind of outage.
        assertThat(service.create(TENANT, USER, "forever", ApiTokenScope.FULL, 0).token().expiresAt())
                .isNull();
    }

    @Test
    @DisplayName("revoking is idempotent and cannot be undone")
    void revokeIsIdempotent() {
        ApiTokenService.IssuedToken issued = issue("twice");
        service.revoke(TENANT, USER, issued.token().id());
        Instant firstRevocation = repository.findById(TENANT, issued.token().id()).orElseThrow().revokedAt();

        service.revoke(TENANT, USER, issued.token().id());   // must not throw

        assertThat(repository.findById(TENANT, issued.token().id()).orElseThrow().revokedAt())
                .as("a second revoke must not move the timestamp")
                .isEqualTo(firstRevocation);
    }

    @Test
    @DisplayName("one user cannot revoke another's token by guessing its id")
    void cannotRevokeSomeoneElsesToken() {
        ApiTokenService.IssuedToken mine = issue("mine");
        UUID otherUser = UUID.randomUUID();

        assertThatThrownBy(() -> service.revoke(TENANT, otherUser, mine.token().id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No such token.");
        assertThat(service.authenticate(mine.secret()))
                .as("the token must still work")
                .isPresent();
    }

    @Test
    @DisplayName("live token names are unique per user, so a list stays readable")
    void duplicateLiveNamesRejected() {
        issue("laptop");

        assertThatThrownBy(() -> issue("LAPTOP"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already have a live token");
    }

    @Test
    @DisplayName("a revoked name can be reused")
    void revokedNameFreesUp() {
        ApiTokenService.IssuedToken first = issue("laptop");
        service.revoke(TENANT, USER, first.token().id());

        assertThat(issue("laptop").token().name()).isEqualTo("laptop");
    }

    @Test
    @DisplayName("blank and oversized names are rejected")
    void nameValidation() {
        assertThatThrownBy(() -> service.create(TENANT, USER, "  ", ApiTokenScope.READ, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(TENANT, USER, "x".repeat(121), ApiTokenScope.READ, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the list shows revoked and expired tokens, not just live ones")
    void listIncludesDeadTokens() {
        ApiTokenService.IssuedToken live = issue("live");
        ApiTokenService.IssuedToken dead = issue("dead");
        service.revoke(TENANT, USER, dead.token().id());

        assertThat(service.list(TENANT, USER)).extracting(ApiToken::name)
                .containsExactlyInAnyOrder("live", "dead");
        assertThat(live.token().revokedAt()).isNull();
    }

    // ── the owner's door (audit J7-F8) and the audit trail (J7-F1) ───────────

    private static final UUID TENANT_ID = UUID.fromString(TENANT.value());

    private static AuthenticatedUser owner() {
        return new AuthenticatedUser(UUID.randomUUID(), "owner", TENANT_ID, TenantRole.OWNER);
    }

    @Test
    @DisplayName("an owner can revoke a colleague's token, and the trail names both of them")
    void ownerRevokesAColleaguesToken() {
        ApiTokenService.IssuedToken leaked = issue("public-notebook");
        AuthenticatedUser owner = owner();

        service.revokeAsOwner(owner, leaked.token().id());

        assertThat(service.authenticate(leaked.secret()))
                .as("the leaked token must stop working")
                .isEmpty();
        var revoked = auditTrail.withAction("token.revoked");
        assertThat(revoked).hasSize(1);
        assertThat(revoked.getFirst().actorId()).isEqualTo(owner.userId());
        assertThat(revoked.getFirst().targetId()).isEqualTo(leaked.token().id());
        assertThat(revoked.getFirst().metadata())
                .containsEntry("ownerUserId", USER.toString())
                .containsEntry("name", "public-notebook");
    }

    @Test
    @DisplayName("a member cannot use the owner's door to revoke someone else's token")
    void memberCannotRevokeAsOwner() {
        ApiTokenService.IssuedToken mine = issue("mine");
        AuthenticatedUser member = new AuthenticatedUser(UUID.randomUUID(), "m", TENANT_ID,
                TenantRole.MEMBER);

        assertThatThrownBy(() -> service.revokeAsOwner(member, mine.token().id()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.authenticate(mine.secret())).isPresent();
    }

    @Test
    @DisplayName("the owner's door does not reach another tenant's token")
    void ownerCannotReachAnotherTenant() {
        ApiTokenService.IssuedToken elsewhere = issue("elsewhere");
        AuthenticatedUser otherOwner = new AuthenticatedUser(UUID.randomUUID(), "o",
                UUID.randomUUID(), TenantRole.OWNER);

        assertThatThrownBy(() -> service.revokeAsOwner(otherOwner, elsewhere.token().id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.authenticate(elsewhere.secret())).isPresent();
    }

    @Test
    @DisplayName("revoking an already-revoked token as owner records nothing, because nothing happened")
    void ownerRevokeIsIdempotent() {
        ApiTokenService.IssuedToken issued = issue("twice");
        service.revokeAsOwner(owner(), issued.token().id());

        service.revokeAsOwner(owner(), issued.token().id());

        assertThat(auditTrail.withAction("token.revoked")).hasSize(1);
    }

    @Test
    @DisplayName("revokeAllFor revokes only the live tokens and says how many")
    void revokeAllForCountsLiveOnes() {
        issue("a");
        issue("b");
        ApiTokenService.IssuedToken already = issue("c");
        service.revoke(TENANT, USER, already.token().id());

        assertThat(service.revokeAllFor(TENANT, USER)).isEqualTo(2);
        assertThat(repository.findByUser(TENANT, USER)).allMatch(t -> t.revokedAt() != null);
    }

    @Test
    @DisplayName("creating and revoking your own token are both recorded, never with the secret")
    void createAndSelfRevokeAreAudited() {
        ApiTokenService.IssuedToken issued = issue("laptop");
        service.revoke(TENANT, USER, issued.token().id());

        assertThat(auditTrail.withAction("token.created")).singleElement()
                .satisfies(e -> {
                    assertThat(e.actorId()).isEqualTo(USER);
                    assertThat(e.metadata()).containsEntry("name", "laptop")
                            .doesNotContainValue(issued.secret());
                });
        assertThat(auditTrail.withAction("token.revoked")).singleElement()
                .satisfies(e -> assertThat(e.metadata())
                        .containsEntry("ownerUserId", USER.toString()));
    }
}
