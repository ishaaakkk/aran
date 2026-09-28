package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.net.OutboundAddressGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Driver;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The import/extract path connects to a user-supplied host. It must screen the host through the
 * same {@link OutboundAddressGuard} the registration path uses (sec-audit F-S1-1) — otherwise, with
 * private-network blocking on, an internal host or the cloud metadata endpoint is reachable here
 * even though test/scan would refuse it.
 */
class JdbcTableExtractorGuardTest {

    /** A downloader that fails if touched — proving the screen runs before any driver work. */
    private static final DriverDownloader FAILING = new DriverDownloader() {
        @Override public Driver loadDriver(String engine) {
            throw new AssertionError("the outbound screen must run before any driver work");
        }
        @Override public void unloadDriver(String engine) {}
    };

    private static RelationalDatabaseRepository repo(String host) {
        return new RelationalDatabaseRepository(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "src", "", "MYSQL", host, 3306, "db", "u", "p", "public", false);
    }

    @Test
    @DisplayName("extract screens the target host before connecting (F-S1-1)")
    void extractScreensHostBeforeConnecting() {
        JdbcTableExtractor extractor = new JdbcTableExtractor(FAILING, new OutboundAddressGuard(true));
        assertThatThrownBy(() -> extractor.extract(repo("127.0.0.1"), "t", 1_000_000))
                .isInstanceOf(OutboundAddressGuard.BlockedHostException.class);
    }
}
