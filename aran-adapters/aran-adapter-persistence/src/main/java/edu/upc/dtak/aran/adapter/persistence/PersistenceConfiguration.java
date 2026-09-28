package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Auto-configuration for the persistence adapter.
 *
 * <p>The adapter lives in its own module and package, outside the
 * application's component-scan root, and is wired in as {@code runtimeOnly} —
 * the application never references this class by type. So instead of being
 * {@code @Import}ed, it registers itself as a Spring Boot
 * {@code @AutoConfiguration} (see
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}).
 * Boot picks it up at startup.
 *
 * <p>It declares where the adapter's JPA entities and Spring Data repositories
 * live. The application does not (and should not) scan adapter internals
 * itself — the only contract between them is the set of core port beans this
 * module publishes ({@code JpaUserRepository}, {@code JpaTenantRepository},
 * {@code JpaProjectRepository}), which are picked up via the {@code @Component}
 * scan triggered from here.
 */
@AutoConfiguration
@EntityScan(basePackages = "edu.upc.dtak.aran.adapter.persistence.entity")
@EnableJpaRepositories(
        basePackages = "edu.upc.dtak.aran.adapter.persistence.jpa",
        considerNestedRepositories = true)
@org.springframework.context.annotation.ComponentScan(
        basePackages = "edu.upc.dtak.aran.adapter.persistence")
public class PersistenceConfiguration {
}