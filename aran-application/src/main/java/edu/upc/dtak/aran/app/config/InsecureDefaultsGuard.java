package edu.upc.dtak.aran.app.config;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when a security-critical setting still carries its development default.
 *
 * <p>Audit finding H-2: seven credentials in {@code application.yml} fall back to values like
 * {@code aran}, {@code change-me}, {@code admin} and {@code dev-…-change-in-prod}. None of them
 * failed — a deployment that forgot an environment variable came up with weak credentials and
 * said nothing. {@code compose.standalone.yml} adds its own
 * ({@code standalone-secret-change-me}, {@code standalone-encryption-key-change-me}), so
 * shipping the one-command topology is not an excuse to skip the check; those literals are
 * guarded here too.
 *
 * <p><b>This is the only such guard.</b> A second one — {@code ProductionSecretsValidator},
 * which warned on six of the same settings and refused only under a {@code prod} profile —
 * was deleted in the T4 audit tranche (AUTH_IDENTITY-4). Its sentinel list was a strict subset
 * of this one, so nothing moved across; what it cost was two classes disagreeing about when the
 * same value is acceptable, and a second escape hatch to remember.
 *
 * <p><b>Why an explicit property rather than the profile name.</b> The pattern this replaces,
 * {@code CredentialEncryptionConverter}, reads {@code SPRING_PROFILES_ACTIVE} and tests
 * {@code contains("dev") || contains("test")}. Finding F3 records the trap: any profile whose
 * name happens to contain neither string aborts startup, which is exactly what the {@code e2e}
 * profile hit and had to work around. An escape hatch keyed off a *declared* property —
 * {@code aran.security.allow-insecure-defaults} — cannot be triggered or missed by accident of
 * naming, and a new profile inherits the safe behaviour rather than a string-matching quirk.
 *
 * <p>Running in the constructor is deliberate: bean creation happens during context refresh,
 * before the embedded server binds its port, so a misconfigured deployment never serves a
 * single request.
 */
@Component
@NullMarked
class InsecureDefaultsGuard {

    private static final Logger log = LoggerFactory.getLogger(InsecureDefaultsGuard.class);

    static final String ALLOW_PROPERTY = "aran.security.allow-insecure-defaults";

    /**
     * A setting, the variable that overrides it, and the values that must not reach a
     * deployment. Values are matched exactly: a password that merely *resembles* a default is
     * someone's real choice, and refusing it would train people to set the flag.
     */
    record Guarded(String property, String envVar, Set<String> insecureValues) {}

    private static final List<Guarded> GUARDED = List.of(
            new Guarded("spring.datasource.password", "ARAN_DB_PASSWORD", Set.of("aran")),
            new Guarded("spring.security.oauth2.client.registration.keycloak.client-secret",
                    "ARAN_OIDC_CLIENT_SECRET", Set.of("change-me")),
            new Guarded("aran.identity.keycloak.client-secret", "ARAN_KEYCLOAK_ADMIN_CLIENT_SECRET",
                    Set.of("dev-backend-secret-change-in-prod")),
            new Guarded("aran.lake.secret", "ARAN_LAKE_SECRET",
                    Set.of("dev-shared-secret-change-in-prod", "standalone-secret-change-me")),
            new Guarded("aran.freyja.secret", "ARAN_FREYJA_SECRET",
                    Set.of("dev-freyja-secret-change-in-prod")),
            new Guarded("aran.fuseki.username", "ARAN_FUSEKI_USERNAME", Set.of("admin")),
            new Guarded("aran.fuseki.password", "ARAN_FUSEKI_PASSWORD", Set.of("admin")),
            new Guarded("aran.security.allowed-origins", "ARAN_ALLOWED_ORIGINS", Set.of("*")),
            // CLIENTS-5. The one entry named by its ENVIRONMENT VARIABLE rather than a property,
            // because there is no property: CredentialEncryptionConverter is a JPA
            // @Converter instantiated by Hibernate, outside the Spring container, so it reads
            // System.getenv directly and no application.yml binding can reach it. Spring's
            // Environment resolves the variable name as given, so guarding it here works.
            //
            // Its own check refuses only an UNSET key. A key SET to one of these two published
            // literals — compose.standalone.yml's default, or the converter's dev fallback
            // written out by hand — encrypts every repository credential at rest with a value
            // anyone can read out of this repository, and passes in silence.
            new Guarded("ARAN_ENCRYPTION_KEY", "ARAN_ENCRYPTION_KEY",
                    Set.of("standalone-encryption-key-change-me", "aran-development-default-key-32c")));

    InsecureDefaultsGuard(Environment environment) {
        List<Guarded> offenders = findOffenders(environment);
        if (offenders.isEmpty()) {
            return;
        }

        String detail = offenders.stream()
                .map(g -> "  - %s — set %s".formatted(g.property(), g.envVar()))
                .collect(Collectors.joining("\n"));

        if (!environment.getProperty(ALLOW_PROPERTY, Boolean.class, false)) {
            throw new IllegalStateException("""
                    Refusing to start: %d security-critical setting(s) still hold their \
                    development default.
                    %s
                    Set each variable, or set %s=true to accept them (dev, e2e and tests do).\
                    """.formatted(offenders.size(), detail, ALLOW_PROPERTY));
        }

        // Warned even when allowed: the list is the useful part, and a developer who has opted
        // in still benefits from seeing exactly which credentials are public knowledge.
        log.warn("{} security-critical setting(s) are using development defaults, permitted by "
                + "{}=true. DO NOT deploy this configuration.\n{}",
                offenders.size(), ALLOW_PROPERTY, detail);
    }

    /** Package-private so the test can assert the detection independently of the throw. */
    static List<Guarded> findOffenders(Environment environment) {
        return GUARDED.stream()
                .filter(g -> {
                    String actual = environment.getProperty(g.property());
                    // Set.of(...).contains(null) throws NPE rather than returning false.
                    return actual != null && g.insecureValues().contains(actual);
                })
                .toList();
    }
}
