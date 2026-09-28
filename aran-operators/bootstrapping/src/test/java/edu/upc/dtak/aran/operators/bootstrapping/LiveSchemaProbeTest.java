package edu.upc.dtak.aran.operators.bootstrapping;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression;
import edu.upc.dtak.aran.core.domain.drift.FederatedSchemaProbe.ProbeUnavailableException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The drift probe's JDBC half. The happy path needs a live remote and is
 * {@code AbstractFederatedMySqlFamilyIT}'s; what is pinned here is what an unreachable remote
 * does, because conflating "the database was down" with "the schema changed" would fire a
 * breaking alarm on every blip, and people would stop reading them.
 */
class LiveSchemaProbeTest {

    private static final String PASSWORD = "hunter2-sentinel";

    private static RelationalDatabaseRepository closedPort(String engine) {
        return new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "gone", "", engine,
                "127.0.0.1", 1, "nope", "nope", PASSWORD, "public", true);
    }

    @Test
    @DisplayName("an unreachable Postgres raises ProbeUnavailable, never a drift verdict")
    void unreachablePostgresIsNotDrift() {
        assertThatThrownBy(() -> LiveSchemaProbe.describe(
                FederatedScanExpression.forRepository(closedPort("POSTGRESQL"), "nope")))
                .isInstanceOf(ProbeUnavailableException.class);
    }

    @Test
    @DisplayName("an unreachable MySQL fails at its ATTACH: ProbeUnavailable, and the password never in the message")
    void unreachableMysqlIsNotDriftAndLeaksNothing() {
        assertThatThrownBy(() -> LiveSchemaProbe.describe(
                FederatedScanExpression.forRepository(closedPort("MYSQL"), "nope")))
                .isInstanceOf(ProbeUnavailableException.class)
                .hasMessageContaining("(ATTACH)")
                .hasMessageNotContaining(PASSWORD);
    }
}
