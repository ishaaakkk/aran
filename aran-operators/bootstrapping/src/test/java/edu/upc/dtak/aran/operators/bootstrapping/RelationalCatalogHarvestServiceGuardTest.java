package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.net.OutboundAddressGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Driver;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog-harvest path connects to a user-supplied host and is best-effort (never throws). It
 * must still screen the host through {@link OutboundAddressGuard} (sec-audit F-S1-1): a blocked
 * host yields no edges rather than a connection to an internal or metadata address.
 */
class RelationalCatalogHarvestServiceGuardTest {

    /** Fails if touched — proving the screen runs before any driver/connect work. */
    private static final DriverDownloader FAILING = new DriverDownloader() {
        @Override public Driver loadDriver(String engine) {
            throw new AssertionError("the outbound screen must run before any driver work");
        }
        @Override public void unloadDriver(String engine) {}
    };

    @Test
    @DisplayName("a blocked host yields an empty harvest without connecting (F-S1-1)")
    void blockedHostHarvestsNothing() {
        RelationalCatalogHarvestService service =
                new RelationalCatalogHarvestService(FAILING, new OutboundAddressGuard(true));
        RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "src", "", "POSTGRESQL", "127.0.0.1", 5432, "db", "u", "p", "public", true);

        assertThat(service.harvest(repo)).isEqualTo(edu.upc.dtak.aran.core.source.Harvest.EMPTY);
    }
}
