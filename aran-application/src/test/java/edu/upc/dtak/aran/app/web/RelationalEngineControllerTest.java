package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.web.RelationalEngineController.RelationalEngineResponse;
import edu.upc.dtak.aran.core.source.RelationalEngines;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-engine capability the registration form renders.
 *
 * <p>No Spring context: the controller reads the static engine registry and the interesting
 * assertions are about honesty, not wiring.
 */
class RelationalEngineControllerTest {

    private Map<String, RelationalEngineResponse> published() {
        return new RelationalEngineController().listEngines().stream()
                .collect(Collectors.toMap(RelationalEngineResponse::id, Function.identity()));
    }

    @Test
    @DisplayName("Oracle ingests by extract alone, with no federation to fall back on")
    void oracle_ingests_without_federating() {
        // The honesty gap this whole exercise closed: before phase D, Oracle was offered in the
        // registration form and could do nothing once registered. It still cannot federate — the
        // extract is the entire answer, which is what makes it the interesting case.
        RelationalEngineResponse oracle = published().get("ORACLE");

        assertThat(oracle.supportsFederation()).isFalse();
        assertThat(oracle.bulkExtract()).isEqualTo("JDBC_GENERIC");
        assertThat(oracle.bulkExtractReady()).isTrue();
        assertThat(oracle.canIngest()).isTrue();
    }

    @Test
    @DisplayName("a declared mechanism and a built one stay separate facts, even now both are true")
    void declared_is_not_implemented() {
        // Merging these is how MATERIALIZE_TO_LAKE came to be declared while nothing implemented
        // it, and the user found out at query time. Every shipped mechanism is now built, so the
        // distinction cannot be caught by an engine that answers differently to the two questions
        // — HBase is the one that still can, declaring NONE and therefore never ready.
        RelationalEngineResponse hbase = published().get("HBASE");
        assertThat(hbase.bulkExtract()).isEqualTo("NONE");
        assertThat(hbase.bulkExtractReady()).isFalse();

        // ...and readiness is asked of the mechanism, not hardcoded per engine: MySQL and Oracle
        // declare the same JDBC_GENERIC and must therefore agree about it, whatever else differs.
        RelationalEngineResponse mysql = published().get("MYSQL");
        assertThat(mysql.bulkExtract()).isEqualTo("JDBC_GENERIC");
        assertThat(mysql.bulkExtractReady())
                .isEqualTo(published().get("ORACLE").bulkExtractReady());
        assertThat(mysql.supportsFederation()).isTrue();
        assertThat(mysql.canIngest()).isTrue();
    }

    @Test
    @DisplayName("PostgreSQL federates and materializes, by the lake's native path rather than JDBC")
    void postgres_has_both() {
        RelationalEngineResponse pg = published().get("POSTGRESQL");

        assertThat(pg.supportsFederation()).isTrue();
        assertThat(pg.bulkExtract()).isEqualTo("LAKE_NATIVE");
        assertThat(pg.bulkExtractReady()).isTrue();
        assertThat(pg.defaultPort()).isEqualTo(5432);
    }

    @Test
    @DisplayName("HBase claims nothing at all")
    void hbase_claims_nothing() {
        RelationalEngineResponse hbase = published().get("HBASE");

        assertThat(hbase.supportsFederation()).isFalse();
        assertThat(hbase.bulkExtract()).isEqualTo("NONE");
        assertThat(hbase.canIngest()).isFalse();
    }

    @Test
    @DisplayName("no connection detail or scan expression reaches the wire")
    void publishes_capability_not_mechanics() {
        // A form needs what an engine can do and which port to prefill — not how Aran talks to it.
        assertThat(published().toString())
                .doesNotContain("postgres_scan")
                .doesNotContain("mysql_scan")
                .doesNotContain("password");
    }

    @Test
    @DisplayName("every engine the registry ships is published, whatever the registry ships")
    void all_engines_published() {
        // Structural rather than a hardcoded list. The first version named the five engines that
        // existed when it was written, so adding SQL Server failed it — a test fighting the
        // extensibility the SPI exists to provide. Asserting against the registry means a new
        // engine needs no test edit, while an engine that silently vanishes still fails.
        Map<String, RelationalEngineResponse> all = published();

        assertThat(all).containsOnlyKeys(RelationalEngines.defaults().all().stream()
                .map(edu.upc.dtak.aran.core.source.RelationalEngine::id)
                .toArray(String[]::new));
        assertThat(all.values()).allSatisfy(e -> {
            assertThat(e.displayName()).isNotBlank();
            assertThat(e.defaultPort()).isPositive();
        });
    }

    @Test
    @DisplayName("SQL Server arrives extractable but not federatable, from its defaults alone")
    void sql_server() {
        // Added to test the SPI's acceptance criterion. It overrides no federation method at all;
        // the honest "no" comes from the interface defaults rather than from anything it says.
        RelationalEngineResponse sqlServer = published().get("SQLSERVER");

        assertThat(sqlServer.supportsFederation()).isFalse();
        assertThat(sqlServer.bulkExtract()).isEqualTo("JDBC_GENERIC");
        assertThat(sqlServer.defaultPort()).isEqualTo(1433);
        // And it inherits a working extract from the mechanism it named — the point of declaring
        // one centrally rather than per engine.
        assertThat(sqlServer.canIngest()).isTrue();
    }
}
