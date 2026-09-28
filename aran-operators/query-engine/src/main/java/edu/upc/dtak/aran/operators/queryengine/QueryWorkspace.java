package edu.upc.dtak.aran.operators.queryengine;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * One query's private working directory, inside {@link QueryTempDir}: the snapshots it downloads
 * and the file it {@code COPY}s its result to live here, and this directory — not the shared
 * working area — is what its filesystem lockdown allowlists.
 *
 * <p>The shared {@code aran-query-work} holds every running query's downloads and every result
 * waiting to be streamed, whichever tenant it belongs to. Allowlisting it made a statement that
 * slipped the SQL screen able to read — or glob — all of them from inside the lockdown. A
 * directory per query confines each connection to its own files.
 *
 * <p>A result that must outlive the query (the caller streams it, then deletes it) is moved out
 * with {@link #handOff} once the query's connection is closed, into the shared directory, which no
 * locked-down connection allowlists any more. {@link #close} deletes whatever is left.
 */
@NullMarked
final class QueryWorkspace implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QueryWorkspace.class);

    private final Path dir;

    private QueryWorkspace(Path dir) {
        this.dir = dir;
    }

    static QueryWorkspace create() throws IOException {
        return new QueryWorkspace(Files.createTempDirectory(QueryTempDir.get(), "q-"));
    }

    Path dir() {
        return dir;
    }

    /** As {@code Files.createTempFile(prefix, suffix)}, inside this query's directory. */
    Path createFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(dir, prefix, suffix);
    }

    /**
     * Moves {@code file} out of the workspace into the shared working directory and returns its
     * new path — for the one result a caller streams after the query is over. Call only once the
     * connection that wrote it is closed.
     */
    Path handOff(Path file, String prefix, String suffix) throws IOException {
        Path out = QueryTempDir.createFile(prefix, suffix);
        Files.move(file, out, StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    /** Deletes the directory and everything still in it. Best effort: a leftover costs disk only. */
    @Override
    public void close() {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("could not delete query working file {}", p, e);
                }
            });
        } catch (IOException e) {
            log.debug("could not clean up query working directory {}", dir, e);
        }
    }
}
