package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.domain.TableExtractor;
import edu.upc.dtak.aran.core.net.OutboundAddressGuard;
import edu.upc.dtak.aran.core.service.JdbcDriverRegistry;
import edu.upc.dtak.aran.core.source.RelationalEngine;
import edu.upc.dtak.aran.core.source.RelationalEngines;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.util.Properties;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The {@link TableExtractor} port, over {@link JdbcBulkExtractor}.
 *
 * <p>This class is the wiring — driver, URL, credentials, temp file — and holds no extraction
 * logic, which stays in {@code JdbcBulkExtractor} where it can be tested against a real
 * {@code ResultSet} without a driver download.
 *
 * <p>Drivers come from {@link DriverDownloader}, the same path connection tests and previews use:
 * pinned coordinates and SHA-256 hashes from {@link JdbcDriverRegistry}, loaded in a sandboxed
 * classloader. Opening a connection any other way here would be a second, weaker copy of a
 * security control.
 */
@NullMarked
@Component
public final class JdbcTableExtractor implements TableExtractor {

    private final DriverDownloader drivers;
    private final OutboundAddressGuard addressGuard;
    private final JdbcBulkExtractor extractor = new JdbcBulkExtractor();

    public JdbcTableExtractor(DriverDownloader drivers, OutboundAddressGuard addressGuard) {
        this.drivers = drivers;
        this.addressGuard = addressGuard;
    }

    @Override
    public Extracted extract(RelationalDatabaseRepository repository, String table, long maxBytes) {
        // Screen the target before any driver/connect work — the same guard registration uses,
        // now on the import path too (sec-audit F-S1-1).
        addressGuard.checkHost(repository.host());

        RelationalEngine engine = RelationalEngines.defaults().find(repository.engine())
                .orElseThrow(() -> new UnsupportedOperationException(
                        "no engine registered for '" + repository.engine() + "'"));
        if (engine.bulkExtract() != RelationalEngine.BulkExtract.JDBC_GENERIC) {
            throw new UnsupportedOperationException(
                    engine.displayName() + " does not use the generic JDBC extract (declares "
                    + engine.bulkExtract() + ")");
        }

        JdbcDriverRegistry.DriverMetadata meta = JdbcDriverRegistry.get(repository.engine())
                .orElseThrow(() -> new UnsupportedOperationException(
                        "no JDBC driver registered for " + repository.engine()));
        String url = meta.urlTemplate()
                .replace("{host}", repository.host())
                .replace("{port}", String.valueOf(repository.port()))
                .replace("{database}", repository.database());

        // The table name is quoted AND qualified by the engine, and the caller has already checked
        // it against the relations the repository actually exposes — free-form input never reaches
        // here. Qualified because, on engines with schemas, that membership list is scoped to the
        // repository's schema (S2-7): unqualified, a same-named relation on the connection's
        // default search path would be copied in its place, silently, under the chosen table's
        // name. MySQL and MariaDB have no such scoping (their drivers ignore the schemaPattern and
        // the database is the schema), so their engine reads the bare table on the connection's
        // database — RelationalEngine.qualify's MySqlEngine override.
        String query = "SELECT * FROM " + engine.qualify(repository.schema(), table);

        Path parquet = null;
        try {
            Driver driver = drivers.loadDriver(repository.engine());
            Properties props = new Properties();
            if (repository.username() != null) props.setProperty("user", repository.username());
            if (repository.password() != null) props.setProperty("password", repository.password());

            parquet = Files.createTempFile("aran-extract-", ".parquet");
            try (Connection source = driver.connect(url, props)) {
                if (source == null) {
                    throw new IllegalStateException(
                            "the " + repository.engine() + " driver did not accept " + url);
                }
                JdbcBulkExtractor.Extract extract =
                        extractor.extractToParquet(source, query, parquet, maxBytes);
                return new Extracted(Files.readAllBytes(extract.parquet()), extract.rowCount());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "extract of '" + table + "' from " + repository.engine() + " failed: "
                    + e.getMessage(), e);
        } finally {
            if (parquet != null) {
                try {
                    Files.deleteIfExists(parquet);
                } catch (java.io.IOException ignored) {
                    // The bytes are already read; a leftover temp file is not worth failing an
                    // otherwise successful extract.
                }
            }
        }
    }
}
