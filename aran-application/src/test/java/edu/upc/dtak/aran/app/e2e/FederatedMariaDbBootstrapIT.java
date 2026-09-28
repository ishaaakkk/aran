package edu.upc.dtak.aran.app.e2e;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;

/**
 * MariaDB is served by {@code MySqlEngine}'s attached-catalog read and quoting on an assumption
 * of wire compatibility ({@code MariaDbEngine}); this class is where that assumption meets a
 * server.
 */
@DisplayName("Federated MariaDB 11 → bootstrap via the downloaded driver; the live read (V52/V67)")
class FederatedMariaDbBootstrapIT extends AbstractFederatedMySqlFamilyIT {

    @Override
    protected String engine() {
        return "MARIADB";
    }

    @Override
    protected String image() {
        return "mariadb:11";
    }

    @Override
    protected Map<String, String> env() {
        return Map.of(
                "MARIADB_ROOT_PASSWORD", ROOT_PASSWORD,
                "MARIADB_DATABASE", DATABASE,
                "MARIADB_USER", USER,
                "MARIADB_PASSWORD", PASSWORD);
    }

    @Override
    protected String client() {
        return "mariadb";
    }
}
