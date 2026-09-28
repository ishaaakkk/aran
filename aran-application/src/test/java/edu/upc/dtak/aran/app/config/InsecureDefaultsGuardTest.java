package edu.upc.dtak.aran.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Audit finding H-2 — the guard that refuses development credentials in a deployment.
 *
 * <p>These are plain {@link MockEnvironment} tests rather than a Spring context: the guard's
 * whole job is a decision over resolved property values, and a context test would need Docker
 * for the datasource, which would put the check behind the one gate this repo cannot run.
 */
class InsecureDefaultsGuardTest {

    private static MockEnvironment secureEnvironment() {
        var env = new MockEnvironment();
        env.setProperty("spring.datasource.password", "a-real-password");
        env.setProperty("spring.security.oauth2.client.registration.keycloak.client-secret", "s3cret");
        env.setProperty("aran.identity.keycloak.client-secret", "another-real-one");
        env.setProperty("aran.lake.secret", "not-the-default");
        env.setProperty("aran.freyja.secret", "not-the-default-either");
        env.setProperty("aran.fuseki.username", "aran-service");
        env.setProperty("aran.fuseki.password", "fuseki-real-password");
        env.setProperty("aran.security.allowed-origins", "https://aran.example.org");
        return env;
    }

    @Test
    @DisplayName("a fully configured deployment starts")
    void secureConfigurationPasses() {
        assertThatCode(() -> new InsecureDefaultsGuard(secureEnvironment())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("each guarded default on its own is enough to refuse startup")
    void everyGuardedDefaultIsDetectedIndividually() {
        Map<String, String> defaults = Map.of(
                "spring.datasource.password", "aran",
                "spring.security.oauth2.client.registration.keycloak.client-secret", "change-me",
                "aran.identity.keycloak.client-secret", "dev-backend-secret-change-in-prod",
                "aran.lake.secret", "dev-shared-secret-change-in-prod",
                "aran.freyja.secret", "dev-freyja-secret-change-in-prod",
                "aran.fuseki.username", "admin",
                "aran.fuseki.password", "admin",
                "aran.security.allowed-origins", "*",
                "ARAN_ENCRYPTION_KEY", "standalone-encryption-key-change-me");

        // Asserted one at a time: a loop over all nine would still pass if the guard only
        // ever noticed the first.
        defaults.forEach((property, insecureValue) -> {
            var env = secureEnvironment();
            env.setProperty(property, insecureValue);

            assertThatThrownBy(() -> new InsecureDefaultsGuard(env))
                    .as("guard must refuse %s=%s", property, insecureValue)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(property);
        });
    }

    @Test
    @DisplayName("compose.standalone.yml's own lake default is guarded too")
    void standaloneLakeSecretIsGuarded() {
        var env = secureEnvironment();
        env.setProperty("aran.lake.secret", "standalone-secret-change-me");

        assertThatThrownBy(() -> new InsecureDefaultsGuard(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aran.lake.secret");
    }

    /**
     * CLIENTS-5. {@code CredentialEncryptionConverter} refuses only an UNSET key — a key set to
     * the literal {@code compose.standalone.yml} publishes decrypts every stored credential with
     * a value anyone can read out of this repository, and nothing said a word.
     */
    @Test
    @DisplayName("compose.standalone.yml's published encryption key is refused")
    void standaloneEncryptionKeyIsGuarded() {
        var env = secureEnvironment();
        env.setProperty("ARAN_ENCRYPTION_KEY", "standalone-encryption-key-change-me");

        assertThatThrownBy(() -> new InsecureDefaultsGuard(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARAN_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("the converter's own fallback key is refused when set explicitly")
    void developmentEncryptionKeyIsGuarded() {
        var env = secureEnvironment();
        env.setProperty("ARAN_ENCRYPTION_KEY", "aran-development-default-key-32c");

        assertThatThrownBy(() -> new InsecureDefaultsGuard(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARAN_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("a rotated encryption key starts")
    void rotatedEncryptionKeyPasses() {
        var env = secureEnvironment();
        env.setProperty("ARAN_ENCRYPTION_KEY", "8f3c1e9a-rotated-for-this-deployment");

        assertThatCode(() -> new InsecureDefaultsGuard(env)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the escape hatch permits the defaults instead of failing")
    void allowFlagPermitsDefaults() {
        var env = secureEnvironment();
        env.setProperty("aran.lake.secret", "dev-shared-secret-change-in-prod");
        env.setProperty(InsecureDefaultsGuard.ALLOW_PROPERTY, "true");

        assertThatCode(() -> new InsecureDefaultsGuard(env)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the failure names every offender, not only the first")
    void messageListsAllOffenders() {
        var env = secureEnvironment();
        env.setProperty("aran.lake.secret", "dev-shared-secret-change-in-prod");
        env.setProperty("aran.fuseki.password", "admin");

        assertThatThrownBy(() -> new InsecureDefaultsGuard(env))
                .hasMessageContaining("aran.lake.secret")
                .hasMessageContaining("aran.fuseki.password")
                // The variable to set is the actionable half of the message.
                .hasMessageContaining("ARAN_LAKE_SECRET")
                .hasMessageContaining("ARAN_FUSEKI_PASSWORD");
    }

    @Test
    @DisplayName("an unset property is not an insecure default")
    void absentPropertiesAreIgnored() {
        // Set.of(...).contains(null) throws NPE; an empty environment must simply pass.
        assertThatCode(() -> new InsecureDefaultsGuard(new MockEnvironment()))
                .doesNotThrowAnyException();
        assertThat(InsecureDefaultsGuard.findOffenders(new MockEnvironment())).isEmpty();
    }
}
