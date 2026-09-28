package edu.upc.dtak.aran.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the Aran core.
 *
 * <p>Component scanning is scoped to {@code edu.upc.dtak.aran.core} only.
 * The application module imports this configuration explicitly rather than
 * relying on a broad scan from the boot class.
 */
@Configuration
@ComponentScan(basePackages = "edu.upc.dtak.aran.core")
public class CoreConfiguration {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
