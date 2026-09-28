package edu.upc.dtak.aran.operators.queryengine;

import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one directory the query engine writes its working files into.
 *
 * <p>Snapshot downloads and data-product {@code COPY} targets used to go to
 * {@code Files.createTempFile(prefix, suffix)}, which is the JVM's {@code java.io.tmpdir} —
 * {@code /tmp} in a container. That was fine while nothing depended on <em>where</em> they were.
 *
 * <p>{@link DuckDbExecutor#restrictFilesystemTo} depends on it. The allowlist it installs has to
 * name every directory a legitimate query touches, so if the working files sit directly in
 * {@code /tmp} then {@code /tmp} is what gets allowed — and {@code /tmp} on a busy node is where
 * every other tenant's snapshot also is. Giving those files a directory of their own is what
 * turns the allowlist from a gesture into a boundary — and giving each <em>query</em> one, a
 * {@link QueryWorkspace} inside this directory, is what makes it a boundary between queries: this
 * directory itself is on no allowlist, and holds only workspaces and the results they hand off.
 *
 * <p>Not configurable on purpose: it is a scratch location whose only requirement is that it be
 * writable and distinct. The snapshot <em>cache</em> is the thing worth pointing at a real volume
 * ({@code aran.query.snapshot-cache.dir}), because it is the thing worth keeping.
 */
@NullMarked
final class QueryTempDir {

    private static final String NAME = "aran-query-work";

    private QueryTempDir() {
    }

    /** The working directory, created if it does not exist yet. */
    static Path get() throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), NAME);
        Files.createDirectories(dir);
        return dir;
    }

    /** As {@code Files.createTempFile(prefix, suffix)}, but inside {@link #get()}. */
    static Path createFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(get(), prefix, suffix);
    }
}
