package edu.upc.dtak.aran.core.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.upc.dtak.aran.core.domain.ApiToken;
import edu.upc.dtak.aran.core.domain.ApiTokenRepository;
import edu.upc.dtak.aran.core.domain.ApiTokenScope;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issue, list, revoke and authenticate API tokens.
 *
 * <h2>The token</h2>
 *
 * <p>{@code aran_pat_} + 32 bytes of {@link SecureRandom}, URL-safe Base64. The prefix is there to
 * be greppable: secret scanners key on fixed prefixes, so a token pasted into a repository or a
 * ticket is detectable as one. 256 bits of entropy makes guessing irrelevant next to every other
 * way a credential leaks.
 *
 * <h2>Storage</h2>
 *
 * <p>The plaintext is returned exactly once, by {@link #create}, and never persisted. What is
 * stored is a hex SHA-256 and a short display prefix. SHA-256 rather than bcrypt or argon2 is a
 * deliberate departure from password advice and holds only because this is <em>not</em> a
 * password: a 256-bit uniform random value has no structure to attack, so the slow-KDF defence
 * against dictionary search buys nothing, while costing a KDF on every authenticated request.
 */
@Service
@NullMarked
public class ApiTokenService {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenService.class);

    /** How stale the last-used stamp may be. Invisible in a UI that shows "3 days ago". */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final Cache<UUID, Boolean> touchThrottle = Caffeine.newBuilder()
            .expireAfterWrite(TOUCH_INTERVAL)
            .maximumSize(50_000)
            .build();

    /** Greppable by secret scanners, and unmistakable in a log or a config file. */
    public static final String TOKEN_PREFIX = "aran_pat_";

    /** Enough of the token to tell two rows apart in a list; not enough to authenticate. */
    private static final int DISPLAY_PREFIX_LENGTH = 16;

    private static final int TOKEN_BYTES = 32;

    /** Chosen so a forgotten token stops working rather than outliving the person who made it. */
    public static final int DEFAULT_EXPIRY_DAYS = 90;

    /** A newly issued token: the domain row plus the secret, which exists only here. */
    public record IssuedToken(ApiToken token, String secret) {}

    private final ApiTokenRepository repository;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(ApiTokenRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /**
     * Mint a token for {@code userId} in {@code tenant}.
     *
     * @param expiryDays days until expiry; {@code null} uses {@link #DEFAULT_EXPIRY_DAYS}, and a
     *                   value {@code <= 0} means "never expires" — allowed, because a scheduled
     *                   job that dies silently at 90 days is its own kind of outage, but not the
     *                   default
     */
    @Transactional
    public IssuedToken create(TenantContext tenant, UUID userId, String name,
                              ApiTokenScope scope, @Nullable Integer expiryDays) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Token name must not be blank.");
        }
        if (name.length() > 120) {
            throw new IllegalArgumentException("Token name must be 120 characters or fewer.");
        }
        boolean duplicate = repository.findByUser(tenant, userId).stream()
                .anyMatch(t -> t.revokedAt() == null && t.name().equalsIgnoreCase(name.trim()));
        if (duplicate) {
            throw new IllegalArgumentException("You already have a live token named '" + name.trim() + "'.");
        }

        byte[] entropy = new byte[TOKEN_BYTES];
        random.nextBytes(entropy);
        String secret = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        int days = expiryDays == null ? DEFAULT_EXPIRY_DAYS : expiryDays;
        Instant now = Instant.now();
        ApiToken token = new ApiToken(
                UUID.randomUUID(),
                UUID.fromString(tenant.value()),
                userId,
                name.trim(),
                hash(secret),
                secret.substring(0, DISPLAY_PREFIX_LENGTH),
                scope == null ? ApiTokenScope.READ : scope,
                days > 0 ? now.plus(days, ChronoUnit.DAYS) : null,
                null, null, now);

        ApiToken saved = repository.save(tenant, token);
        audit.apiTokenCreated(saved.tenantId(), userId, saved.id(), saved.name(), saved.scope().name());
        return new IssuedToken(saved, secret);
    }

    /** Every token the user owns, live or not — a revoked token is still worth showing. */
    public List<ApiToken> list(TenantContext tenant, UUID userId) {
        return repository.findByUser(tenant, userId);
    }

    /**
     * Revoke a token. Irreversible, and idempotent: revoking an already-revoked token succeeds
     * rather than erroring, because the caller's intent ("this must not work") is satisfied.
     */
    @Transactional
    public void revoke(TenantContext tenant, UUID userId, UUID tokenId) {
        ApiToken token = repository.findById(tenant, tokenId)
                .orElseThrow(() -> new IllegalArgumentException("No such token."));
        // A token belongs to a person, not to a tenant at large: an OWNER must not be able to
        // revoke a colleague's credential through this endpoint by guessing its id.
        if (!token.userId().equals(userId)) {
            throw new IllegalArgumentException("No such token.");
        }
        if (token.revokedAt() == null) {
            repository.save(tenant, token.revoked(Instant.now()));
            audit.apiTokenRevoked(token.tenantId(), userId, token.id(), token.userId(), token.name());
        }
    }

    /**
     * An owner revoking one colleague's token from the admin area (audit J7-F8).
     *
     * <p>The route {@link #revoke} deliberately refuses — it is the holder's own door, and an
     * owner guessing an id must not reach a colleague's credential through it. This is the other
     * door, and it is owner-gated and audited instead: a token leaked in a public notebook has to
     * be stoppable by someone other than the person who is on holiday. Idempotent like
     * {@link #revoke}; an already-revoked token records nothing, because nothing happened.
     *
     * @throws AccessDeniedException     if the caller is not a tenant owner
     * @throws ResourceNotFoundException if no such token exists in the caller's tenant
     */
    @Transactional
    public ApiToken revokeAsOwner(AuthenticatedUser owner, UUID tokenId) {
        if (!owner.canAdministerTenant()) {
            throw new AccessDeniedException("only a tenant owner can revoke another member's token");
        }
        TenantContext tenant = new TenantContext(owner.tenantId().toString());
        ApiToken token = repository.findById(tenant, tokenId)
                .orElseThrow(() -> new ResourceNotFoundException("no such token: " + tokenId));
        if (token.revokedAt() != null) {
            return token;
        }
        ApiToken revoked = repository.save(tenant, token.revoked(Instant.now()));
        audit.apiTokenRevoked(token.tenantId(), owner.userId(), token.id(), token.userId(),
                token.name());
        return revoked;
    }

    /**
     * Revoke every live token {@code userId} holds in this tenant — what removing them from the
     * tenant does (UI-OD9). Revoked, not merely inert: a removed member's tokens used to fail only
     * because the filter found no membership, so re-adding the same email brought every one of
     * them back to life.
     *
     * <p>Not audited per token: the removal that calls this records the count on its own line.
     *
     * @return how many tokens were live and are now revoked
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int revokeAllFor(TenantContext tenant, UUID userId) {
        Instant now = Instant.now();
        int revoked = 0;
        for (ApiToken token : repository.findByUser(tenant, userId)) {
            if (token.revokedAt() == null) {
                repository.save(tenant, token.revoked(now));
                revoked++;
            }
        }
        return revoked;
    }

    /**
     * Resolve a presented secret to a usable token, or empty.
     *
     * <p>Empty covers "no such token", "revoked" and "expired" alike. The caller answers 401 for
     * all three without distinguishing them — telling an unauthenticated client that a token
     * expired confirms it was real, which is a small oracle but a free one to close.
     *
     * <p>Records the use as a side effect, in its own transaction, so a request that later fails
     * still leaves evidence the credential was exercised.
     */
    public Optional<ApiToken> authenticate(String presented) {
        if (presented == null || !presented.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Optional<ApiToken> found = repository.findByTokenHash(hash(presented));
        if (found.isEmpty() || !found.get().isUsable(Instant.now())) {
            return Optional.empty();
        }
        touchThrottled(found.get().id());
        return found;
    }

    /**
     * Record the use, at most once per {@link #TOUCH_INTERVAL} per token.
     *
     * <p>This used to write on every authenticated request. For a token doing anything at volume
     * — a JDBC session fetching frames, a notebook in a loop — that is one database write per
     * request, on the hot path, to maintain a column read on one admin screen. The throttle keeps
     * what the column is for ("nobody has touched this in months") and drops what it cost.
     *
     * <p>Failures are swallowed: a credential that authenticated successfully must not be
     * rejected because its usage stamp could not be written.
     */
    private void touchThrottled(UUID tokenId) {
        if (touchThrottle.getIfPresent(tokenId) != null) {
            return;
        }
        touchThrottle.put(tokenId, Boolean.TRUE);
        try {
            repository.touch(tokenId, Instant.now());
        } catch (Exception e) {
            log.debug("could not stamp last-used for token {}", tokenId, e);
        }
    }

    /** Hex SHA-256 of the token string — what the database stores. */
    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
