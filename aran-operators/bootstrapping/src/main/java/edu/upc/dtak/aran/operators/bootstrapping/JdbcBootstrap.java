package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.jspecify.annotations.NullMarked;

/**
 * Schema discovery over JDBC, from the catalog rather than from the rows.
 *
 * <p>This is the single highest-leverage connector Aran can have. Every other source kind needs
 * its own client, its own type system and its own idea of what a schema is; JDBC standardised all
 * three in 1997, and {@link DatabaseMetaData#getColumns} answers the question for Oracle, SQL
 * Server, DB2, Snowflake, Redshift, SQLite, Trino and anything else with a driver — from one
 * implementation.
 *
 * <p><b>Not one row is read.</b> The SPI requires it ("must use the catalog/describe API only and
 * never materialize rows"), and the requirement is not ceremonial: a bootstrap that did
 * {@code SELECT *} to infer types would be a table scan on someone's production database,
 * triggered by clicking Register in a web UI. The cost of introspecting a billion-row table here
 * is the same as introspecting an empty one.
 *
 * <p><b>The driver comes from {@link DriverDownloader}</b>, the path connection tests, previews,
 * the table extract and the catalog harvest all use: pinned coordinates and SHA-256 hashes,
 * loaded in a sandboxed classloader. This class opened its own connection through
 * {@code DriverManager} until 2026-09-07, and that was not merely a weaker copy of a security
 * control — it did not work. A driver the downloader loaded lives in a child
 * {@code URLClassLoader}, and {@code DriverManager.getConnection} re-resolves the driver class
 * through the <em>calling</em> loader before it will use it, so only the one vendor driver on the
 * application classpath (Postgres) was ever reachable. A federated MySQL or MariaDB repository —
 * both of which DuckDB can scan, so both of which {@code SourceAccess} grants FEDERATED — passed
 * test-connection, scan and preview and then failed here with "No suitable driver" (S4-4/S3-3).
 */
@NullMarked
final class JdbcBootstrap {

