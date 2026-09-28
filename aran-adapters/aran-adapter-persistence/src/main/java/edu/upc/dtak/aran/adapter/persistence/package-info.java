/**
 * Outbound persistence adapter for Aran.
 *
 * <p>This module implements the persistence-facing SPI ports declared by
 * {@code aran-core}, backed by Spring Data JPA / Hibernate 7. It is wired into
 * the application via {@code runtimeOnly}, so the core depends on the port
 * interfaces but never on this concrete implementation — the adapter is
 * swappable.
 *
 * <p><strong>Multi-tenancy responsibility.</strong> Per the shared-schema ADR,
 * tenant isolation is enforced <em>here</em>, centrally, not scattered across
 * queries. The intended mechanism is a Hibernate {@code @Filter} on every
 * tenant-owned entity, enabled per session from
 * {@link edu.upc.dtak.aran.core.tenant.TenantContextHolder}. Concentrating it in one
 * place is what makes "every query is tenant-scoped" an invariant rather than
 * a code-review hope.
 *
 * <p>Skeleton: entities, repositories, and the tenant-filter wiring are added
 * as the domain model is built out.
 */
package edu.upc.dtak.aran.adapter.persistence;
