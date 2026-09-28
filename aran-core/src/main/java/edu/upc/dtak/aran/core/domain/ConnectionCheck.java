package edu.upc.dtak.aran.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of the last connection test a person ran against a stored repository (V46).
 *
 * <p>Kept so the repositories list can say "connected, tested 3 h ago" without dialing every
 * source on every visit (UI audit J2-F19): the list used to probe each repository on page load,
 * which ran a Singer tap's discovery and opened a JDBC connection per database for a glance at a
 * table, and read a first driver download as UNREACHABLE. Only a test of exactly the stored
 * configuration is recorded — a test of unsaved edits in the edit dialog says nothing about what
 * the list row describes — and an update discards the record, because it no longer describes the
 * stored configuration either.
 *
 * @param testedAt when the test read the configuration it dialed — before the probe, so a save
 *     landing mid-probe is later than the check
 * @param testedBy the user who ran the test; null only for a row whose user was deleted
 */
@NullMarked
public record ConnectionCheck(
        UUID repositoryId,
        UUID tenantId,
        boolean success,
        String message,
        @Nullable String details,
        Instant testedAt,
        @Nullable UUID testedBy) {
}
