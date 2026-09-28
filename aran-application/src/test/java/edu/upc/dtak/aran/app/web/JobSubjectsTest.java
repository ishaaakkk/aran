package edu.upc.dtak.aran.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.upc.dtak.aran.app.web.dto.JobDtos.JobSubjectDto;
import edu.upc.dtak.aran.core.domain.Job;
import edu.upc.dtak.aran.core.domain.NamedObjectRepository;
import edu.upc.dtak.aran.core.domain.NamedObjectRepository.NamedObject;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UI audit J8-F7: a list of jobs has to say what each is about in words a person recognises —
 * "Import · orders", "Rebuild · Bibliography" — not the ids its parameters store; and it may only
 * say it about objects the caller can see.
 */
@DisplayName("JobSubjects — what a job is about, named")
class JobSubjectsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID HIDDEN_PROJECT = UUID.randomUUID();
    private static final UUID REPO = UUID.randomUUID();

    private final AuthenticatedUser caller =
            new AuthenticatedUser(UUID.randomUUID(), "subject", TENANT, TenantRole.MEMBER);
    private final ProjectAccess projectAccess = mock(ProjectAccess.class);
    private final FakeNames names = new FakeNames();
    private final JobSubjects subjects = new JobSubjects(names, projectAccess);

    JobSubjectsTest() {
        when(projectAccess.visibleProjectIds(any())).thenReturn(Set.of(PROJECT));
    }

    /** Counts its calls, so the test can say how many queries a list costs. */
    private static final class FakeNames implements NamedObjectRepository {
        final Map<UUID, NamedObject> datasets = new HashMap<>();
        final Map<UUID, NamedObject> products = new HashMap<>();
        final Map<UUID, NamedObject> projects = new HashMap<>();
        int calls;

        private Map<UUID, NamedObject> pick(Map<UUID, NamedObject> from, Collection<UUID> ids) {
            if (!ids.isEmpty()) {
                calls++;
            }
            Map<UUID, NamedObject> out = new HashMap<>();
            ids.forEach(id -> {
                if (from.containsKey(id)) {
                    out.put(id, from.get(id));
                }
            });
            return out;
        }

        @Override public Map<UUID, NamedObject> datasets(TenantContext tenant, Collection<UUID> ids) {
            return pick(datasets, ids);
        }

        @Override public Map<UUID, NamedObject> products(TenantContext tenant, Collection<UUID> ids) {
            return pick(products, ids);
        }

        @Override public Map<UUID, NamedObject> projects(TenantContext tenant, Collection<UUID> ids) {
            return pick(projects, ids);
        }
    }

    private static Job job(String type, Map<String, String> parameters, @Nullable UUID projectId) {
        return Job.pending(UUID.randomUUID(), TENANT, projectId, type, parameters, null, Instant.now());
    }

    private @Nullable JobSubjectDto subjectOf(Job job) {
        return subjects.subjectsOf(caller, List.of(job)).get(job.id());
    }

    private UUID dataset(String name, UUID projectId) {
        UUID id = UUID.randomUUID();
        names.datasets.put(id, new NamedObject(id, name, projectId, REPO));
        return id;
    }

    @Test
    @DisplayName("a dataset job is named by the dataset, with the repository a retry needs")
    void datasetJobIsNamedByItsDataset() {
        UUID datasetId = dataset("orders", PROJECT);

        JobSubjectDto subject = subjectOf(
                job("dataset:import", Map.of("datasetId", datasetId.toString(), "tableName", "orders"), PROJECT));

        assertThat(subject).isEqualTo(new JobSubjectDto("dataset", datasetId, "orders", REPO));
    }

    @Test
    @DisplayName("a product job is named by the product")
    void productJobIsNamedByItsProduct() {
        UUID productId = UUID.randomUUID();
        names.products.put(productId, new NamedObject(productId, "top_authors", PROJECT, null));

        JobSubjectDto subject = subjectOf(job("product:materialize", Map.of("productId", productId.toString()), PROJECT));

        assertThat(subject).isEqualTo(new JobSubjectDto("product", productId, "top_authors", null));
    }

    @Test
    @DisplayName("a rebuild is named by its project")
    void modelJobIsNamedByItsProject() {
        names.projects.put(PROJECT, new NamedObject(PROJECT, "Bibliography", PROJECT, null));

        JobSubjectDto subject = subjectOf(job("model:compile", Map.of("projectId", PROJECT.toString()), PROJECT));

        assertThat(subject).isEqualTo(new JobSubjectDto("project", PROJECT, "Bibliography", null));
    }

    @Test
    @DisplayName("a deleted object keeps its id with no name, rather than vanishing from the list")
    void deletedObjectKeepsItsId() {
        UUID datasetId = UUID.randomUUID();

        JobSubjectDto subject = subjectOf(job("dataset:bootstrap", Map.of("datasetId", datasetId.toString()), PROJECT));

        assertThat(subject).isEqualTo(new JobSubjectDto("dataset", datasetId, null, null));
    }

    @Test
    @DisplayName("a job naming nothing has no subject, and a malformed id names nothing")
    void noSubject() {
        assertThat(subjectOf(job("operator:profiling:profile", Map.of("datasetId", "not-a-uuid"), null))).isNull();
    }

    @Test
    @DisplayName("a tenant-visible job about a dataset in a project the caller cannot see names nothing")
    void hiddenProjectsObjectIsNotNamed() {
        // A profiling run has no project, so every tenant member lists it; its dataset lives in a
        // project this caller is not a member of. Its name and repository are that project's data.
        UUID hidden = dataset("payroll_2026", HIDDEN_PROJECT);
        UUID shown = dataset("orders", PROJECT);

        Map<UUID, JobSubjectDto> named = subjects.subjectsOf(caller, List.of(
                job("operator:profiling:profile", Map.of("datasetId", hidden.toString()), null),
                job("operator:profiling:profile", Map.of("datasetId", shown.toString()), null)));

        assertThat(named.values()).extracting(JobSubjectDto::name).containsExactly("orders");
    }

    @Test
    @DisplayName("an object in its own job's project needs no membership lookup — the job's gate covered it")
    void sameProjectNeedsNoLookup() {
        UUID datasetId = dataset("orders", PROJECT);

        subjectOf(job("dataset:refresh", Map.of("datasetId", datasetId.toString()), PROJECT));

        verify(projectAccess, never()).visibleProjectIds(any());
    }

    @Test
    @DisplayName("a hundred jobs are named with one query per kind, and the caller's projects once")
    void listIsBatched() {
        List<Job> jobs = IntStream.range(0, 100).mapToObj(i -> switch (i % 3) {
            case 0 -> job("operator:profiling:profile",
                    Map.of("datasetId", dataset("d" + i, PROJECT).toString()), null);
            case 1 -> job("product:materialize", Map.of("productId", UUID.randomUUID().toString()), PROJECT);
            default -> job("model:compile", Map.of(), PROJECT);
        }).toList();

        subjects.subjectsOf(caller, jobs);

        assertThat(names.calls).isEqualTo(3);
        verify(projectAccess, times(1)).visibleProjectIds(any());
    }
}
