package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.drift.FederatedSchemaProbe;
import edu.upc.dtak.aran.core.domain.drift.FederatedSchemaProbe.ProbeUnavailableException;
import edu.upc.dtak.aran.core.domain.drift.FederatedSchemaProbe.ProbedColumn;
import edu.upc.dtak.aran.core.source.FederatedScan;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Asks DuckDB to describe a live federated table — the JDBC half of {@link FederatedSchemaProbe},
 * here rather than in core because core's {@code java.sql} use is a frozen ratchet
 * ({@code ArchitectureTest}) and binding a {@link FederatedScan} needs more of it than the old
 * single-expression describe did.
 *
 * <p>{@code DESCRIBE SELECT * FROM <expression> LIMIT 0} asks the remote server for column names
 * and types without scanning a row, after the scan's own setup — for a MySQL repository that is
 * the secret and the {@code ATTACH}, without which the expression names nothing.
 */
@NullMarked
final class LiveSchemaProbe {

    private LiveSchemaProbe() {}

    /**
     * @throws ProbeUnavailableException when the remote cannot be read — never a drift verdict:
     *         conflating "the database was down" with "the schema changed" would raise a breaking
     *         alarm every time a database blinked
     */
    static List<ProbedColumn> describe(FederatedScan scan) {
        List<ProbedColumn> columns = new ArrayList<>();
        try (Connection conn = DuckDbSession.open();
             Statement stmt = conn.createStatement()) {
            List<String> setup = scan.setupSql();
            for (int i = 0; i < setup.size(); i++) {
                try {
                    stmt.execute(setup.get(i));
                } catch (SQLException e) {
                    // The statement may hold a password; setupFailureMessage decides what of the
                    // driver's message is safe to keep, and the cause is deliberately dropped.
                    throw new ProbeUnavailableException("Could not read the live schema: "
                            + FederatedScan.setupFailureMessage(i, setup.get(i), e.getMessage()));
                }
            }
            try (ResultSet rs = stmt.executeQuery(
                    "DESCRIBE SELECT * FROM " + scan.expression() + " LIMIT 0")) {
                while (rs.next()) {
                    columns.add(new ProbedColumn(rs.getString("column_name"),
                            rs.getString("column_type")));
                }
            }
        } catch (ProbeUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ProbeUnavailableException(
                    "Could not read the live schema: " + firstLine(e), e);
        }
        return columns;
    }

    /**
     * DuckDB prints the statement after the first line of an error, and a setup statement
     * ({@code CREATE SECRET …}) carries the source's credential.
     */
    private static String firstLine(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage() == null ? current.toString() : current.getMessage();
        return message.strip().lines().findFirst().orElse("");
    }
}
