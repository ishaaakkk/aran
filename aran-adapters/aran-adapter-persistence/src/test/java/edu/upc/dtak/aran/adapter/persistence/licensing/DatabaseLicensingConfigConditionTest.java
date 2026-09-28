package edu.upc.dtak.aran.adapter.persistence.licensing;

import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.LicenseJpaRepository;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import edu.upc.dtak.aran.core.licensing.LicenseProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link DatabaseLicensingConfig} must contribute its beans in {@code database} mode and in no
 * other, so that another mode's {@link LicenseProvider} has the seam to itself.
 *
 * <p>The regression this guards survived unnoticed until a third licensing mode was added. The
 * configuration has always carried {@code @ConditionalOnProperty(havingValue = "database")}, but
 * its two beans were nested {@code @Component} classes — and this package sits inside
 * {@code PersistenceConfiguration}'s {@code @ComponentScan}. A scan evaluates the conditions
 * declared on the class it found, not those on that class's enclosing type, so both beans were
 * registered in every mode. {@code signed-token} would have started with two providers and failed
 * on {@code NoUniqueBeanDefinitionException}. Nothing caught it, because {@code database} is the
 * only mode any test or compose file boots.
 */
@DisplayName("DatabaseLicensingConfig — mode condition")
class DatabaseLicensingConfigConditionTest {

    // LicensePlansProperties is deliberately not registered here: the configuration's own
    // @EnableConfigurationProperties supplies it, and adding a second would be the test's bug.
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(LicenseJpaRepository.class, () -> mock(LicenseJpaRepository.class))
            .withUserConfiguration(DatabaseLicensingConfig.class);

    @Test
    @DisplayName("contributes the provider and the admin port in database mode")
    void contributesInDatabaseMode() {
        contexts.withPropertyValues("aran.licensing.mode=database").run(context -> {
            assertThat(context).hasSingleBean(LicenseProvider.class);
            assertThat(context).hasSingleBean(LicenseAdminPort.class);
        });
    }

    @Test
    @DisplayName("stays the default when the mode is unset")
    void contributesWhenModeUnset() {
        // matchIfMissing = true: an existing cloud deployment that never set the property keeps
        // working exactly as before.
        contexts.run(context -> assertThat(context).hasSingleBean(LicenseProvider.class));
    }

    @Test
    @DisplayName("contributes nothing in the other modes")
    void contributesNothingOtherwise() {
        for (String mode : new String[] {"unlimited", "signed-token"}) {
            contexts.withPropertyValues("aran.licensing.mode=" + mode).run(context -> {
                assertThat(context).doesNotHaveBean(LicenseProvider.class);
                assertThat(context).doesNotHaveBean(LicenseAdminPort.class);
            });
        }
    }

    @Test
    @DisplayName("a package scan does not register the beans behind the condition's back")
    void componentScanDoesNotBypassTheCondition() {
        // Asserted against a real scan, because that is the mechanism that broke — the runner
        // above imports the configuration directly and would never have reproduced it.
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("aran.licensing.mode", "unlimited");

        try (AnnotationConfigApplicationContext scanned = new AnnotationConfigApplicationContext()) {
            scanned.setEnvironment(environment);
            scanned.scan(getClass().getPackageName());

            assertThat(scanned.getBeanDefinitionNames())
                    .noneMatch(name -> name.contains("DatabaseLicenseProvider")
                            || name.contains("DatabaseLicenseAdminPort"));
        }
    }
}
