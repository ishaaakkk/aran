package edu.upc.dtak.aran.adapter.iceberg;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The REST catalog client against a real HTTP server (in-process, loopback) speaking the
 * protocol's JSON — the parts that are easy to get subtly wrong: the config prefix, the
 * unit-separator namespace encoding, the v2 {@code schemas}/{@code current-schema-id}
 * indirection, and the bearer header.
 */
@DisplayName("HttpIcebergCatalogClient — the REST protocol's read subset")
class HttpIcebergCatalogClientTest {

    private static HttpServer server;
    private static String baseUri;
    private static final List<String> requests = new ArrayList<>();
    private static volatile String authHeaderSeen;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/catalog", HttpIcebergCatalogClientTest::handle);
        server.createContext("/dotted", HttpIcebergCatalogClientTest::handleSeparatorCatalog);
        server.start();
        baseUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/catalog";
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        requests.clear();
        authHeaderSeen = null;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String pathAndQuery = exchange.getRequestURI().toString();
        requests.add(pathAndQuery);
        authHeaderSeen = exchange.getRequestHeaders().getFirst("Authorization");

        String body;
        int status = 200;
        // Raw (still percent-encoded) halves: the unit separator travels as %1F and the point of
        // this stub is to see exactly what went on the wire, not what a decoder made of it.
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        if (path.endsWith("/v1/config")) {
            body = """
                    {"defaults":{"clients":"4"},"overrides":{"prefix":"prod"}}""";
        } else if (path.endsWith("/v1/prod/namespaces") && query == null) {
            body = """
                    {"namespaces":[["db"]]}""";
        } else if (path.endsWith("/v1/prod/namespaces") && query.startsWith("parent=db%1Fnested")) {
            body = """
                    {"namespaces":[]}""";
        } else if (path.endsWith("/v1/prod/namespaces") && query.startsWith("parent=db")) {
            body = """
                    {"namespaces":[["db","nested"]]}""";
        } else if (path.endsWith("/v1/prod/namespaces/db/tables")) {
            body = """
                    {"identifiers":[{"namespace":["db"],"name":"users"}]}""";
        } else if (path.endsWith("/v1/prod/namespaces/db%1Fnested/tables")) {
            body = """
                    {"identifiers":[{"namespace":["db","nested"],"name":"deep"}]}""";
        } else if (path.endsWith("/v1/prod/namespaces/db/tables/users")) {
            body = """
                    {"metadata-location":"s3://lake/db/users/metadata/00007.metadata.json",
                     "metadata":{
                       "format-version":2,
                       "current-schema-id":1,
                       "current-snapshot-id":77,
                       "snapshots":[
                         {"snapshot-id":42,"summary":{"operation":"append","total-records":"1"}},
                         {"snapshot-id":77,"summary":{"operation":"append","total-records":"44000000"}}],
                       "schemas":[
                         {"schema-id":0,"type":"struct","fields":[
                           {"id":1,"name":"stale","required":false,"type":"string"}]},
                         {"schema-id":1,"type":"struct","fields":[
                           {"id":1,"name":"id","required":true,"type":"long"},
                           {"id":2,"name":"payload","required":false,
                            "type":{"type":"struct","fields":[]}}]}]}}""";
        } else {
            status = 404;
            body = """
                    {"error":{"message":"NoSuchTable","type":"NoSuchTableException","code":404}}""";
        }
        respond(exchange, status, body);
    }

    /** The separator this stub advertises; the tests below vary it per request chain. */
    private static volatile String advertisedSeparator = "%2E";

    /**
     * A catalog shaped like the reference {@code apache/iceberg-rest-fixture} (Iceberg 1.10+,
     * Jetty 12): it advertises {@code namespace-separator}, and — like Jetty's "Suspicious Path
     * Character" — refuses a control character in a path outright, so only the advertised
     * separator can reach a nested namespace.
     */
    private static void handleSeparatorCatalog(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI().toString());
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        if (path.contains("%1F") || path.contains("%1f")) {
            respond(exchange, 400, "Suspicious Path Character");
            return;
        }
        String body;
        if (path.endsWith("/v1/config")) {
            body = "{\"defaults\":{},\"overrides\":{\"namespace-separator\":\""
                    + advertisedSeparator + "\"}}";
        } else if (path.endsWith("/v1/namespaces") && query == null) {
            body = """
                    {"namespaces":[["db"]]}""";
        } else if (path.endsWith("/v1/namespaces") && query.equals("parent=db")) {
            body = """
                    {"namespaces":[["db","inner"]]}""";
        } else if (path.endsWith("/v1/namespaces")) {
            body = """
                    {"namespaces":[]}""";
        } else if (path.endsWith("/v1/namespaces/db/tables")) {
            body = """
                    {"identifiers":[]}""";
        } else if (path.endsWith("/v1/namespaces/db%2Einner/tables")) {
            body = """
                    {"identifiers":[{"namespace":["db","inner"],"name":"deep"}]}""";
        } else {
            respond(exchange, 404, """
                    {"error":{"message":"NoSuchNamespace","type":"NoSuchNamespaceException","code":404}}""");
            return;
        }
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static IcebergRestRepository repo(String token) {
        return new IcebergRestRepository(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "lakehouse", "", baseUri, "prod-wh", token,
                "S3", "", "", "", "");
    }

    private final HttpIcebergCatalogClient client = new HttpIcebergCatalogClient();

    @Test
    @DisplayName("config merges defaults and overrides and carries the warehouse parameter")
    void configMergesAndSendsWarehouse() {
        Map<String, String> config = client.config(repo("tok"));

        assertThat(config).containsEntry("clients", "4").containsEntry("prefix", "prod");
        assertThat(requests.getFirst()).contains("warehouse=prod-wh");
    }

    @Test
    @DisplayName("the bearer token rides the Authorization header; a blank token sends none")
    void bearerHeader() {
        client.config(repo("catalog-token"));
        assertThat(authHeaderSeen).isEqualTo("Bearer catalog-token");

        client.config(repo(""));
        assertThat(authHeaderSeen).isNull();
    }

    @Test
    @DisplayName("listTables honours the config prefix and walks nested namespaces")
    void listTablesWalksNamespaces() {
        List<String> tables = client.listTables(repo("tok"));

        assertThat(tables).containsExactly("db.users", "db.nested.deep");
        // Every data call carried the prefix the catalog demanded.
        assertThat(requests.stream().filter(r -> r.contains("/v1/") && !r.contains("/config")))
                .allSatisfy(r -> assertThat(r).contains("/v1/prod/"));
    }

    @Test
    @DisplayName("a nested namespace travels joined by the separator the catalog advertises, not %1F")
    void nestedNamespacesUseTheAdvertisedSeparator() {
        advertisedSeparator = "%2E";
        IcebergRestRepository dotted = new IcebergRestRepository(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "fixture", "",
                baseUri.replace("/catalog", "/dotted"), "", "", "S3", "", "", "", "");

        assertThat(client.listTables(dotted)).containsExactly("db.inner.deep");
        assertThat(requests).contains("/dotted/v1/namespaces/db%2Einner/tables",
                "/dotted/v1/namespaces?parent=db%2Einner");
    }

    @Test
    @DisplayName("an advertised separator that is not percent-escapes is ignored for the spec default")
    void unusableSeparatorFallsBackToTheDefault() {
        advertisedSeparator = "/";
        try {
            IcebergRestRepository dotted = new IcebergRestRepository(UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), "fixture", "",
                    baseUri.replace("/catalog", "/dotted"), "", "", "S3", "", "", "", "");

            // "/" would have turned db/inner into two path segments; the default %1F is what a
            // spec-following client sends instead, and this stub refuses it the way Jetty does.
            assertThatThrownBy(() -> client.listTables(dotted)).hasMessageContaining("400");
            assertThat(requests).noneMatch(r -> r.contains("namespaces/db/inner"));
        } finally {
            advertisedSeparator = "%2E";
        }
    }

    @Test
    @DisplayName("loadTable answers the CURRENT schema (v2 indirection), nested types as their kind")
    void loadTableReadsCurrentSchema() {
        IcebergCatalogClient.IcebergTable table = client.loadTable(repo("tok"), "db.users");

        assertThat(table.metadataLocation())
                .isEqualTo("s3://lake/db/users/metadata/00007.metadata.json");
        assertThat(table.fields()).extracting(IcebergCatalogClient.IcebergField::name)
                .containsExactly("id", "payload");
        assertThat(table.fields().getFirst().type()).isEqualTo("long");
        assertThat(table.fields().getFirst().required()).isTrue();
        assertThat(table.fields().get(1).type()).isEqualTo("struct");
    }

    @Test
    @DisplayName("a catalog error surfaces status and body — never the Authorization header")
    void errorsCarryDiagnosticsNotCredentials() {
        assertThatThrownBy(() -> client.loadTable(repo("secret-token"), "db.gone"))
                .hasMessageContaining("404")
                .hasMessageContaining("NoSuchTable")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-token"));
    }

    @Test
    @DisplayName("an identifier without a namespace is refused before any HTTP")
    void bareTableNameRefused() {
        assertThatThrownBy(() -> client.loadTable(repo("tok"), "users"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace.table");
    }
}
