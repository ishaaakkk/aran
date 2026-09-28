package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.source.SourceConnector;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import edu.upc.dtak.aran.core.source.SourceSchema;
import java.io.IOException;
import java.sql.SQLException;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * One connector, every database with a JDBC driver.
 *
 * <p>Where the file connectors each handle one format, this handles a category: Oracle, SQL
 * Server, DB2, Snowflake, Redshift, SQLite, Trino, and anything else a driver exists for. That is
 * the whole argument for building it first — it is the only connector whose reach grows without
 * anyone writing more Aran code.
 *
 * <p>It claims a descriptor by the presence of connection details rather than by a content type or
 * a filename, so it is mutually exclusive with the file connectors by construction rather than by
 * a rule someone has to remember. {@code SourceConnectors} enforces that exclusivity at startup.
 *
 * <p>Whether a table is then read live or from a lake copy is not decided here: {@code
 * SourceAccess} grants FEDERATED only to an engine with a DuckDB scanner
 * ({@code RelationalEngine.duckDbExtension}), so every other engine is served from its copy.
 */
@NullMarked
@Component
public final class JdbcConnector implements SourceConnector {

    private final JdbcBootstrap jdbc;

    /**
     * The {@link DriverDownloader} is a constructor dependency because introspection has to open a
     * connection, and there is exactly one sanctioned way to get a driver for a source database —
     * see {@link JdbcBootstrap}'s javadoc for what the {@code DriverManager} shortcut cost.
     */
    public JdbcConnector(DriverDownloader drivers) {
        this.jdbc = new JdbcBootstrap(drivers);
    }

    @Override
    public String sourceKind() {
        return "JDBC";
    }

    @Override
    public boolean supports(SourceDescriptor source) {
        return source.connection() != null;
    }

    @Override
    public SourceSchema introspect(SourceDescriptor source) throws IOException {
        if (source.connection() == null) {
            throw new IOException("the JDBC connector requires connection details");
        }
        try {
            return new SourceSchema(jdbc.bootstrap(source));
        } catch (SQLException e) {
            // Wrapped rather than propagated: the SPI declares IOException, and a driver's message
            // is the most useful thing an operator gets when a hostname or a grant is wrong.
            throw new IOException("could not introspect " + source.connection().table()
                    + ": " + e.getMessage(), e);
        }
    }
}
