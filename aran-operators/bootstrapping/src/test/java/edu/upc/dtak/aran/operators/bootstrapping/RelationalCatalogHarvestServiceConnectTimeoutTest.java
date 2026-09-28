package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connect-timeout fix (Phase 4B Task 5 review): {@link RelationalCatalogHarvestService}'s
 * connect step must be bounded, not just eventually best-effort. {@code
 * DatasetService.registerDataset} calls the harvest inline on a request thread, so an unbounded
 * connect to a blackholed address (packets silently dropped, as opposed to an immediate
 * "connection refused") would hang that request for the OS's own TCP retry schedule
 * (commonly 60-130s) — a mild DoS via enrolling a dataset against a dead/firewalled host.
 *
 * <p>Untagged (no Testcontainers, no live connection at all) so this runs in the offline suite —
 * {@link RelationalCatalogHarvestServiceConnectTimeoutTest} pins the config the fix relies on;
 * {@code RelationalCatalogHarvestServiceTest} (docker-tagged, live PostgreSQL) additionally proves
 * the end-to-end behaviour against a real blackholed address.
 */
class RelationalCatalogHarvestServiceConnectTimeoutTest {

    private static RelationalDatabaseRepository repo() {
        // Host/port are never dialled here — connectionProperties() is a pure function of repo's
        // credentials, no I/O — so a syntactically valid but arbitrary address is fine.
        return new RelationalDatabaseRepository(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "repo", "", "POSTGRESQL", "192.0.2.1", 5432, "db", "user", "pw", "public", false);
    }

    @Test
    @DisplayName("connectionProperties sets a bounded connectTimeout, well under the OS TCP default")
    void connectTimeoutIsSetAndBounded() {
        Properties props = RelationalCatalogHarvestService.connectionProperties(repo());

        String value = props.getProperty("connectTimeout");
        assertThat(value).isNotBlank();
        assertThat(Integer.parseInt(value))
                .describedAs("bounded well under the OS TCP default (commonly 60-130s) so a "
                        + "blackholed host fails fast rather than hanging the caller")
                .isPositive()
                .isLessThanOrEqualTo(30);
        assertThat(Integer.parseInt(value)).isEqualTo(RelationalCatalogHarvestService.CONNECT_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("connectionProperties still carries the credentials — the timeout is additive, not a replacement")
    void credentialsAreStillSet() {
        Properties props = RelationalCatalogHarvestService.connectionProperties(repo());

        assertThat(props.getProperty("user")).isEqualTo("user");
        assertThat(props.getProperty("password")).isEqualTo("pw");
    }

    @Test
    @DisplayName("a repository with no credentials omits user/password but still gets a bounded connectTimeout")
    void noCredentialsStillGetsATimeout() {
        RelationalDatabaseRepository noAuth = new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "repo", "", "POSTGRESQL", "192.0.2.1", 5432, "db", null, null, "public", false);

        Properties props = RelationalCatalogHarvestService.connectionProperties(noAuth);

        assertThat(props.getProperty("user")).isNull();
        assertThat(props.getProperty("password")).isNull();
        assertThat(props.getProperty("connectTimeout"))
                .isEqualTo(String.valueOf(RelationalCatalogHarvestService.CONNECT_TIMEOUT_SECONDS));
    }
}
