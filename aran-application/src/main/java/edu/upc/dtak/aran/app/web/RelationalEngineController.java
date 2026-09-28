package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.core.source.RelationalEngine;
import edu.upc.dtak.aran.core.source.RelationalEngines;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What each relational engine can actually do, so the registration form stops guessing.
 *
 * <p>Gate 0 made capability honest at the <em>repository type</em> level: {@code RELATIONAL_DB} is
 * marked available and {@code GET /api/repository-types} publishes that. One level down it was
 * still overclaiming — the engine dropdown offered ORACLE and HBASE under a type labelled
 * available, and neither can move a row. This is the same fix, one level deeper.
 *
 * <p>Publishes no scan expressions and no connection strings: a form needs to know what an engine
 * can do and which port to prefill, not how Aran would talk to it.
 */
@RestController
@RequestMapping("/api/relational-engines")
public class RelationalEngineController {

    /**
     * The static registry, exactly as every other call site reads it.
     *
     * <p>This used to be an injected constructor parameter, with a no-arg constructor beside it
     * falling back to {@code defaults()}. Spring picks the no-arg constructor when a class declares
     * two and neither is annotated, so the injected path never ran and the {@code @Bean} that fed
     * it ({@code SourceAccessConfig}) was dead — deleted 2026-08-27, audit SOURCE_REGISTRATION-12.
     * Reading the registry statically also keeps the design's own rule: engines are declared in one
     * explicit list ({@code RelationalEngines.DEFAULT}), never a second one the container assembles.
     */
    private final RelationalEngines engines = RelationalEngines.defaults();

    @GetMapping
    public List<RelationalEngineResponse> listEngines() {
        return engines.all().stream()
                .map(e -> new RelationalEngineResponse(
                        e.id(),
                        e.displayName(),
                        e.defaultPort(),
                        e.duckDbExtension().isPresent(),
                        e.bulkExtract().name(),
                        e.bulkExtract().isImplemented()))
                .toList();
    }

    /**
     * @param supportsFederation whether this engine has a live scanner at all — the capability
     *                           question, independent of whether any repository asked for it
     * @param bulkExtract        the mechanism this engine's rows would reach the lake by
     * @param bulkExtractReady   whether that mechanism is built in this release. Separate from
     *                           {@code bulkExtract} on purpose: an engine naming a mechanism and a
     *                           build implementing it are different facts, and merging them is how
     *                           a capability comes to be advertised before it exists
     */
    public record RelationalEngineResponse(
            String id,
            String displayName,
            int defaultPort,
            boolean supportsFederation,
            String bulkExtract,
            boolean bulkExtractReady) {

        /** Whether a dataset under this engine can be filled at all today. */
        public boolean canIngest() {
            return supportsFederation || bulkExtractReady;
        }
    }
}
