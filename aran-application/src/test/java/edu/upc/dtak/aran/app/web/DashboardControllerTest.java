package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.DashboardDtos.DashboardSummary;
import edu.upc.dtak.aran.core.domain.AuditEvent;
import edu.upc.dtak.aran.core.domain.AuditPort;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dashboard's read model, over stubbed ports.
 *
 * <p>Exists because {@code readyDatasets} was silently 0 for every tenant: the count compared the
 * string {@code "READY"} with a {@link DatasetStatus}, which no dataset can ever equal. A number
 * that is always zero looks like an empty install rather than a bug, so nothing but an assertion
 * over a seeded mix of statuses can hold it honest.
 */
@DisplayName("Dashboard summary — the counts it derives from the ports")
class DashboardControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-00000000000d");
    private static final UUID USER = UUID.fromString("22222222-0000-0000-0000-00000000000d");
    private static final UUID PROJECT = UUID.fromString("33333333-0000-0000-0000-00000000000d");
    private static final UUID REPO = UUID.fromString("44444444-0000-0000-0000-00000000000d");

    private final ProjectService projectService = mock(ProjectService.class);
    private final DataRepositoryRepository repositories = mock(DataRepositoryRepository.class);
    private final DatasetRepository datasets = mock(DatasetRepository.class);
    private final GraphStore graphStore = mock(GraphStore.class);
    private final GraphVersionPort versions = mock(GraphVersionPort.class);
    private final AuditPort audit = mock(AuditPort.class);
    private final CurrentUserResolver currentUser = mock(CurrentUserResolver.class);

    private DashboardController controller;

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(USER, "subject", TENANT, TenantRole.OWNER);
    }

    private static Project project() {
        return new Project(PROJECT, TENANT, "papers", "", USER, Instant.EPOCH,
                "#000000", "PRIVATE", "ACTIVE", 0);
    }

    private static DataRepository repository() {
        return new FileSystemRepository(REPO, PROJECT, TENANT, "uploads", "");
    }

    private static Dataset dataset(String name, DatasetStatus status) {
        return new Dataset(UUID.randomUUID(), REPO, name, "", null, List.of(), null, status);
    }

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(caller());
        when(projectService.listVisibleProjects(any())).thenReturn(List.of(project()));
        when(repositories.findAllByProject(any(), eq(PROJECT))).thenReturn(List.of(repository()));
        when(audit.recent(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        controller = new DashboardController(projectService, repositories, datasets, graphStore,
                versions, audit, currentUser);
    }

    @Test
    @DisplayName("readyDatasets counts the bootstrapped ones, not zero and not all of them")
    void countsOnlyReadyDatasets() {
        when(datasets.findAllByRepository(any(), eq(REPO))).thenReturn(List.of(
                dataset("ready-one", DatasetStatus.READY),
                dataset("ready-two", DatasetStatus.READY),
                dataset("still-running", DatasetStatus.RUNNING),
                dataset("failed", DatasetStatus.FAILED),
                dataset("drifted", DatasetStatus.DRIFTED),
                // Pre-bootstrap rows carry no status at all; they are not ready either.
                dataset("never-bootstrapped", null)));

        DashboardSummary summary = controller.summary();

        assertThat(summary.datasets()).isEqualTo(6);
        assertThat(summary.readyDatasets()).isEqualTo(2);
        assertThat(summary.projects()).isEqualTo(1);
        assertThat(summary.repositories()).isEqualTo(1);
    }

    @Test
    @DisplayName("no ready dataset counts zero — the honest zero, not the accidental one")
    void countsZeroWhenNothingIsReady() {
        when(datasets.findAllByRepository(any(), eq(REPO)))
                .thenReturn(List.of(dataset("still-running", DatasetStatus.RUNNING)));

        assertThat(controller.summary().readyDatasets()).isZero();
    }

    private static AuditEvent line(String action, String targetType, UUID targetId) {
        return new AuditEvent(UUID.randomUUID(), TENANT, USER, action, targetType, targetId,
                java.util.Map.of(), Instant.EPOCH);
    }

    @Test
    @DisplayName("UI-OD11: a member's feed is only the lines of projects they can see")
    void memberFeedIsScopedToVisibleProjects() {
        when(currentUser.require())
                .thenReturn(new AuthenticatedUser(USER, "subject", TENANT, TenantRole.MEMBER));
        when(datasets.findAllByRepository(any(), eq(REPO))).thenReturn(List.of());
        when(audit.recentForProjects(any(), eq(List.of(PROJECT)), anyInt()))
                .thenReturn(List.of(line("project.created", "project", PROJECT)));

        DashboardSummary summary = controller.summary();

        assertThat(summary.recentAudit()).extracting(a -> a.action())
                .containsExactly("project.created");
        // The unscoped read is the one that leaked tenant governance lines to every member.
        verify(audit, never()).recent(any(), anyInt());
    }

    @Test
    @DisplayName("UI-OD11: an owner's feed is the whole tenant's, as /admin/activity is")
    void ownerFeedIsTenantWide() {
        when(datasets.findAllByRepository(any(), eq(REPO))).thenReturn(List.of());
        when(audit.recent(any(), anyInt()))
                .thenReturn(List.of(line("tenant.member.removed", "tenant", TENANT)));

        assertThat(controller.summary().recentAudit()).extracting(a -> a.action())
                .containsExactly("tenant.member.removed");
        verify(audit, never()).recentForProjects(any(), any(), anyInt());
    }

    private static GraphVersion version(int number, String status) {
        return new GraphVersion(UUID.randomUUID(), TENANT, number, status, Instant.EPOCH, null, null, null);
    }

    @Test
    @DisplayName("a compiled draft newer than the active version is reported, so the first-run steps can say 'promote vN' (J1-F2)")
    void reportsADraftAwaitingPromotion() {
        when(datasets.findAllByRepository(any(), eq(REPO))).thenReturn(List.of());
        when(graphStore.currentVersion(any())).thenReturn(version(2, "ACTIVE"));
        when(versions.findLatest(any())).thenReturn(Optional.of(version(3, "DRAFT")));

        DashboardSummary summary = controller.summary();

        assertThat(summary.activeSchemaVersion()).isEqualTo(2);
        assertThat(summary.draftSchemaVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("no draft is reported when the newest version is the active one")
    void noDraftWhenTheLatestIsActive() {
        when(datasets.findAllByRepository(any(), eq(REPO))).thenReturn(List.of());
        GraphVersion active = version(4, "ACTIVE");
        when(graphStore.currentVersion(any())).thenReturn(active);
        when(versions.findLatest(any())).thenReturn(Optional.of(active));

        assertThat(controller.summary().draftSchemaVersion()).isNull();
    }
}
