package edu.upc.dtak.aran.core.domain;

/**
 * A user's role <em>within a particular tenant</em>.
 *
 * <p>This role lives on {@link Membership}, not on {@link User}. That was once because a user
 * could belong to several tenants with a different role in each; since V23 a user belongs to
 * exactly one, and the join table is kept because the constraint is what enforces the rule and
 * relaxing it later is one line, while un-collapsing a table is a data migration. It is
 * deliberately a small enum; extend it (e.g. {@code ADMIN}, {@code VIEWER})
 * only when a concrete need appears, rather than speculatively.
 *
 * <ul>
 *   <li>{@link #OWNER} — can manage the tenant: invite/remove members, change
 *       roles, and do everything a member can.</li>
 *   <li>{@link #MEMBER} — can operate within the tenant (create projects, be
 *       added to projects) but cannot administer the tenant itself.</li>
 * </ul>
 */
public enum TenantRole {
    OWNER,
    MEMBER
}
