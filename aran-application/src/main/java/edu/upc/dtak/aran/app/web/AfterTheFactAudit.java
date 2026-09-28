package edu.upc.dtak.aran.app.web;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the audit line for an action that has already happened and cannot be undone.
 *
 * <p>Every other audit write joins the action's own transaction, so either both land or neither
 * does. A schema promote or rollback cannot: it moves the graph in Fuseki and the version rows in
 * Postgres, which no transaction spans, and its three doors hold none (D5 part A will give
 * promotion one owner). So the line is written last, after everything the promote triggers —
 * stale-marking, notes — and a failure to write it is logged rather than thrown. Throwing would
 * answer an error for a promote that happened, and the user would retry an action that is no
 * longer possible, while stale-marking and notes that came after it were skipped.
 */
@NullMarked
final class AfterTheFactAudit {

    private static final Logger log = LoggerFactory.getLogger(AfterTheFactAudit.class);

    private AfterTheFactAudit() {}

    /** @param what the action, as the log should name it — "promote of schema version 4" */
    static void record(String what, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.error("{} succeeded, but its audit line could not be written — Activity is "
                    + "missing it", what, e);
        }
    }
}
