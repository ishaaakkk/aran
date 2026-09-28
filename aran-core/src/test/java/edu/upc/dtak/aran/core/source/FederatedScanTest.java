package edu.upc.dtak.aran.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FederatedScanTest {

    private static final String PASSWORD = "hunter2-sentinel";

    @Test
    @DisplayName("a failed CREATE SECRET reports its index and keyword only — DuckDB's parser echoes the statement")
    void secretFailureWithholdsTheDriverMessage() {
        // Watched on DuckDB 1.5.5: a parser error prints the statement after the first line, and
        // "at or near" can quote the password token on the first.
        String message = FederatedScan.setupFailureMessage(3,
                "CREATE OR REPLACE TEMPORARY SECRET s (TYPE mysql, PASSWORD '" + PASSWORD + "')",
                "Parser Error: syntax error at or near \"'" + PASSWORD + "'\"\n\nLINE 1: CREATE …");

        assertThat(message)
                .isEqualTo("federated setup statement 3 (CREATE) failed")
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("a failed ATTACH keeps the first line — what tells 'Access denied' from 'Unknown host'")
    void attachFailureKeepsTheFirstLine() {
        String message = FederatedScan.setupFailureMessage(4,
                "ATTACH IF NOT EXISTS '' AS aran_attach_x (TYPE mysql, SECRET s, READ_ONLY)",
                "IO Error: Failed to connect to MySQL database with parameters \"\": Access denied"
                        + "\nLINE 1: ATTACH …");

        assertThat(message).isEqualTo("federated setup statement 4 (ATTACH) failed: IO Error: "
                + "Failed to connect to MySQL database with parameters \"\": Access denied");
    }

    @Test
    @DisplayName("toString carries counts, never a statement")
    void toStringRedacts() {
        FederatedScan scan = FederatedScan.attachedCatalog("mysql", "aran_attach_ab", "aran_attach_ab.\"d\".\"t\"",
                List.of("CREATE OR REPLACE TEMPORARY SECRET s (TYPE mysql, PASSWORD '" + PASSWORD + "')",
                        "ATTACH IF NOT EXISTS '' AS aran_attach_ab (TYPE mysql, SECRET s, READ_ONLY)"));

        assertThat(scan.toString()).doesNotContain(PASSWORD).doesNotContain("SECRET");
        assertThat(FederatedScan.tableFunction("postgres", "postgres_scan('password=" + PASSWORD + "')")
                .toString()).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("an attach alias must carry the reserved prefix the SQL screen refuses")
    void aliasMustBeReserved() {
        assertThatThrownBy(() -> FederatedScan.attachedCatalog("mysql", "src", "src.\"d\".\"t\"",
                List.of("ATTACH '' AS src (TYPE mysql, READ_ONLY)")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FederatedScan.attachAlias(UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")))
                .isEqualTo("aran_attach_0f1e2d3c4b5a69788796a5b4c3d2e1f0");
    }
}
