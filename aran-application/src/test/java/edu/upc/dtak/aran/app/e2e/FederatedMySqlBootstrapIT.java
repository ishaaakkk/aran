package edu.upc.dtak.aran.app.e2e;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Federated MySQL 8.4 → bootstrap via the downloaded driver; the live read (V52/V67)")
class FederatedMySqlBootstrapIT extends AbstractFederatedMySqlFamilyIT {

    @Override
    protected String engine() {
        return "MYSQL";
    }

    @Override
    protected String image() {
        return "mysql:8.4";
    }

    @Override
    protected Map<String, String> env() {
        return Map.of(
                "MYSQL_ROOT_PASSWORD", ROOT_PASSWORD,
                "MYSQL_DATABASE", DATABASE,
                "MYSQL_USER", USER,
                "MYSQL_PASSWORD", PASSWORD);
    }

    @Override
    protected String client() {
        return "mysql";
    }
}
