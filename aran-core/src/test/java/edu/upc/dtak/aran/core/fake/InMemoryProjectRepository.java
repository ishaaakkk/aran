package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryProjectRepository implements ProjectRepository {

    private final Map<UUID, Project> projects = new HashMap<>();
    private final Map<UUID, ProjectMember> members = new HashMap<>();

    @Override
    public Optional<Project> findById(TenantContext tenant, UUID projectId) {
        return Optional.ofNullable(projects.get(projectId))
                .filter(p -> p.tenantId().toString().equals(tenant.value()));
    }

    @Override
    public Optional<Project> findVisibleToUser(TenantContext tenant, UUID projectId, UUID userId) {
        return findById(tenant, projectId)
                .filter(p -> isMember(p.id(), userId));
    }

    @Override
    public List<Project> findAllVisibleToUser(TenantContext tenant, UUID userId) {
        return projects.values().stream()
                .filter(p -> p.tenantId().toString().equals(tenant.value()))
                .filter(p -> isMember(p.id(), userId))
                .toList();
    }

    @Override
    public Project save(TenantContext tenant, Project project) {
        projects.put(project.id(), project);
        return project;
    }

    @Override
    public ProjectMember addMember(TenantContext tenant, ProjectMember member) {
        return members.values().stream()
                .filter(m -> m.projectId().equals(member.projectId())
                        && m.userId().equals(member.userId()))
                .findFirst()
                .orElseGet(() -> {
                    members.put(member.id(), member);
                    return member;
                });
    }

    @Override
    public List<ProjectMember> findMembers(TenantContext tenant, UUID projectId) {
        return members.values().stream()
                .filter(m -> m.projectId().equals(projectId))
                .toList();
    }

    @Override
    public boolean removeMember(TenantContext tenant, UUID projectId, UUID userId) {
        return members.values().removeIf(m -> m.projectId().equals(projectId)
                && m.userId().equals(userId)
                && findById(tenant, m.projectId()).isPresent());
    }

    @Override
    public int removeAllMembershipsOf(TenantContext tenant, UUID userId) {
        int before = members.size();
        members.values().removeIf(m -> m.userId().equals(userId)
                && findById(tenant, m.projectId()).isPresent());
        return before - members.size();
    }

    private boolean isMember(UUID projectId, UUID userId) {
        return members.values().stream()
                .anyMatch(m -> m.projectId().equals(projectId) && m.userId().equals(userId));
    }
}
