package edu.upc.dtak.aran.app.config;

import edu.upc.dtak.aran.core.net.OutboundAddressGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The outbound-address guard as one shared bean, so every path that opens a connection to a
 * user-supplied host screens it the same way (sec-audit F-S1-1/F-S1-11). Before this, only
 * {@code DataRepositoryService}'s test/scan/preview paths screened; the bootstrap, import,
 * catalog-harvest and object-storage paths connected unscreened, so with
 * {@code block-private-networks} on the cloud-metadata endpoint and internal hosts were reachable
 * there even though test/scan would refuse them.
 *
 * <p>Reads the same {@code aran.security.block-private-networks} property
 * {@code DataRepositoryService} does — off by default because Aran is normally deployed beside the
 * databases it federates, and flipped on for a multi-tenant SaaS.
 */
@Configuration
class OutboundGuardConfig {

    @Bean
    OutboundAddressGuard outboundAddressGuard(
            @Value("${aran.security.block-private-networks:false}") boolean blockPrivateNetworks) {
        return new OutboundAddressGuard(blockPrivateNetworks);
    }
}
