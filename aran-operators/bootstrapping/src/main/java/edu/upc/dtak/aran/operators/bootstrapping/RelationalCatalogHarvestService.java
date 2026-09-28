package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.DriverDownloader;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import edu.upc.dtak.aran.core.service.JdbcDriverRegistry;
import edu.upc.dtak.aran.core.source.CatalogHarvestQueries;
import edu.upc.dtak.aran.core.source.Harvest;
import edu.upc.dtak.aran.core.source.RelationalEngine;
import edu.upc.dtak.aran.core.source.RelationalEngines;
import java.sql.Connection;
import java.sql.Driver;
import java.util.Optional;
import java.util.Properties;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves a {@link RelationalDatabaseRepository} to a live connection and harvests its catalog.
 *
 * <p>The wiring layer for {@link JdbcCatalogHarvester}: engine lookup, JDBC URL, driver, and
 * credentials — the same path {@code JdbcTableExtractor} and {@code
 * DataRepositoryService.testJdbcConnection} already use. Drivers come from {@link
 * DriverDownloader}: pinned coordinates and SHA-256 hashes from {@link JdbcDriverRegistry}, loaded
 * in a sandboxed classloader. Opening a connection any other way here would be a second, weaker
 * copy of a security control — the same reasoning {@code JdbcTableExtractor}'s javadoc gives.
 *
 * <h2>Best-effort — never throws</h2>
 *
 * <p>A catalog harvest feeds cost estimation; it must never be the reason a query, a plan, or a
 * registration flow fails. Every failure this class can hit itself — the engine is unknown, it
 * declares no {@link CatalogHarvestQueries}, no driver is registered for it, the driver could not
 * be loaded, the host is unreachable, the credentials are wrong — degrades to {@link
 * Harvest#EMPTY} behind a single WARN log line. (A failure reading only <em>one</em> catalog view
 * once connected is handled one layer in, by {@link JdbcCatalogHarvester}, which can return a
 * partial harvest — this class's own catch is for the connection itself never succeeding at all.)
 *
 * <h2>Credential hygiene</h2>
 *
 * <p>The WARN log line below names the engine, host and port — already public, since a user typed
 * them into a registration form — and the driver's own exception message. It never logs {@code
 * repo} itself (its generated {@code toString()} would include the plaintext password), the
 * {@link Properties} carrying the credentials, or the connection URL's argument list.
 *
 * <h2>The connect step is bounded — a dead database fails fast, never hangs</h2>
 *
 * <p>"Unreachable host" has two very different failure speeds. A host that is merely down, or a
 * port nothing is listening on, answers "connection refused" in milliseconds. A <em>blackholed</em>
 * one — a firewall silently dropping the packets, a dead route — answers nothing at all, and a
 * plain {@code driver.connect(url, props)} then waits out the OS's own TCP retry schedule (commonly
 * 60–130s) before giving up on its own. {@code DatasetService.registerDataset} calls this class's
 * {@link #harvest} inline, on the request thread — enrolling a federated relational dataset has no
 * job to defer into (see that method's javadoc) — so an unbounded connect there would hang a
 * user-facing request for that long, and a dataset registered against a deliberately blackholed
 * address would be a cheap way to tie one up. {@link #CONNECT_TIMEOUT_SECONDS} bounds the connect
 * step specifically, via {@link #connectionProperties}; a timeout is just a faster instance of the
 * same "could not connect" failure this class already swallows into {@link Harvest#EMPTY} behind a
 * WARN, so no other part of the best-effort contract changes.
 */
@NullMarked
@Component
public final class RelationalCatalogHarvestService {

    private static final Logger log = LoggerFactory.getLogger(RelationalCatalogHarvestService.class);

    /**
     * How long the connect step waits before giving up, in seconds — PostgreSQL's driver property
     * unit (see {@link #connectionProperties}). Generous for any real database, including one
     * across a slow WAN link, and short enough that a blackholed one fails within a request a user
     * is actively waiting on rather than after the OS's own multi-minute retry schedule.
     */
    static final int CONNECT_TIMEOUT_SECONDS = 10;

    private final DriverDownloader drivers;
    private final edu.upc.dtak.aran.core.net.OutboundAddressGuard addressGuard;
    private final JdbcCatalogHarvester harvester = new JdbcCatalogHarvester();

    public RelationalCatalogHarvestService(DriverDownloader drivers,
            edu.upc.dtak.aran.core.net.OutboundAddressGuard addressGuard) {
        this.drivers = drivers;
        this.addressGuard = addressGuard;
    }

    /**
     * This repository's connection properties: credentials plus {@link #CONNECT_TIMEOUT_SECONDS}.
     *
     * <p>Package-private and static so a test can pin the timeout is actually set without opening a
     * real connection (see {@code RelationalCatalogHarvestServiceConnectTimeoutTest}) — the same
     * "test the config, not just the behaviour" reasoning as {@code CatalogHarvestQueries}' own
     * pure-function split from the JDBC that runs it.
     *
     * <p>{@code connectTimeout} is PostgreSQL's driver property name, read in <b>seconds</b>. This
     * method is reached only for an engine whose {@link RelationalEngine#catalogHarvestQueries()}
     * is present — today, only {@code PostgresEngine} overrides that default-empty capability — so
     * a Postgres-specific property name is safe here. <b>If a future engine's harvest ever reuses
     * this exact connect path, its driver's own timeout property name and unit must be verified
     * first</b>: MySQL Connector/J, for one, also has a property literally named {@code
     * connectTimeout}, but reads it in <b>milliseconds</b> — the same property name here would
     * silently mean "10 milliseconds", not "10 seconds", and every connection would fail instantly.
     *
     * <p>PGJDBC 42.7.x happens to default this same property to {@code "10"} internally when
     * nothing sets it (confirmed directly against the pinned {@code JdbcDriverRegistry} coordinate,
     * 42.7.5, and the newer 42.7.10 this module's tests resolve) — so this call is not the only
     * thing standing between a blackholed host and a hang today. It is still made explicit rather
     * than left to that undocumented, version-specific driver default: nothing in this codebase
     * asserted it before, a future driver bump or a different connect path could change or bypass
     * it silently, and {@code RelationalCatalogHarvestServiceConnectTimeoutTest} can only regress on
     * a property this method actually sets.
     */
    static Properties connectionProperties(RelationalDatabaseRepository repo) {
        Properties props = new Properties();
        if (repo.username() != null) {
            props.setProperty("user", repo.username());
        }
        if (repo.password() != null) {
            props.setProperty("password", repo.password());
        }
        props.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        return props;
    }

    /** Best-effort: always returns a {@link Harvest}, never throws. See the class javadoc. */
    public Harvest harvest(RelationalDatabaseRepository repo) {
        Optional<RelationalEngine> engine = RelationalEngines.defaults().find(repo.engine());
        if (engine.isEmpty()) {
            return Harvest.EMPTY;
        }
        Optional<CatalogHarvestQueries> queries = engine.get().catalogHarvestQueries();
        if (queries.isEmpty()) {
            // Absent-by-default capability: this engine has never claimed it can harvest.
            return Harvest.EMPTY;
        }
        Optional<JdbcDriverRegistry.DriverMetadata> meta = JdbcDriverRegistry.get(repo.engine());
        if (meta.isEmpty()) {
            return Harvest.EMPTY;
        }

        String url = meta.get().urlTemplate()
                .replace("{host}", repo.host())
                .replace("{port}", String.valueOf(repo.port()))
                .replace("{database}", repo.database());

        try {
            // Screen the target host before connecting (sec-audit F-S1-1). A blocked host lands in
            // the best-effort catch below (BlockedHostException is a RuntimeException), so harvest
            // simply yields no edges rather than throwing — the class's non-throwing contract holds.
            addressGuard.checkHost(repo.host());

            Driver driver = drivers.loadDriver(repo.engine());
            Properties props = connectionProperties(repo);

            try (Connection conn = driver.connect(url, props)) {
                if (conn == null) {
                    log.warn("catalog harvest: the {} driver did not accept a connection to {}:{}",
                            repo.engine(), repo.host(), repo.port());
                    return Harvest.EMPTY;
                }
                return harvester.harvest(conn, queries.get(), repo.schema());
            }
        } catch (Exception e) {
            // Broad on purpose: DriverDownloader.loadDriver declares "throws Exception", and an
            // unreachable host or a rejected password both land here as a checked SQLException.
            // Never rethrown — see the class javadoc's best-effort contract. Never Throwable: a
            // genuine Error (e.g. OutOfMemoryError) still propagates rather than being swallowed.
            log.warn("catalog harvest could not connect to {} at {}:{} — {}",
                    repo.engine(), repo.host(), repo.port(), e.getMessage());
            return Harvest.EMPTY;
        }
    }
}