    /**
     * How long the connect step waits before giving up, in the unit named per engine below.
     *
     * <p>Bounded because this runs inside a web request. A host that is merely down answers
     * "connection refused" in milliseconds, but a blackholed one — a firewall dropping the packets
     * — answers nothing, and an unbounded connect then waits out the OS's own TCP retry schedule
     * (60–130s). A registration form that hangs for two minutes reads as a broken product, not as
     * a wrong hostname.
     *
     * <p>Bounded <em>per connection</em>, not with {@code DriverManager.setLoginTimeout}: that is
     * JVM-global mutable state shared with Hikari and every other JDBC user in the process, and
     * this class has no business writing it.
     */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    /**
     * The connect-timeout property each engine's driver reads, and the value in <em>that driver's
     * own unit</em>. Absent means unbounded, deliberately: {@code connectTimeout} is seconds for
     * PostgreSQL and milliseconds for MySQL and MariaDB, so one shared number would silently mean
     * "10 milliseconds" on two of the three and every connection would fail instantly. A guess is
     * worse than no bound.
     *
     * <p>Three entries is the whole reachable set, not an oversight: this class is only reached
     * for a FEDERATED relational dataset, and {@code SourceAccess.resolve} grants FEDERATED only
     * where {@code FederatedScanExpression} has a scanner — PostgreSQL, MySQL and MariaDB.
     * Oracle, SQL Server and HBase can be registered and extracted, never federated, so their
     * property names are not asserted here on the strength of documentation nobody has run.
     * (The right long-term home is a {@code RelationalEngine} capability, so each engine states
     * its own; that is S4-16 and needs the SPI, not this class.)
     */
    private static final Map<String, Map.Entry<String, String>> CONNECT_TIMEOUT_PROPERTIES = Map.of(
            "POSTGRESQL", Map.entry("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS)),
            "MYSQL", Map.entry("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS * 1000)),
            "MARIADB", Map.entry("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS * 1000)));

    private final DriverDownloader drivers;

    JdbcBootstrap(DriverDownloader drivers) {
        this.drivers = drivers;
    }

    Model bootstrap(SourceDescriptor source) throws SQLException {
        SourceDescriptor.Connection conn = java.util.Objects.requireNonNull(
                source.connection(), "the JDBC connector requires connection details");

        Model model = ModelFactory.createDefaultModel();
        String classIri = BootstrapEngine.sourceClassIri(source.datasetId(), source.datasetName());
        Resource cls = model.createResource(classIri);
        model.add(cls, RDF.type, RDFS.Class);
        model.add(cls, RDFS.label, source.datasetName());

        // No snapshot: nothing has been copied into the lake, and inventing one would make the
        // catalog resolve a snapshot that does not exist. The binding strategy is what tells the
        // query engine to reach the source live instead.
        WrapperVocab.attachClassWrapper(model, cls, source.datasetId(), "", "JDBC", conn.table(),
                WrapperVocab.Shape.FLAT);

        Map<String, Integer> columns = describeColumns(conn);
        if (columns.isEmpty()) {
            throw new SQLException("no columns found for table '" + conn.table()
                    + "' — check the table name, the schema, and that the user may read it");
        }

        for (Map.Entry<String, Integer> column : columns.entrySet()) {
            String name = column.getKey();
            String propIri = BootstrapEngine.sourcePropertyIri(
                    source.datasetId(), source.datasetName(), name);
            Resource prop = model.createResource(propIri);
            model.add(prop, RDF.type, RDF.Property);
            model.add(prop, RDFS.label, name);
            model.add(prop, RDFS.domain, cls);
            model.add(prop, RDFS.range, xsdFor(column.getValue()));
            model.add(prop, WrapperVocab.SOURCE_REF, name);
        }
        return model;
    }

    /**
     * Column names and their JDBC types, in ordinal order.
     *
     * <p>A {@link LinkedHashMap} because the contract requires introspection to be deterministic:
     * drift detection compares fingerprints, and a connector whose property order depends on hash
     * iteration reports drift on a table nobody touched.
     */
    private Map<String, Integer> describeColumns(SourceDescriptor.Connection conn)
            throws SQLException {
        Map<String, Integer> columns = new LinkedHashMap<>();

        Driver driver;
        try {
            driver = drivers.loadDriver(conn.engine());
        } catch (Exception e) {
            // loadDriver declares `throws Exception` — a download, a hash mismatch and an unknown
            // engine all arrive here. Wrapped rather than propagated so the connector's one
            // SQLException-to-IOException translation keeps working, and the driver's own reason
            // is the most useful thing an operator gets.
            throw new SQLException("could not load the " + conn.engine() + " JDBC driver: "
                    + e.getMessage(), e);
        }

        try (Connection jdbc = driver.connect(conn.jdbcUrl(), connectionProperties(conn))) {
            if (jdbc == null) {
                // Driver.connect returns null (rather than throwing) for a URL it does not
                // recognise — the one failure DriverManager used to report for us.
                throw new SQLException(
                        "the " + conn.engine() + " driver did not accept the connection URL");
            }
            DatabaseMetaData meta = jdbc.getMetaData();
            // Null schema means "any", which is what a user who did not specify one means. The
            // table name is passed as-is: getColumns treats it as a pattern, and quietly
            // upper-casing it here would break every case-sensitive database.
            //
            // The catalog is the connection's own, never null. On MySQL and MariaDB the catalog
            // IS the database and the schema argument is ignored, so a null catalog searches
            // every database the user can see: MariaDB answered a table named `events` with its
            // own two columns plus INFORMATION_SCHEMA.EVENTS' twenty-four, watched live against
            // MariaDB 11 (FederatedMariaDbBootstrapIT). PgJDBC ignores the catalog argument.
            try (ResultSet rs = meta.getColumns(jdbc.getCatalog(), conn.schema(), conn.table(), null)) {
                while (rs.next()) {
                    columns.put(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"));
                }
            }
        }
        return columns;
    }

    /**
     * This connection's driver properties: credentials, plus the connect bound when this engine's
     * property name and unit are stated in {@link #CONNECT_TIMEOUT_PROPERTIES}.
     *
     * <p>Package-private and static so a test can pin what is set without opening a connection —
     * the same "test the config, not just the behaviour" split {@code
     * RelationalCatalogHarvestService.connectionProperties} makes.
     */
    static Properties connectionProperties(SourceDescriptor.Connection conn) {
        Properties props = new Properties();
        if (conn.username() != null) {
            props.setProperty("user", conn.username());
        }
        if (conn.password() != null) {
            props.setProperty("password", conn.password());
        }
        if (conn.engine() != null) {
            Map.Entry<String, String> timeout =
                    CONNECT_TIMEOUT_PROPERTIES.get(conn.engine().toUpperCase(java.util.Locale.ROOT));
            if (timeout != null) {
                props.setProperty(timeout.getKey(), timeout.getValue());
            }
        }
        return props;
    }

    /**
     * A JDBC type as an XSD type.
     *
     * <p>Deliberately coarse. The integration stage compares ranges to decide whether two
     * properties can be aligned, so what matters is that a number is not a string and a date is
     * not a number — not that DECIMAL(38,2) is distinguished from DECIMAL(10,4). Anything
     * unrecognised becomes a string, which is the safe direction: a value that is really a number
     * still compares and displays correctly as text, whereas guessing a numeric range for an
     * opaque vendor type produces a view that fails at query time.
     */
    private static Resource xsdFor(int jdbcType) {
        return switch (jdbcType) {
            case Types.BIT, Types.BOOLEAN -> XSD.xboolean;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> XSD.integer;
            case Types.BIGINT -> XSD.xlong;
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> XSD.xdouble;
            case Types.NUMERIC, Types.DECIMAL -> XSD.decimal;
            case Types.DATE -> XSD.date;
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> XSD.time;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> XSD.dateTime;
            default -> XSD.xstring;
        };
    }
}
