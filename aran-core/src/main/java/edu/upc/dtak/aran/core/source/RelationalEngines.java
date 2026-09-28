package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.source.engines.HBaseEngine;
import edu.upc.dtak.aran.core.source.engines.MariaDbEngine;
import edu.upc.dtak.aran.core.source.engines.MySqlEngine;
import edu.upc.dtak.aran.core.source.engines.OracleEngine;
import edu.upc.dtak.aran.core.source.engines.PostgresEngine;
import edu.upc.dtak.aran.core.source.engines.SqlServerEngine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/**
 * Every {@link RelationalEngine} this build knows, indexed by id.
 *
 * <h2>Why an explicit list rather than component scanning</h2>
 *
 * <p>{@code SourceConnectors} collects its beans from the container, and this was written the same
 * way at first. It cannot be: {@code FederatedScanExpression} is called <em>statically</em> from
 * both {@code QueryEngineOperator} and {@code BootstrappingOperator}, so a container-only registry
 * would need a second, static copy of "which engines exist" — and two lists that can disagree about
 * that is precisely the drift this class was created to remove.
 *
 * <p>So there is one list, in {@link #DEFAULT}, and the Spring bean hands out that same instance.
 * Registration is explicit, which is also easier to grep, easier to test, and impossible to break
 * by moving a package. Adding an engine is one class and one line here.
 */
@NullMarked
public final class RelationalEngines {

    /**
     * The engines this build ships. Adding one here and nowhere else is the design's acceptance
     * test.
     */
    private static final RelationalEngines DEFAULT = new RelationalEngines(List.of(
            new PostgresEngine(),
            new MySqlEngine(),
            new MariaDbEngine(),
            new OracleEngine(),
            new SqlServerEngine(),
            new HBaseEngine()));

    private final Map<String, RelationalEngine> byId;

    public RelationalEngines(List<RelationalEngine> engines) {
        Map<String, RelationalEngine> index = new LinkedHashMap<>();
        for (RelationalEngine engine : engines) {
            String id = engine.id().toUpperCase(Locale.ROOT);
            RelationalEngine previous = index.put(id, engine);
            if (previous != null) {
                throw new IllegalStateException(
                        "two RelationalEngine implementations declare id '" + id + "': "
                        + previous.getClass().getName() + " and " + engine.getClass().getName());
            }
        }
        this.byId = Map.copyOf(index);
    }

    /** The shared registry. Stateless, so one instance serves every caller. */
    public static RelationalEngines defaults() {
        return DEFAULT;
    }

    /**
     * The engine for this id, if this build has one.
     *
     * <p>An unknown engine is {@code empty} rather than an exception: a repository row can name an
     * engine a later build dropped, and refusing to load it would take the whole project's
     * repository list down with it.
     */
    public Optional<RelationalEngine> find(@org.jspecify.annotations.Nullable String engineId) {
        return engineId == null
                ? Optional.empty()
                : Optional.ofNullable(byId.get(engineId.toUpperCase(Locale.ROOT)));
    }

    /** Every engine, for a registration form to offer. */
    public List<RelationalEngine> all() {
        return List.copyOf(byId.values());
    }
}
