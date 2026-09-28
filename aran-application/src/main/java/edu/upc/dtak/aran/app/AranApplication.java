package edu.upc.dtak.aran.app;

import edu.upc.dtak.aran.core.config.CoreConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Aran — application entry point.
 *
 * <p>This is the single deployable artifact. It assembles three things into a
 * bootable application:
 * <ul>
 *   <li>the rewritten <strong>core</strong> ({@link CoreConfiguration}),
 *       imported explicitly rather than scanned;</li>
 *   <li>the <strong>adapters</strong> on the runtime classpath (persistence),
 *       picked up by Spring Boot autoconfiguration;</li>
 *   <li>the Spring-managed <strong>operators</strong> on the runtime classpath,
 *       discovered by Spring Boot component scanning.</li>
 * </ul>
 *
 * <p>{@code @SpringBootApplication}'s component scan is configured to scan the
 * {@code edu.upc.dtak.aran.app} (web controllers) and {@code edu.upc.dtak.aran.operators}
 * packages, while the core is imported via {@code @Import} to maintain clear module boundaries.
 */
@SpringBootApplication(scanBasePackages = {"edu.upc.dtak.aran.app", "edu.upc.dtak.aran.operators"})
@Import(CoreConfiguration.class)
public class AranApplication {

    public static void main(String[] args) {
        SpringApplication.run(AranApplication.class, args);
    }
}
