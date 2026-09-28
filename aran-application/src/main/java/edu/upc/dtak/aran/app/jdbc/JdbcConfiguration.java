package edu.upc.dtak.aran.app.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import org.apache.calcite.avatica.metrics.noop.NoopMetricsSystem;
import org.apache.calcite.avatica.remote.JsonHandler;
import org.apache.calcite.avatica.remote.LocalService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the Avatica JDBC endpoint.
 *
 * <p>Singletons on purpose. {@link AranMeta} holds the open statements of every JDBC session, so
 * one instance per application is what makes a {@code fetch} find the cursor its
 * {@code prepareAndExecute} opened. The per-request identity it needs comes from
 * {@link CurrentUserResolver}, which reads the security context, not from this bean's state.
 */
@Configuration
public class JdbcConfiguration {

    @Bean
    AranMeta aranMeta(OperatorOrchestrator orchestrator, CurrentUserResolver currentUser,
                      ObjectMapper mapper,
                      edu.upc.dtak.aran.core.service.UsageService usage,
                      edu.upc.dtak.aran.core.licensing.LicenseService licenses,
                      ProductRegistry products) {
        return new AranMeta(orchestrator, currentUser, mapper, usage, licenses, products);
    }

    /**
     * The {@code products} schema's view of the registry: the caller's tenant's data products,
     * named as SQL tables.
     *
     * <p>{@code ProductCatalog} does the naming and is shared with the query engine, which
     * registers the DuckDB views a client's SQL then reads. One derivation, two readers — a second
     * copy would offer a name in the tree that nothing could execute.
     */
    @Bean
    ProductRegistry jdbcProductRegistry(edu.upc.dtak.aran.core.service.DataProductService products) {
        return caller -> edu.upc.dtak.aran.core.domain.ProductCatalog.of(products.listAll(
                new edu.upc.dtak.aran.core.tenant.TenantContext(caller.tenantId().toString())));
    }

    @Bean
    JsonHandler avaticaJsonHandler(AranMeta meta) {
        // Avatica's metrics are a separate subsystem with its own reporters; Aran's observability
        // is Micrometer/OTel via the actuator, so the no-op keeps one metrics pipeline rather
        // than starting a second one nothing reads.
        return new JsonHandler(new LocalService(meta), NoopMetricsSystem.getInstance());
    }
}
