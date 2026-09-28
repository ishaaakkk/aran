package edu.upc.dtak.aran.app.config;

import edu.upc.dtak.aran.core.domain.IdentityProviderPort;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.Tenant;
import edu.upc.dtak.aran.core.domain.TenantRepository;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The first-run owner. The class javadoc's security claim — no published credential survives a
 * non-dev boot — is what these tests hold it to, including the branch that used to break it:
 * adopting an account seeded by the realm import and leaving its published password alone.
 */
class AdminBootstrapTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final IdentityProviderPort identity = mock(IdentityProviderPort.class);

    private AdminBootstrap bootstrap(String profile) {
        MockEnvironment env = new MockEnvironment();
        if (profile != null) {
            env.setActiveProfiles(profile);
        }
        return new AdminBootstrap(tenants, users, identity, env,
                "default", "Default", "alice", "alice@aran.local", "Aran Administrator", "");
    }

    private void freshDatabase() {
        when(tenants.findBySlug("default")).thenReturn(Optional.empty());
        when(tenants.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(users.findBySubject(anyString())).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("dev profile: does nothing — the realm import already seeds alice/aran")
    void devDoesNothing() {
        bootstrap("dev").run(null);

        verify(tenants, never()).save(any(Tenant.class));
        verify(identity, never()).createUser(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("non-dev, no existing account: creates it with a generated temporary password")
    void freshInstallGeneratesAPassword() {
        freshDatabase();
        when(identity.findSubjectByUsername("alice")).thenReturn(Optional.empty());
        when(identity.createUser(anyString(), anyString(), anyString())).thenReturn("subject-1");

        bootstrap("standalone").run(null);

        ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
        verify(identity).setTemporaryPassword(anyString(), password.capture());
        assertThat(password.getValue()).hasSize(20).isNotEqualTo("aran");
        verify(tenants).saveMembership(any(Membership.class));
    }

    @Test
    @DisplayName("non-dev, account adopted from a realm import: its password is ROTATED, not kept")
    void adoptedAccountIsRotated() {
        // The branch that used to break the class's own security claim: outside dev, the adopt
        // path only runs on an empty database — a fresh install — and there the existing
        // credential is the realm import's published alice/aran. Leaving it valid is exactly
        // the window the javadoc says must not exist.
        freshDatabase();
        when(identity.findSubjectByUsername("alice")).thenReturn(Optional.of("subject-1"));

        bootstrap("standalone").run(null);

        ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
        verify(identity).setTemporaryPassword(anyString(), password.capture());
        assertThat(password.getValue()).hasSize(20).isNotEqualTo("aran");
        verify(identity, never()).createUser(anyString(), anyString(), anyString());
        verify(tenants).saveMembership(any(Membership.class));
    }

    @Test
    @DisplayName("an existing tenant means a done bootstrap — nothing runs twice")
    void existingTenantIsANoOp() {
        when(tenants.findBySlug("default")).thenReturn(Optional.of(
                new Tenant(java.util.UUID.randomUUID(), "default", "Default",
                        java.time.Instant.now())));

        bootstrap("standalone").run(null);

        verify(identity, never()).findSubjectByUsername(anyString());
        verify(tenants, never()).save(any(Tenant.class));
    }

    @Test
    @DisplayName("failed Keycloak provisioning leaves no Aran rows — provision first, commit second")
    void failedProvisioningLeavesNoRows() {
        freshDatabase();
        when(identity.findSubjectByUsername("alice")).thenReturn(Optional.empty());
        when(identity.createUser(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("keycloak down"));

        assertThatThrownBy(() -> bootstrap("standalone").run(null))
                .isInstanceOf(IllegalStateException.class);

        verify(tenants, never()).save(any(Tenant.class));
        verify(users, never()).save(any(User.class));
    }
}
