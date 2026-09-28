package edu.upc.dtak.aran.adapter.iceberg;

import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the Iceberg REST catalog client for the core's {@link IcebergCatalogClient} port —
 * self-registering auto-configuration for the same reason the persistence adapter's is: the
 * application wires this module without referencing any of its types.
 */
@AutoConfiguration
public class IcebergAdapterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IcebergCatalogClient icebergCatalogClient() {
        return new HttpIcebergCatalogClient();
    }
}
