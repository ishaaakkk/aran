package edu.upc.dtak.aran.core.source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/**
 * Which Singer taps this deployment is willing to run.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A Singer tap is an executable Aran launches as a subprocess. If the command came from a
 * registration form, any tenant user could run arbitrary code as the Aran backend — on a
 * multi-tenant deployment that is every other tenant's data and every credential the backend
 * holds. {@code SingerTapRunner}'s own Javadoc already assumes otherwise: it says a tap is "a
 * command an <em>operator</em> configures", meaning whoever runs Aran, not whoever signs into it.
 *
 * <p>So the command is deployment configuration and never user input. A user chooses an
 * <em>id</em> from this catalogue and supplies <em>values</em>; Aran builds the argv. This mirrors
 * {@link edu.upc.dtak.aran.core.service.JdbcDriverRegistry}, which pins driver coordinates and
 * SHA-256 hashes rather than letting anyone name a JAR — and a driver at least lands in a
 * sandboxed {@code URLClassLoader}, where a tap is a process with the backend's own privileges.
 *
 * <h2>The two rules that make it safe</h2>
 *
 * <ol>
 *   <li><b>The command is never user input.</b> {@link #commandFor} builds argv from the
 *       configured command plus {@code --config <file>}, and nothing else.</li>
 *   <li><b>Config values never reach argv.</b> They are written to a JSON file the tap reads,
 *       so a value containing a space, a quote or a {@code --flag} is data rather than another
 *       argument. Only declared keys are accepted, so a tap cannot be handed settings its
 *       operator did not sanction.</li>
 * </ol>
 *
 * <p>An empty catalogue is the default and means the feature is off. That is deliberate: a
 * deployment that never configures a tap cannot run one.
 */
@NullMarked
public final class SingerTapCatalog {

    /**
     * One tap this deployment allows.
     *
     * @param id         stable identifier a user selects, e.g. {@code "exchangeratesapi"}
     * @param label      what to show in a form
     * @param command    argv Aran executes, e.g. {@code [/opt/taps/bin/tap-exchangeratesapi]}.
     *                   From configuration only
     * @param configKeys the settings a user may supply. Anything else is refused
     * @param secretKeys the subset of {@code configKeys} that hold credentials, so callers can
     *                   avoid logging or echoing them back
     */
    public record Tap(
            String id,
            String label,
            List<String> command,
            Set<String> configKeys,
            Set<String> secretKeys) {

        public Tap {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("a tap needs an id");
            }
            if (command == null || command.isEmpty()) {
                throw new IllegalArgumentException("tap '" + id + "' has no command configured");
            }
            if (command.stream().anyMatch(a -> a == null || a.isBlank())) {
                throw new IllegalArgumentException("tap '" + id + "' has a blank argument");
            }
            label = (label == null || label.isBlank()) ? id : label;
            command = List.copyOf(command);
            // Locals because the components are reassigned just above, which makes them not
            // effectively final and so unusable inside the lambda below.
            final Set<String> accepted = configKeys == null ? Set.of() : Set.copyOf(configKeys);
            final Set<String> secret = secretKeys == null ? Set.of() : Set.copyOf(secretKeys);
            if (!accepted.containsAll(secret)) {
                throw new IllegalArgumentException(
                        "tap '" + id + "' marks keys secret that it does not accept: "
                        + secret.stream().filter(k -> !accepted.contains(k)).sorted().toList());
            }
            configKeys = accepted;
            secretKeys = secret;
        }
    }

    /** Refused because the deployment does not allow that tap. Never says what it *would* allow. */
    public static final class TapNotAllowedException extends RuntimeException {
        public TapNotAllowedException(String message) {
            super(message);
        }
    }

    private final Map<String, Tap> byId;

    public SingerTapCatalog(List<Tap> taps) {
        Map<String, Tap> index = new LinkedHashMap<>();
        for (Tap tap : taps) {
            if (index.put(tap.id(), tap) != null) {
                throw new IllegalArgumentException(
                        "two Singer taps are configured with id '" + tap.id() + "'");
            }
        }
        this.byId = Map.copyOf(index);
    }

    /** No taps configured — the feature is off, which is the default. */
    public static SingerTapCatalog empty() {
        return new SingerTapCatalog(List.of());
    }

    public boolean isEnabled() {
        return !byId.isEmpty();
    }

    /** Every allowed tap, for a form to offer. Carries no secrets — only key names. */
    public List<Tap> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Tap> find(String tapId) {
        return Optional.ofNullable(byId.get(tapId));
    }

    private Tap require(String tapId) {
        Tap tap = byId.get(tapId);
        if (tap == null) {
            throw new TapNotAllowedException(
                    "no Singer tap '" + tapId + "' is configured on this Aran instance. Taps are declared "
                    + "by the deployment, not by users; ask whoever operates this instance to add "
                    + "it under aran.singer.taps.");
        }
        return tap;
    }

    /**
     * The argv to execute, for a tap this deployment allows.
     *
     * <p>Built entirely from configuration plus the path Aran itself chose for the config file.
     * No caller-supplied string reaches this list — that is the whole point, and
     * {@code SingerTapCatalogTest} asserts it.
     *
     * @throws TapNotAllowedException if {@code tapId} is not in the catalogue
     */
    public List<String> commandFor(String tapId, Path configFile) {
        Tap tap = require(tapId);
        List<String> argv = new ArrayList<>(tap.command());
        argv.add("--config");
        argv.add(configFile.toAbsolutePath().toString());
        return List.copyOf(argv);
    }

    /**
     * Check a user's settings against what the tap declares, returning them unchanged if they pass.
     *
     * <p>Rejecting unknown keys rather than dropping them: a user who typed {@code api_key} where
     * the tap wants {@code apiKey} should be told, not left with a tap that authenticates as
     * nobody and returns an empty stream that looks like "no data".
     *
     * @throws TapNotAllowedException   if the tap is not allowed
     * @throws IllegalArgumentException if a setting is not one the tap accepts
     */
    public Map<String, String> validateConfig(String tapId, Map<String, String> supplied) {
        Tap tap = require(tapId);
        List<String> unknown = supplied.keySet().stream()
                .filter(k -> !tap.configKeys().contains(k))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "tap '" + tapId + "' does not accept " + unknown + ". It accepts: "
                    + tap.configKeys().stream().sorted().toList());
        }
        return Map.copyOf(supplied);
    }
}
