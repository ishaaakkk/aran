package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.DatasetRefresh;
import edu.upc.dtak.aran.core.domain.DatasetRefreshRepository;
import edu.upc.dtak.aran.core.domain.Membership;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.domain.User;
import edu.upc.dtak.aran.core.domain.UserRepository;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.source.RefreshPolicy;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Policy changes, and who a scheduled refresh runs as.
 *
 * <p>The identity question is the one worth pinning. A schedule has no session, and the easy answer
 * — a system principal that skips authorization — is an ambient superuser that would keep reading a
 * source long after the only person who could see it had lost access. These tests hold the harder
 * answer in place: the schedule runs as the person who set it, checked live on every tick.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatasetRefreshServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID REPOSITORY = UUID.randomUUID();
    private static final UUID DATASET = UUID.randomUUID();
    private static final UUID CREATOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-15T10:00:00Z");

    @Mock private DatasetService datasets;
    @Mock private DataRepositoryService repositories;
    @Mock private IdentityService identity;
    @Mock private UserRepository users;

    private final InMemoryRefreshes store = new InMemoryRefreshes();
    private DatasetRefreshService service;

    /** Records saves so the tests can assert on state rather than on interactions. */
    private static final class InMemoryRefreshes implements DatasetRefreshRepository {
        final Map<UUID, DatasetRefresh> byDataset = new LinkedHashMap<>();
        final List<DatasetRefresh> due = new ArrayList<>();
        Instant claimedAt;
        int claimedLimit;

        @Override public Optional<DatasetRefresh> findByDataset(TenantContext t, UUID datasetId) {
            return Optional.ofNullable(byDataset.get(datasetId));
        }

        @Override public List<DatasetRefresh> findAllByRepository(TenantContext t, UUID repositoryId) {
            return List.copyOf(byDataset.values());
        }

        @Override public DatasetRefresh save(TenantContext t, DatasetRefresh refresh) {
            byDataset.put(refresh.datasetId(), refresh);
            return refresh;
        }

        @Override public void delete(TenantContext t, UUID datasetId) {
            byDataset.remove(datasetId);
        }

        @Override public List<DatasetRefresh> claimDue(Instant now, int limit) {
            claimedAt = now;
            claimedLimit = limit;
            return List.copyOf(due);
        }
    }

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(CREATOR, "sub", TENANT, TenantRole.OWNER);
    }

    private static DatasetRefresh manual() {
        return DatasetRefresh.manual(DATASET, TENANT, REPOSITORY, PROJECT, CREATOR, "orders");
    }

    @BeforeEach
    void setUp() {
        service = new DatasetRefreshService(store, datasets, repositories, identity, users);
        when(users.findById(CREATOR)).thenReturn(Optional.of(
                new User(CREATOR, "sub", "a@b.c", "Creator", NOW)));
        when(identity.soleMembership(CREATOR)).thenReturn(Optional.of(
                new Membership(UUID.randomUUID(), CREATOR, TENANT, TenantRole.OWNER, NOW)));
    }

    @Test
    @DisplayName("a dataset with no upstream cannot be given a schedule, and is told why")
    void no_upstream_refuses_a_policy() {
        assertThatThrownBy(() -> service.setPolicy(caller(), PROJECT, REPOSITORY, DATASET,
                new RefreshPolicy.Every(Duration.ofHours(1))))
                .isInstanceOf(DatasetRefreshService.NoUpstreamException.class)
                .hasMessageContaining("uploaded");

        assertThat(store.byDataset)
                .describedAs("a policy that could never run must not be stored looking as if it will")
                .isEmpty();
    }

    @Test
    @DisplayName("a dataset with no upstream cannot be refreshed on demand either")
    void no_upstream_refuses_a_refresh() {
        assertThatThrownBy(() -> service.refreshNow(caller(), PROJECT, REPOSITORY, DATASET))
                .isInstanceOf(DatasetRefreshService.NoUpstreamException.class);

        verify(datasets, never()).refreshDataset(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("setting a schedule stores it with a next run; setting manual clears it")
    void policy_round_trip() {
        store.save(null, manual());

        DatasetRefresh scheduled = service.setPolicy(caller(), PROJECT, REPOSITORY, DATASET,
                new RefreshPolicy.Every(Duration.ofHours(1)));
        assertThat(scheduled.nextRunAt()).isNotNull();

        DatasetRefresh back = service.setPolicy(caller(), PROJECT, REPOSITORY, DATASET,
                new RefreshPolicy.Manual());
        assertThat(back.nextRunAt()).isNull();
    }

    @Test
    @DisplayName("refresh-now works under a schedule too, rather than forcing a shortened interval")
    void refresh_now_under_a_schedule() {
        // Refusing it would push a user into shortening the interval to force one refresh, which is
        // worse for the source database than the single refresh they actually wanted.
        store.save(null, manual().withPolicy(new RefreshPolicy.Every(Duration.ofDays(1)), NOW));

        service.refreshNow(caller(), PROJECT, REPOSITORY, DATASET);

        verify(datasets).refreshDataset(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a scheduled refresh runs as the person who set it, not as an ambient superuser")
    void scheduled_refresh_runs_as_its_creator() {
        store.due.add(manual().withPolicy(new RefreshPolicy.Every(Duration.ofHours(1)), NOW));

        assertThat(service.runDue(NOW)).isEqualTo(1);

        var captor = org.mockito.ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(datasets).refreshDataset(captor.capture(), any(), any(), any(), any());
        assertThat(captor.getValue().userId()).isEqualTo(CREATOR);
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("a schedule whose creator was deleted fails visibly instead of running anyway")
    void deleted_creator_stops_the_schedule() {
        // The alternative is a refresh that keeps reading a source on the authority of an account
        // that no longer exists — which is exactly what a system principal would have done.
        store.due.add(manual().withPolicy(new RefreshPolicy.Every(Duration.ofHours(1)), NOW));
        store.save(null, store.due.getFirst());
        when(users.findById(CREATOR)).thenReturn(Optional.empty());

        assertThat(service.runDue(NOW)).isZero();

        verify(datasets, never()).refreshDataset(any(), any(), any(), any(), any());
        assertThat(store.byDataset.get(DATASET).lastError())
                .describedAs("the reason belongs where a user looks for 'why is this stale'")
                .contains("no longer exists");
    }

    @Test
    @DisplayName("a creator who moved to another tenant cannot carry the schedule with them")
    void creator_in_a_different_tenant_stops_the_schedule() {
        store.due.add(manual().withPolicy(new RefreshPolicy.Every(Duration.ofHours(1)), NOW));
        store.save(null, store.due.getFirst());
        when(identity.soleMembership(CREATOR)).thenReturn(Optional.of(new Membership(
                UUID.randomUUID(), CREATOR, UUID.randomUUID(), TenantRole.OWNER, NOW)));

        assertThat(service.runDue(NOW)).isZero();
        assertThat(store.byDataset.get(DATASET).lastError()).contains("no longer belongs");
    }

    @Test
    @DisplayName("one broken schedule does not stop the others")
    void a_failure_does_not_stop_the_batch() {
        // One unreachable source database must not silently freeze every other tenant's schedule.
        UUID otherDataset = UUID.randomUUID();
        UUID otherCreator = UUID.randomUUID();
        store.due.add(manual().withPolicy(new RefreshPolicy.Every(Duration.ofHours(1)), NOW));
        store.due.add(DatasetRefresh.manual(otherDataset, TENANT, REPOSITORY, PROJECT, otherCreator, "t")
                .withPolicy(new RefreshPolicy.Every(Duration.ofHours(1)), NOW));
        when(users.findById(otherCreator)).thenReturn(Optional.of(
                new User(otherCreator, "sub2", "c@d.e", "Other", NOW)));
        when(identity.soleMembership(otherCreator)).thenReturn(Optional.of(
                new Membership(UUID.randomUUID(), otherCreator, TENANT, TenantRole.OWNER, NOW)));

        Map<UUID, Boolean> attempted = new HashMap<>();
        when(datasets.refreshDataset(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            UUID datasetId = inv.getArgument(3);
            attempted.put(datasetId, true);
            if (datasetId.equals(DATASET)) {
                throw new IllegalStateException("source unreachable");
            }
            return null;
        });

        assertThat(service.runDue(NOW)).isEqualTo(1);
        assertThat(attempted).containsOnlyKeys(DATASET, otherDataset);
    }

    @Test
    @DisplayName("one poll is bounded, so a backlog is worked through rather than started at once")
    void poll_is_bounded() {
        service.runDue(NOW);

        assertThat(store.claimedAt).isEqualTo(NOW);
        assertThat(store.claimedLimit)
                .isEqualTo(DatasetRefreshService.MAX_PER_POLL)
                .isLessThan(1000);
    }
}
