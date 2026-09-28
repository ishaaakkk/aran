package edu.upc.dtak.aran.adapter.persistence.aop;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.tenant.TenantContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/**
 * Aspect that automatically enables Hibernate's {@code tenantFilter}
 * on the current Hibernate Session before executing any tenant-scoped
 * persistence operations.
 *
 * <p>Removes the need to duplicate hand-rolled filter-enabling logic
 * across individual adapter classes. Matches any method inside the
 * persistent JPA repositories and adapters that operates under tenant scope.
 */
@Aspect
@Component
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Before("execution(* edu.upc.dtak.aran.adapter.persistence.JpaProjectRepository.*(..)) || " +
            "execution(* edu.upc.dtak.aran.adapter.persistence.JpaDataRepositoryRepository.*(..)) || " +
            "execution(* edu.upc.dtak.aran.adapter.persistence.JpaDatasetRepository.*(..)) || " +
            "execution(* edu.upc.dtak.aran.adapter.persistence.JpaGraphVersionAdapter.*(..)) || " +
            "execution(* edu.upc.dtak.aran.adapter.persistence.JpaJobRepository.*(..)) || " +
            "execution(* edu.upc.dtak.aran.adapter.persistence.JpaDataProductRepository.*(..)) || " +
            "execution(* edu.upc.dtak.aran.adapter.persistence.JpaIntegrationModelRepository.*(..)) || " +
            "execution(* edu.upc.dtak.aran.adapter.persistence.JpaSchemaFingerprintRepository.*(..)) || " +
            "execution(* edu.upc.dtak.aran.adapter.persistence.JpaAuditAdapter.*(..))")
    public void enableTenantFilter(JoinPoint joinPoint) {
        TenantContext tenant = null;
        if (TenantContextHolder.isBound()) {
            tenant = TenantContextHolder.require();
        } else if (joinPoint.getArgs().length > 0 && joinPoint.getArgs()[0] instanceof TenantContext) {
            tenant = (TenantContext) joinPoint.getArgs()[0];
        }

        if (tenant != null) {
            entityManager.unwrap(Session.class)
                    .enableFilter("tenantFilter")
                    .setParameter("tenantId", UUID.fromString(tenant.value()));
        }
    }
}
