package edu.upc.dtak.aran.adapter.persistence.listener;

import edu.upc.dtak.aran.core.tenant.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Method;
import java.util.UUID;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.*;
import org.hibernate.internal.SessionFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Foolproof security listener enforcing strict multi-tenant boundary invariants
 * at the Hibernate engine level.
 *
 * <p>Intercepts PRE_LOAD, PRE_INSERT, and PRE_UPDATE actions dynamically. If
 * the loaded/written entity is tenant-scoped (defines {@code getTenantId()}),
 * this listener asserts that its value matches the active security context.
 * Any mismatch immediately raises a {@link SecurityException}, halting execution.
 */
@Component
@SuppressWarnings("deprecation")
public class TenantSecurityEventListener implements PreLoadEventListener, PreInsertEventListener, PreUpdateEventListener {

    private static final Logger log = LoggerFactory.getLogger(TenantSecurityEventListener.class);

    private final EntityManagerFactory entityManagerFactory;

    public TenantSecurityEventListener(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @PostConstruct
    public void registerListeners() {
        SessionFactoryImpl sessionFactory = entityManagerFactory.unwrap(SessionFactoryImpl.class);
        EventListenerRegistry registry = sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class);

        registry.getEventListenerGroup(EventType.PRE_LOAD).appendListener(this);
        registry.getEventListenerGroup(EventType.PRE_INSERT).appendListener(this);
        registry.getEventListenerGroup(EventType.PRE_UPDATE).appendListener(this);
        log.info("Successfully registered Hibernate multi-tenancy PreLoad, PreInsert, and PreUpdate event listeners");
    }

    @Override
    public void onPreLoad(PreLoadEvent event) {
        validateTenant(event.getEntity(), "LOAD");
    }

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        validateTenant(event.getEntity(), "INSERT");
        return false;
    }

    @Override
    public boolean onPreUpdate(PreUpdateEvent event) {
        validateTenant(event.getEntity(), "UPDATE");
        return false;
    }

    private void validateTenant(Object entity, String action) {
        if (!TenantContextHolder.isBound()) {
            // No tenant context bound — system background bootstrapping/restoring. Allow.
            return;
        }

        UUID activeTenantId = UUID.fromString(TenantContextHolder.require().value());

        try {
            Method getTenantIdMethod = entity.getClass().getMethod("getTenantId");
            UUID entityTenantId = (UUID) getTenantIdMethod.invoke(entity);

            if (entityTenantId != null && !entityTenantId.equals(activeTenantId)) {
                log.error("CRITICAL SECURITY VIOLATION: Tenant boundary crossing blocked! Active context: {}, Entity: {}, Entity Owner: {}, Action: {}",
                        activeTenantId, entity.getClass().getSimpleName(), entityTenantId, action);
                throw new SecurityException(String.format(
                        "Tenant violation detected! Active Tenant Context (%s) does not match resource owner (%s) during %s of %s",
                        activeTenantId, entityTenantId, action, entity.getClass().getSimpleName()
                ));
            }
        } catch (NoSuchMethodException e) {
            // The entity is not tenant-owned (e.g. global System configs, Keycloak Identity records, etc.). Allow.
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Failed to validate tenant security boundaries on loaded entity", e);
        }
    }
}
