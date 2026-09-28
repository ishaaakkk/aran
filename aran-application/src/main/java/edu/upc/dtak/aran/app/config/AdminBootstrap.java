package edu.upc.dtak.aran.app.config;

import edu.upc.dtak.aran.core.domain.IdentityProviderPort;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates the single tenant and its first owner on a first start, so a fresh install has somebody
 * who can log in and administer it.
 *
 * <h2>The password depends on the profile, and only one half of that is convenience</h2>
 *
 * <p>In {@code dev} the credential is the published {@code alice}/{@code aran}, so
 * install-and-go costs nothing and the e2e suite keeps working.
 *
 * <p>Everywhere else the password is generated and printed <b>once</b> to the log, and Keycloak is
 * told to force a change at first login. Forcing the change alone would not have been enough: a
 * published default is valid from first boot until somebody changes it, and an instance reachable
 * on a network before its operator's first login has a documented admin credential for that whole
 * window. Not publishing the password is what closes that; the forced change is the second layer.
 * It is the same pattern as Keycloak's own bootstrap admin.
 *
 * <h2>Idempotent across two systems</h2>
 *
 * <p>Runs only when there is no tenant at all. The Keycloak account is looked up before being
 * created, because on a dev machine the realm import already contains it and creating blindly
 * would fail on every restart.
 *
 * <p>The seeded owner is an ordinary row: once a real admin exists, it can be deleted from the
 * admin UI like anyone else.
 */
@Component
@NullMarked
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private static final String PASSWORD_ALPHABET =
            "abcdefghijkmnpqrstuvwxyzACDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int PASSWORD_LENGTH = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The dev credential, matching the realm import. Never used outside the dev profile. */
    private static final String DEV_PASSWORD = "aran";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final IdentityProviderPort identityProvider;
    private final Environment environment;
    private final String tenantSlug;
    private final String tenantName;
    private final String adminUsername;
    private final String adminEmail;
    private final String adminDisplayName;
    private final String configuredPassword;

    public AdminBootstrap(
            TenantRepository tenants,
            UserRepository users,
            IdentityProviderPort identityProvider,
            Environment environment,
            @Value("${aran.bootstrap.tenant-slug:default}") String tenantSlug,
            @Value("${aran.bootstrap.tenant-name:Default}") String tenantName,
            @Value("${aran.bootstrap.admin-username:alice}") String adminUsername,
            @Value("${aran.bootstrap.admin-email:alice@aran.local}") String adminEmail,
            @Value("${aran.bootstrap.admin-display-name:Aran Administrator}") String adminDisplayName,
            @Value("${aran.bootstrap.admin-password:}") String configuredPassword) {
        this.tenants = tenants;
        this.users = users;
        this.identityProvider = identityProvider;
        this.environment = environment;
        this.tenantSlug = tenantSlug;
        this.tenantName = tenantName;
        this.adminUsername = adminUsername;
        this.adminEmail = adminEmail;
        this.adminDisplayName = adminDisplayName;
        this.configuredPassword = configuredPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (isDev() || tenants.findBySlug(tenantSlug).isPresent()) {
            return;
        }

        Provisioned provisioned = provisionAdminAccount();

        Tenant tenant = tenants.save(new Tenant(
                UUID.randomUUID(), tenantSlug, tenantName, Instant.now()));

        User admin = users.findBySubject(provisioned.subject())
                .orElseGet(() -> users.save(new User(UUID.randomUUID(), provisioned.subject(),
                        adminEmail, adminDisplayName, Instant.now())));

        tenants.saveMembership(new Membership(
                UUID.randomUUID(), admin.id(), tenant.id(), TenantRole.OWNER, Instant.now()));

        announce(provisioned);
    }

    private Provisioned provisionAdminAccount() {
        Optional<String> existing = identityProvider.findSubjectByUsername(adminUsername);
        if (existing.isPresent()) {
            // Outside dev this branch only runs on an empty database — a fresh install — and
            // there an already-existing account is the realm import's seed, carrying the
            // PUBLISHED dev password. Leaving it valid is exactly the window the class javadoc
            // says must not exist, so the credential is rotated to a generated (or configured)
            // one and printed once, the same as a created account. This branch used to keep
            // the existing credential; V8's first live run showed what that actually shipped.
            String subject = existing.get();
            String password = choosePassword();
            identityProvider.setTemporaryPassword(subject, password);
            return new Provisioned(subject, password);
        }

        String subject = identityProvider.createUser(adminUsername, adminEmail, adminDisplayName);
        String password = choosePassword();
        identityProvider.setTemporaryPassword(subject, password);
        return new Provisioned(subject, password);
    }

    private String choosePassword() {
        if (!configuredPassword.isBlank()) {
            return configuredPassword;
        }
        return isDev() ? DEV_PASSWORD : generatePassword();
    }

    private boolean isDev() {
        for (String profile : environment.getActiveProfiles()) {
            if ("dev".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The one place the generated password appears. Logged rather than returned because there is
     * no caller — this runs at startup, before anyone can be logged in to be told.
     */
    private void announce(Provisioned provisioned) {
        if (provisioned.password() == null) {
            log.info("Bootstrapped tenant '{}' and made the existing account '{}' its owner.",
                    tenantSlug, adminUsername);
            return;
        }
        if (isDev() || !configuredPassword.isBlank()) {
            log.info("Bootstrapped tenant '{}' with owner '{}'.", tenantSlug, adminUsername);
            return;
        }
        log.warn("""

                ┌────────────────────────────────────────────────────────────────────┐
                │  Aran first-run administrator                                      │
                │                                                                    │
                │    username: {}
                │    password: {}
                │                                                                    │
                │  This is shown ONCE and is not stored anywhere. You will be asked  │
                │  to change it at first login.                                      │
                └────────────────────────────────────────────────────────────────────┘
                """, adminUsername, provisioned.password());
    }

    private static String generatePassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** @param password {@code null} when the account already existed and was left alone. */
    private record Provisioned(String subject, @org.jspecify.annotations.Nullable String password) {}
}
