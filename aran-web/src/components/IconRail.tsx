import React, { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { 
  LogOut, User as UserIcon, Sun, Moon, Key as KeyIcon,
  Home, Folder, Database, FileText, Layers, History, TerminalSquare, Building2, Package, PlayCircle,
  ShieldCheck, LockKeyhole
} from 'lucide-react';
import { useAuthStore } from '../stores/authStore';
import { useTutorialStore } from '../stores/useTutorialStore';
import { EditProfileDialog } from './EditProfileDialog';
import { ApiTokensDialog } from './ApiTokensDialog';
import { tourForPath } from './tours/tourRegistry';
import { applyTheme, storedTheme } from '../lib/theme';
import { changePassword, clearPasswordChangeOutcome, passwordChangeOutcome } from '../services/keycloak';
import { canChangeOwnPassword } from '../services/demo/demoInterceptor';
import { PasswordChangeNotice } from './PasswordChangeNotice';
import { BrandMark } from './BrandMark';

/**
 * Sidebar navigation. Replaces cryptic glyphs with Lucide React icons
 * and explicit textual labels for better UX.
 */
export const IconRail: React.FC = () => {
  // Selected field by field: the rail renders on every authenticated page, so a
  // whole-store subscription re-renders it on every unrelated store action.
  const me = useAuthStore((s) => s.me);
  const activeTenant = useAuthStore((s) => s.activeTenant);
  const logout = useAuthStore((s) => s.logout);
  const triggerTour = useTutorialStore((s) => s.triggerTour);
  const location = useLocation();
  const [showMenu, setShowMenu] = useState(false);
  const [showEditDialog, setShowEditDialog] = useState(false);
  const [showTokensDialog, setShowTokensDialog] = useState(false);
  // How a "Change password" round trip ended, read once on mount (it is set during Keycloak's init,
  // before anything renders) and cleared when dismissed.
  const [passwordOutcome, setPasswordOutcome] = useState(passwordChangeOutcome);
  const dismissPasswordOutcome = () => {
    clearPasswordChangeOutcome();
    setPasswordOutcome(null);
  };

  /* ---- theme ----
     The rail toggles the theme; it does not own it. `initTheme()` in main.tsx applies
     the stored mode before the first render, because the admin area has its own shell
     and never mounts this component — a hard reload there used to come up dark
     whatever the operator had chosen. */
  const [isDark, setIsDark] = useState(() => storedTheme() === 'dark');

  useEffect(() => {
    applyTheme(isDark ? 'dark' : 'light');
  }, [isDark]);

  const initials = me?.displayName
    ? me.displayName
        .split(' ')
        .filter(Boolean)
        .map((s) => s[0])
        .join('')
        .slice(0, 2)
        .toUpperCase()
    : 'JD';

  // Owners only. This hides a link, it does not protect anything — /api/tenant/admin/**
  // is OWNER-gated server-side, and that is the check that matters.
  const isOwner = activeTenant?.role === 'OWNER';

  // Grouped by the connect → integrate → consume pipeline the product's own tours teach,
  // instead of a flat jargon list (redesign brief, chrome MED). Organizations moved to the
  // account menu below: with one tenant per user by design there is nothing to switch, so
  // it is an account fact, not a workspace destination.
  const navGroups: Array<{ label: string | null; items: Array<{ to: string; icon: typeof Home; label: string }> }> = [
    {
      label: null,
      items: [
        { to: '/dashboard', icon: Home,   label: 'Home' },
        { to: '/projects',  icon: Folder, label: 'Projects' },
      ],
    },
    {
      label: 'Connect',
      items: [
        { to: '/repositories', icon: Database, label: 'Repositories' },
        { to: '/datasets',     icon: FileText, label: 'Datasets' },
      ],
    },
    {
      label: 'Integrate',
      items: [
        { to: '/alignments', icon: Layers,  label: 'Alignments' },
        { to: '/versions',   icon: History, label: 'Schema versions' },
      ],
    },
    {
      label: 'Consume',
      items: [
        { to: '/products', icon: Package,        label: 'Data products' },
        { to: '/oql',      icon: TerminalSquare, label: 'Query workbench' },
      ],
    },
  ];

  return (
    <aside className="w-56 bg-card border-r border-border flex flex-col py-3.5 shrink-0 z-40 relative select-none">
      <div className="flex items-center px-4 mb-4 gap-3">
        <Link to="/dashboard" aria-label="Aran home" className="w-7 h-7 flex items-center justify-center select-none shrink-0">
          <BrandMark className="w-7 h-7" />
        </Link>
        <span className="font-bold text-foreground text-[16px]" style={{ fontFamily: 'var(--font-display)' }}>Aran</span>
      </div>
      
      <div className="flex-1 px-3 space-y-1 overflow-y-auto">
        {navGroups.map((group, gi) => (
          <React.Fragment key={group.label ?? `group-${gi}`}>
            {group.label && (
              <div className="text-[12.5px] font-semibold text-muted-foreground/70 px-3 pt-4 pb-1 select-none">
                {group.label}
              </div>
            )}
            {group.items.map((n) => {
              const isActive =
                location.pathname === n.to || location.pathname.startsWith(n.to + '/');
              const IconComp = n.icon;
              return (
                <Link
                  key={n.to}
                  to={n.to}
                  className={`w-full h-9 rounded-md flex items-center px-3 gap-3 text-[13.5px] font-medium transition-colors duration-100 ${
                    isActive
                      ? 'bg-primary/10 text-primary border border-primary/40'
                      : 'bg-transparent text-muted-foreground border border-transparent hover:text-foreground hover:bg-muted/40'
                  }`}
                >
                  <IconComp className="w-4 h-4 shrink-0" />
                  <span className="truncate">{n.label}</span>
                </Link>
              );
            })}
          </React.Fragment>
        ))}
      </div>

      <div className="px-3 pt-3 mt-2 border-t border-border">
        {tourForPath(location.pathname) && (
          <div className="pb-3">
            <button
              type="button"
              data-testid="nav-guided-tour"
              onClick={() => {
                const tour = tourForPath(location.pathname);
                if (tour) triggerTour(tour.id);
              }}
              className="w-full h-9 rounded-md bg-primary/10 border border-primary/40 text-primary hover:bg-primary/20 hover:border-primary/60 flex items-center justify-center gap-2 text-[13px] font-semibold transition-all cursor-pointer"
            >
              <PlayCircle className="w-3.5 h-3.5" />
              <span>Guided tour</span>
            </button>
          </div>
        )}

      {/* User Avatar with Popup Menu */}
      <div className="relative px-3 pb-2">
        <button
          type="button"
          data-testid="nav-user-menu"
          onClick={() => setShowMenu(!showMenu)}
          className="w-full h-10 rounded-md bg-muted border border-border text-foreground hover:border-primary px-2 flex items-center gap-3 transition-colors cursor-pointer select-none"
        >
          <div className="w-6 h-6 rounded-full bg-card border border-border flex items-center justify-center font-mono text-[10px] font-bold text-primary shrink-0">
            {initials}
          </div>
          <div className="flex-1 text-left truncate text-[13px] font-medium">
            {me?.displayName || 'User'}
          </div>
        </button>

        {showMenu && (
          <>
            <div className="fixed inset-0 z-40" onClick={() => setShowMenu(false)} role="presentation" onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setShowMenu(false); }} />
            <div className="absolute bottom-14 left-3 z-50 min-w-[220px] glass rounded-lg border border-border shadow-2xl p-4 font-mono animate-in">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-full bg-card border border-border flex items-center justify-center text-sm font-semibold text-primary">
                  {initials}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="text-xs font-bold text-foreground truncate">
                    {me?.displayName || 'User'}
                  </div>
                  <div data-testid="nav-user-email" className="text-[10px] text-muted-foreground truncate font-sans mt-0.5">
                    {me?.email || 'No email'}
                  </div>
                </div>
              </div>

              <span className="block h-[1px] bg-border my-2.5" />

              {activeTenant && (
                <div className="px-1 space-y-1.5">
                  <div>
                    <div className="text-[9px] font-bold text-muted-foreground/60 tracking-wider">
                      ORGANIZATION
                    </div>
                    <div className="text-xs text-foreground/90 truncate">
                      {activeTenant.name}
                    </div>
                  </div>
                  <div>
                    <div className="text-[9px] font-bold text-muted-foreground/60 tracking-wider">
                      ROLE
                    </div>
                    <div className="text-xs text-primary font-semibold truncate">
                      {activeTenant.role}
                    </div>
                  </div>
                </div>
              )}

              <span className="block h-[1px] bg-border my-2.5" />

              <div className="px-1 py-0.5">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5 font-mono text-[9px] font-bold text-muted-foreground/60">
                    {isDark ? (
                      <Moon size={11} className="text-primary shrink-0" />
                    ) : (
                      <Sun size={11} className="text-primary shrink-0" />
                    )}
                    <span>DARK MODE</span>
                  </div>
                  <button
                    type="button"
                    onClick={() => setIsDark(!isDark)}
                    className="relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border border-transparent transition-colors duration-200 ease-in-out focus:outline-none"
                    style={{ backgroundColor: isDark ? 'var(--primary)' : 'var(--input)' }}
                  >
                    <span
                      className={`pointer-events-none inline-block h-4.5 w-4.5 transform rounded-full bg-white shadow transition duration-200 ease-in-out ${
                        isDark ? 'translate-x-4' : 'translate-x-0'
                      }`}
                    />
                  </button>
                </div>
              </div>

              <span className="block h-[1px] bg-border my-2.5" />

              <div className="flex flex-col gap-1">
                <button
                  type="button"
                  onClick={() => { setShowEditDialog(true); setShowMenu(false); }}
                  className="flex items-center gap-2 w-full h-8 px-2 text-[11px] font-medium text-muted-foreground hover:text-primary bg-card hover:bg-muted border border-border hover:border-primary/30 rounded transition-all duration-100 cursor-pointer text-left"
                >
                  <UserIcon className="w-3.5 h-3.5" />
                  <span>Edit Profile</span>
                </button>
                {/* J7-F14: self-service, so a suspected leak does not mean asking an owner — who
                    would then see the new password. Keycloak's UPDATE_PASSWORD action through the
                    same-origin /auth proxy; the user comes back to this page, where
                    PasswordChangeNotice says how it went. Never for the shared demo account,
                    whose published password one visitor could change for everyone. */}
                {canChangeOwnPassword() && (
                  <button
                    type="button"
                    data-testid="nav-change-password"
                    onClick={() => { setShowMenu(false); changePassword(); }}
                    className="flex items-center gap-2 w-full h-8 px-2 text-[11px] font-medium text-muted-foreground hover:text-primary bg-card hover:bg-muted border border-border hover:border-primary/30 rounded transition-all duration-100 cursor-pointer text-left"
                  >
                    <LockKeyhole className="w-3.5 h-3.5" />
                    <span>Change password</span>
                  </button>
                )}
                <button
                  type="button"
                  data-testid="nav-api-tokens"
                  onClick={() => { setShowTokensDialog(true); setShowMenu(false); }}
                  className="flex items-center gap-2 w-full h-8 px-2 text-[11px] font-medium text-muted-foreground hover:text-primary bg-card hover:bg-muted border border-border hover:border-primary/30 rounded transition-all duration-100 cursor-pointer text-left"
                >
                  <KeyIcon className="w-3.5 h-3.5" />
                  <span>API Tokens</span>
                </button>
                {/* One tenant per user by design (V23) — there is nothing to switch, so
                    the organization page is an account fact, not a workspace destination.
                    It lives here with the other account actions rather than in the nav. */}
                <Link
                  to="/tenants"
                  data-testid="nav-organizations"
                  onClick={() => setShowMenu(false)}
                  className="flex items-center gap-2 w-full h-8 px-2 text-[11px] font-medium text-muted-foreground hover:text-primary bg-card hover:bg-muted border border-border hover:border-primary/30 rounded transition-all duration-100 cursor-pointer text-left"
                >
                  <Building2 className="w-3.5 h-3.5" />
                  <span>Organization</span>
                </Link>
                {/* Owners only, and here rather than in the nav above on purpose:
                    administration is a different surface, not another place in the
                    workspace, so it belongs with the account actions that also leave
                    this one. Amber, because that is what it opens into. */}
                {isOwner && (
                  <Link
                    to="/admin"
                    data-testid="nav-admin"
                    onClick={() => setShowMenu(false)}
                    className="flex items-center gap-2 w-full h-8 px-2 text-[11px] font-medium text-muted-foreground hover:text-accent bg-card hover:bg-muted border border-border hover:border-accent/40 rounded transition-all duration-100 cursor-pointer text-left"
                  >
                    <ShieldCheck className="w-3.5 h-3.5" />
                    <span>Administration</span>
                  </Link>
                )}
                <button
                  type="button"
                  data-testid="nav-logout"
                  onClick={() => { logout(); setShowMenu(false); }}
                  className="flex items-center gap-2 w-full h-8 px-2 text-[11px] font-medium text-muted-foreground hover:text-rose-400 bg-card hover:bg-muted border border-border hover:border-rose-500/30 rounded transition-all duration-100 cursor-pointer text-left"
                >
                  <LogOut className="w-3.5 h-3.5" />
                  <span>Logout</span>
                </button>
              </div>
            </div>
          </>
        )}
      </div>
      </div>
      
      <EditProfileDialog
        isOpen={showEditDialog}
        onClose={() => setShowEditDialog(false)}
      />

      <ApiTokensDialog
        isOpen={showTokensDialog}
        onClose={() => setShowTokensDialog(false)}
      />

      {passwordOutcome && (
        <PasswordChangeNotice outcome={passwordOutcome} onDismiss={dismissPasswordOutcome} />
      )}
    </aside>
  );
};

export default IconRail;
