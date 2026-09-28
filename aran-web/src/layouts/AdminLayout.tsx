import React from 'react'
import { Link, NavLink } from 'react-router-dom'
import { Activity, ArrowLeft, BarChart3, Database, HeartPulse, KeyRound, ShieldCheck, Users } from 'lucide-react'
import { useAuthStore } from '@/stores/authStore'
import './admin-shell.css'

/**
 * The administration shell.
 *
 * **A separate surface, not a page inside the product.** The workspace shell — the icon
 * rail, the terminal top bar, the breadcrumb, the command palette — is built for someone
 * doing data work: projects, datasets, queries. Administration is a different job done by
 * a different person wearing a different hat, and mixing the two means an owner is always
 * one mis-click from running an admin action while thinking they are in their workspace.
 * So this owns the whole viewport and shares nothing with `AppLayout`.
 *
 * It keeps Aran's tokens — the type, the radii, the palette — so it is recognisably the
 * same product, and then deviates deliberately everywhere it matters: amber where the
 * product is violet, an outlined mark where the product's is filled, a flat top bar with
 * no breadcrumb or palette, a labelled sidebar rather than an icon rail, and a plain base
 * colour instead of the product's tinted gradient. This is the pattern GitLab, Grafana and
 * Discourse all settled on, and for the same reason.
 *
 * The exit is always in the top-left, where the product's home link would be. There is no
 * way to be in here and not see the way out.
 */
export const AdminLayout: React.FC<{
  children: React.ReactNode
  /**
   * Hide the section navigation — for a caller who is not an owner (audit J7-F5), who must not be
   * shown a live admin surface that only refuses them after they act on it.
   */
  restricted?: boolean
}> = ({ children, restricted = false }) => {
  const tenant = useAuthStore((s) => s.activeTenant)
  const me = useAuthStore((s) => s.me)

  return (
    <div className="admin-shell" data-testid="admin-shell">
      <header className="admin-topbar">
        <Link to="/dashboard" className="admin-exit" data-testid="admin-exit">
          <ArrowLeft className="w-3.5 h-3.5" />
          <span>Workspace</span>
        </Link>

        <div className="admin-brand">
          <ShieldCheck className="admin-brand-mark" />
          <span className="admin-brand-name">Aran</span>
          <span className="admin-brand-tag">Admin</span>
        </div>

        <div className="admin-topbar-right">
          {tenant && (
            <span className="admin-tenant">
              {tenant.name}
              <span className="admin-slug">{tenant.slug}</span>
            </span>
          )}
          {me && <span className="admin-whoami">{me.displayName}</span>}
        </div>
      </header>

      <div className="admin-main">
        {!restricted && (
          <nav className="admin-nav" aria-label="Administration">
            <span className="admin-nav-heading">Tenant</span>
            <AdminNavLink to="/admin/users" icon={Users} label="Users" />
            <AdminNavLink to="/admin/usage" icon={BarChart3} label="Usage" />
            <AdminNavLink to="/admin/storage" icon={Database} label="Storage" />
            <AdminNavLink to="/admin/credentials" icon={KeyRound} label="Credentials" />
            <AdminNavLink to="/admin/activity" icon={Activity} label="Activity" />
            <AdminNavLink to="/admin/system" icon={HeartPulse} label="System" />

            <p className="admin-nav-note">
              Users changes accounts and Credentials can revoke a token; both are recorded in
              Activity. Usage, Storage and Activity are records — nothing here can edit the
              history it displays.
            </p>
          </nav>
        )}

        <main className="admin-content">{children}</main>
      </div>
    </div>
  )
}

function AdminNavLink({
  to, icon: Icon, label,
}: { to: string; icon: React.ComponentType<{ className?: string }>; label: string }) {
  return (
    <NavLink to={to} className={({ isActive }) => `admin-nav-link${isActive ? ' is-active' : ''}`}>
      <Icon className="w-4 h-4 shrink-0" />
      <span>{label}</span>
    </NavLink>
  )
}

export default AdminLayout
