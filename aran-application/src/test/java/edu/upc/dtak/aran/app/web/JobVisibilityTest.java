package edu.upc.dtak.aran.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.service.JobService;
import edu.upc.dtak.aran.core.service.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The job read routes' project gate, Docker-free; {@code ProjectScopedAuthorizationIT} pins the
 * same rule over HTTP against Postgres.
 */
@DisplayName("JobVisibility — a project-scoped job is readable only by the project's members")
class JobVisibilityTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MINE = UUID.randomUUID();
    private static final UUID THEIRS = UUID.randomUUID();

    private final AuthenticatedUser caller =
            new AuthenticatedUser(UUID.randomUUID(), "bob", TENANT, TenantRole.MEMBER);
    private final JobService jobService = mock(JobService.class);
    private final ProjectAccess projectAccess = mock(ProjectAccess.class);
    private final JobVisibility visibility = new JobVisibility(jobService, projectAccess);

    {
        when(projectAccess.getVisibleProject(caller, MINE)).thenReturn(new Project(MINE, TENANT,
                "mine", "", caller.userId(), Instant.now(), "#000000", "PRIVATE", "ACTIVE", 0));
        when(projectAccess.getVisibleProject(caller, THEIRS))
                .thenThrow(new ResourceNotFoundException("project not found: " + THEIRS));
        when(projectAccess.visibleProjectIds(caller)).thenReturn(Set.of(MINE));
    }

    private static Job job(@Nullable UUID projectId) {
        return Job.pending(UUID.randomUUID(), TENANT, projectId, "model:compile", Map.of(), null,
                Instant.now());
    }

    private void stored(Job job) {
        when(jobService.find(any(), eq(job.id()))).thenReturn(Optional.of(job));
    }

    @Test
    @DisplayName("another project's job is not found, and the answer does not name the project")
    void anotherProjectsJobIsNotFound() {
        Job theirs = job(THEIRS);
        stored(theirs);

        assertThatThrownBy(() -> visibility.requireVisible(caller, theirs.id()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(theirs.id().toString())
                .hasMessageNotContaining(THEIRS.toString());
    }

    @Test
    @DisplayName("the caller's own project's job and an unscoped job are readable")
    void visibleAndUnscopedJobsAreReadable() {
        Job mine = job(MINE);
        Job unscoped = job(null);
        stored(mine);
        stored(unscoped);

        assertThat(visibility.requireVisible(caller, mine.id())).isEqualTo(mine);
        assertThat(visibility.requireVisible(caller, unscoped.id())).isEqualTo(unscoped);
    }

    @Test
    @DisplayName("the tenant-wide listing drops the jobs of projects the caller cannot see")
    void listingDropsOtherProjectsJobs() {
        Job mine = job(MINE);
        Job theirs = job(THEIRS);
        Job unscoped = job(null);
        when(jobService.listRecent(any(), isNull(), eq(20))).thenReturn(List.of(mine, theirs, unscoped));

        assertThat(visibility.listRecent(caller, null, 20)).containsExactly(mine, unscoped);
    }

    @Test
    @DisplayName("listing by a project the caller cannot see is not found, before any job is read")
    void listingByAnInvisibleProjectIsNotFound() {
        assertThatThrownBy(() -> visibility.listRecent(caller, THEIRS, 20))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(jobService);

        visibility.listRecent(caller, MINE, 20);
        verify(jobService).listRecent(any(), eq(MINE), eq(20));
    }
}
