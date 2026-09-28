package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.ProjectJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.ProjectMemberJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.mapper.EntityMapper;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectMember;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * JPA-backed implementation of the core {@link ProjectRepository} port.
 *
 * <p>List queries lean on the shared-schema tenant filter, enabled transparently
 * via {@code TenantFilterAspect}. Reads and deletes <em>by id</em> do not: Hibernate
 * never applies a {@code @Filter} to a load by primary key, so those name
 * {@code tenant_id} in the predicate themselves. {@code CrossTenantByIdIT} pins it.
 */
@Component
@NullMarked
class JpaProjectRepository implements ProjectRepository {

    private final ProjectJpaRepository projectJpa;
    private final ProjectMemberJpaRepository memberJpa;

    JpaProjectRepository(ProjectJpaRepository projectJpa,
                         ProjectMemberJpaRepository memberJpa) {
        this.projectJpa = projectJpa;
        this.memberJpa = memberJpa;
    }

    @Override
    public Optional<Project> findById(TenantContext tenant, UUID projectId) {
        return projectJpa.findByIdAndTenantId(projectId, UUID.fromString(tenant.value()))
                .map(EntityMapper::toDomain);
    }

    @Override
    public Optional<Project> findVisibleToUser(TenantContext tenant, UUID projectId, UUID userId) {
        return projectJpa
                .findVisibleToUser(projectId, userId, UUID.fromString(tenant.value()))
                .map(EntityMapper::toDomain);
    }

    @Override
    public List<Project> findAllVisibleToUser(TenantContext tenant, UUID userId) {
        return projectJpa
                .findAllVisibleToUser(userId, UUID.fromString(tenant.value()))
                .stream()
                .map(EntityMapper::toDomain)
                .toList();
    }

    @Override
    public Project save(TenantContext tenant, Project project) {
        UUID tenantId = UUID.fromString(tenant.value());
        return EntityMapper.toDomain(projectJpa.save(EntityMapper.toEntity(project, tenantId)));
    }

    @Override
    public ProjectMember addMember(TenantContext tenant, ProjectMember member) {
        // Idempotent: if the visibility row already exists, return it as-is.
        return memberJpa.findByProjectIdAndUserId(member.projectId(), member.userId())
                .map(EntityMapper::toDomain)
                .orElseGet(() -> EntityMapper.toDomain(
                        memberJpa.save(EntityMapper.toEntity(member))));
    }

    @Override
    public List<ProjectMember> findMembers(TenantContext tenant, UUID projectId) {
        return memberJpa.findByProjectId(projectId).stream()
                .map(EntityMapper::toDomain)
                .toList();
    }

    @Override
    public boolean removeMember(TenantContext tenant, UUID projectId, UUID userId) {
        return memberJpa.deleteMembership(projectId, userId, UUID.fromString(tenant.value())) > 0;
    }

    @Override
    public int removeAllMembershipsOf(TenantContext tenant, UUID userId) {
        return memberJpa.deleteAllForUserInTenant(userId, UUID.fromString(tenant.value()));
    }
}
