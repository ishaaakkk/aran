package edu.upc.dtak.aran.core.source.engines;

import org.jspecify.annotations.NullMarked;

/**
 * MariaDB, served by MySQL's attached-catalog read and quoting.
 *
 * <p>A subclass rather than a copy so the two cannot drift. The wire compatibility it rests on
 * is watched, not assumed: bootstrap, live OQL rows and the drift probe all run against a real
 * MariaDB 11 in {@code FederatedMariaDbBootstrapIT}, and this is still the one place to diverge
 * if a later MariaDB stops answering the mysql extension.
 */
@NullMarked
public final class MariaDbEngine extends MySqlEngine {

    @Override
    public String id() {
        return "MARIADB";
    }

    @Override
    public String displayName() {
        return "MariaDB";
    }
}
