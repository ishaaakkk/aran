package edu.upc.dtak.aran.operators.bootstrapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The MATERIALIZED Iceberg copy authenticates an {@code http(s)} data plane the way the live
 * path does: with the catalog's own bearer token. A catalog that serves its files over HTTP —
 * another Aran's produce surface does — answered every read of the copy with a 401, because the
 * extractor bound only an S3 secret.
 *
 * <p>The server here is a stand-in that records the {@code Authorization} header of whatever the
 * real DuckDB {@code iceberg_scan} requests and refuses without the token; the copy then fails on
 * the stand-in's content either way, which is not what is under test.
 */
@DisplayName("DuckDbIcebergTableExtractor sends the catalog's bearer token for http(s) files")
class DuckDbIcebergTableExtractorTest {

    private static final String TOKEN = "aran_pat_extract_test";

    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer files;

    @BeforeEach
    void startFileServer() throws Exception {
        try (Connection probe = DuckDbSession.open(); Statement st = probe.createStatement()) {
            st.execute("INSTALL httpfs");
            st.execute("INSTALL iceberg");
        } catch (SQLException unavailable) {
            Assumptions.abort("httpfs/iceberg cannot be installed here: " + unavailable.getMessage());
        }
        files = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        files.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(auth == null ? "<none>" : auth);
            exchange.sendResponseHeaders(("Bearer " + TOKEN).equals(auth) ? 404 : 401, -1);
            exchange.close();
        });
        files.start();
    }

    @AfterEach
    void stopFileServer() {
        if (files != null) {
            files.stop(0);
        }
    }

    private DuckDbIcebergTableExtractor extractorServing(String metadataLocation) {
        IcebergCatalogClient catalog = new IcebergCatalogClient() {
            @Override public Map<String, String> config(IcebergRestRepository repository) {
                return Map.of();
            }

            @Override public List<String> listTables(IcebergRestRepository repository) {
                return List.of("products.people");
            }

            @Override public IcebergTable loadTable(IcebergRestRepository repository, String table) {
                return new IcebergTable(metadataLocation, List.of());
            }
        };
        return new DuckDbIcebergTableExtractor(Optional.of(catalog));
    }

    private static IcebergRestRepository repository(String token) {
        return new IcebergRestRepository(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "other-aran", "", "http://127.0.0.1:1/api/iceberg", "", token, "S3", "", "", "", "");
    }

    @Test
    @DisplayName("an http metadata location is read with Authorization: Bearer <catalog token>")
    void httpFilesCarryTheCatalogToken() {
        String location = "http://127.0.0.1:" + files.getAddress().getPort()
                + "/api/iceberg/files/people/s1/metadata/v1.metadata.json";

        assertThatThrownBy(() -> extractorServing(location)
                .extract(repository(TOKEN), "products.people", 64L << 20))
                .hasMessageContaining("failed to copy Iceberg table 'products.people'")
                .hasMessageNotContaining(TOKEN);

        assertThat(authorizations).as("what DuckDB sent the file server").isNotEmpty()
                .allMatch(("Bearer " + TOKEN)::equals);
    }

    @Test
    @DisplayName("a repository without a token binds no bearer — the read goes out unauthenticated")
    void noTokenNoBearer() {
        String location = "http://127.0.0.1:" + files.getAddress().getPort()
                + "/api/iceberg/files/people/s1/metadata/v1.metadata.json";

        assertThatThrownBy(() -> extractorServing(location)
                .extract(repository(""), "products.people", 64L << 20))
                .hasMessageContaining("failed to copy Iceberg table 'products.people'");

        assertThat(authorizations).isNotEmpty().allMatch("<none>"::equals);
    }
}
