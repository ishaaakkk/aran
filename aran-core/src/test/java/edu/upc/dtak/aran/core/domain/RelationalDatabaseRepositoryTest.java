package edu.upc.dtak.aran.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code host} and {@code database} are spliced verbatim into the JDBC URL template
 * ({@code jdbc:mysql://{host}:{port}/{database}}) at {@code DataRepositoryService}. A value
 * carrying URL-property syntax therefore injects live driver options — the sec-audit
 * F-S1-3 / F-MALSRC-1 vector: {@code database=db?allowLoadLocalInfile=true} turns a rogue MySQL
 * server into a local-file read of the Aran host. These tests pin the field screen that stops it.
 */
class RelationalDatabaseRepositoryTest {

    private static RelationalDatabaseRepository repo(String host, String database) {
        return new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "src", "desc", "POSTGRESQL", host, 5432, database,
                "user", "pw", "public", false);
    }

    @ParameterizedTest
    @DisplayName("a database name carrying JDBC-URL property syntax is rejected")
    @ValueSource(strings = {
            "db?allowLoadLocalInfile=true",
            "db&allowUrlInLocalInfile=true",
            "db;options=x",
            "db world",
            "db/../other",
            "db'\"x",
    })
    void rejectsDatabaseInjection(String database) {
        assertThatThrownBy(() -> repo("db.internal", database))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database");
    }

    @ParameterizedTest
    @DisplayName("a host carrying URL/property syntax is rejected")
    @ValueSource(strings = {
            "h?x=1",
            "h&x=1",
            "h/x",
            "h x",
            "h'x",
    })
    void rejectsHostInjection(String host) {
        assertThatThrownBy(() -> repo(host, "northwind"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @ParameterizedTest
    @DisplayName("ordinary hostnames, IPs and database names are accepted")
    @ValueSource(strings = {
            "db.internal", "postgres", "10.0.0.5", "my-db-host.example.com", "localhost",
    })
    void acceptsOrdinaryHosts(String host) {
        assertThatCode(() -> repo(host, "northwind")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @DisplayName("ordinary database names are accepted")
    @ValueSource(strings = {
            "northwind", "aran_lake", "my-db", "app.prod", "DB123",
    })
    void acceptsOrdinaryDatabases(String database) {
        assertThatCode(() -> repo("db.internal", database)).doesNotThrowAnyException();
    }
}
