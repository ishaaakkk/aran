package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.licensing.LicenseAdminPort;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TenantAdminService} must be constructible when no {@link LicenseAdminPort} bean exists.
 *
 * <p>The regression this guards: the port was a hard constructor argument, while
 * {@code SignedTokenLicensingConfig} deliberately registers a {@code LicenseProvider} and no
 * admin port — a customer's instance is not the license-issuance authority. That combination makes
 * this bean unsatisfiable and takes the whole application context down with it. No test or compose
 * file boots {@code aran.licensing.mode=signed-token}, so nothing would notice.
 *
 * <p>A context test would need Docker for the datasource; the wiring contract is expressible
 * without one, so it is tested here instead and stays in the {@code --offline} subset.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantAdminService — LicenseAdminPort wiring")
class TenantAdminServiceLicenseAdminWiringTest {

    @Mock TenantRepository tenants;
    @Mock UserRepository users;
    @Mock LicenseService licensing;
    @Mock AuditService audit;
    @Mock GraphStore graphStore;

    /**
     * Mocked rather than hand-implemented: {@code ObjectProvider} is not a functional interface
     * under Spring Boot 4, and {@code getIfAvailable()} is the whole of the contract used here —
     * Spring returns {@code null} from it when nothing registered the type.
     */
    private static ObjectProvider<LicenseAdminPort> providerOf(@Nullable LicenseAdminPort port) {
        @SuppressWarnings("unchecked")
        ObjectProvider<LicenseAdminPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        return provider;
    }

    private static ObjectProvider<LicenseAdminPort> absent() {
        return providerOf(null);
    }

    private TenantAdminService serviceWith(ObjectProvider<LicenseAdminPort> port) {
        return new TenantAdminService(tenants, users, port, licensing, audit, graphStore);
    }

    @Test
    @DisplayName("constructs when no LicenseAdminPort is registered")
    void constructsWithoutAnAdminPort() {
        assertThatCode(() -> serviceWith(absent())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses tenant creation without an issuer, before writing anything")
    void refusesTenantCreationWithoutAnIssuer() {
        TenantAdminService service = serviceWith(absent());

        assertThatThrownBy(() ->
                service.createTenant("acme", "Acme", LicensePlan.FREE, "subject"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aran.licensing.mode");

        // The guard runs first, so no half-built tenant is left behind for the caller to clean up.
        verifyNoInteractions(tenants, users, graphStore);
    }

    @Test
    @DisplayName("uses the port when one is registered")
    void usesThePortWhenPresent() {
        LicenseAdminPort port = (tenantId, plan) -> { };
        assertThatCode(() -> serviceWith(providerOf(port))).doesNotThrowAnyException();
    }

    /**
     * The above construct the service by hand, which proves the null-handling but not that Spring
     * resolves an {@code ObjectProvider} constructor argument at all. The suite that would have
     * covered that — the {@code @SpringBootTest} integration tests — needs Docker for its
     * datasource, so it is asserted here against a real container instead.
     */
    @Nested
    @DisplayName("resolved by the Spring container")
    class SpringWiring {

        private final ApplicationContextRunner contexts = new ApplicationContextRunner()
                .withBean(TenantRepository.class, () -> mock(TenantRepository.class))
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(LicenseService.class, () -> mock(LicenseService.class))
                .withBean(AuditService.class, () -> mock(AuditService.class))
                .withBean(GraphStore.class, () -> mock(GraphStore.class))
                .withBean(TenantAdminService.class);

        @Test
        @DisplayName("starts with no LicenseAdminPort bean — the signed-token case")
        void startsWithoutTheOptionalPort() {
            contexts.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(TenantAdminService.class);
            });
        }

        @Test
        @DisplayName("starts with a LicenseAdminPort bean — the database and unlimited cases")
        void startsWithTheOptionalPort() {
            contexts.withBean(LicenseAdminPort.class, () -> (tenantId, plan) -> { })
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(TenantAdminService.class);
                    });
        }
    }
}
