import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { isAuthenticated, login, pendingReturnTo } from '@/services/keycloak';
import { pathOf, signInReturnPath } from '@/components/sessionGate';
import { Database, FolderGit2, GitMerge, Terminal, ArrowRight, Sun, Moon } from 'lucide-react';
import { BrandMark } from '@/components/BrandMark';

export const LoginView: React.FC = () => {
  const navigate = useNavigate();
  // Where RequireAuth was sent from — a shared `/oql?q=…` link, say. Carried through Keycloak so
  // the user lands there after signing in, not on the dashboard (UI audit J1-F17).
  const location = useLocation();
  // A page kept across "sign out and sign in again" (J8-F1) wins over the default landing that a
  // sign-out's redirect leads here from — but not over a deep link opened since (signInReturnPath).
  const from = signInReturnPath(
    pendingReturnTo(),
    pathOf((location.state as { from?: { pathname: string; search?: string; hash?: string } } | null)?.from),
  );
  const [busy, setBusy] = useState(false);
  const [isDark, setIsDark] = useState(() => {
    return (localStorage.getItem('aran.ui.mode') || 'light') === 'dark';
  });

  useEffect(() => {
    const root = window.document.documentElement;
    if (isDark) {
      root.classList.remove('light');
      root.classList.add('dark');
      localStorage.setItem('aran.ui.mode', 'dark');
    } else {
      root.classList.remove('dark');
      root.classList.add('light');
      localStorage.setItem('aran.ui.mode', 'light');
    }
  }, [isDark]);

  useEffect(() => {
    if (isAuthenticated()) {
      navigate(from ?? '/dashboard', { replace: true });
    }
  }, [navigate, from]);

  const handleLogin = () => {
    setBusy(true);
    login(from);
  };

  return (
    <div className="grid grid-cols-1 md:grid-cols-[420px_1fr] min-h-screen bg-background text-foreground font-sans selection:bg-primary selection:text-primary-foreground">

      {/* ── Left: auth form ──────────────────────────────────────────── */}
      <section className="flex flex-col px-10 py-12 justify-between border-r border-border">
        <header className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <BrandMark className="w-8 h-8 select-none" />
            <span className="font-mono text-[13px] tracking-[0.18em] uppercase font-semibold">
              ARAN
            </span>
          </div>
          <button
            type="button"
            title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
            onClick={() => setIsDark(!isDark)}
            className="w-8 h-8 rounded-md border border-border bg-muted/20 flex items-center justify-center text-muted-foreground hover:text-foreground hover:border-primary/40 transition-colors"
          >
            {isDark ? <Sun size={14} /> : <Moon size={14} />}
          </button>
        </header>

        <div className="space-y-7">
          <div className="space-y-1">
            <div className="flex items-center gap-3 mb-4">
              <span className="font-mono text-[10px] uppercase tracking-[0.18em] text-muted-foreground font-bold">
                Sign in
              </span>
              <span className="flex-1 h-px bg-border" />
            </div>
            <h1 className="text-[32px] font-semibold tracking-tight leading-[1.1] text-foreground">
              Sign in to Aran.
            </h1>
            <p className="text-sm text-muted-foreground leading-relaxed pt-1">
              Use your workspace account. Your organization is resolved
              automatically when you sign in.
            </p>
          </div>

          {/* J1-F20: a fresh install's operator has no way to learn the owner account or where
              its one-time password went. Static and collapsed on purpose — the conditional
              first-run card (shown only until the first sign-in) needs an anonymous
              `firstRunPending` flag and is Tier C's (C6). It names no account: a login page
              does not tell anonymous visitors who the owner is, and AdminBootstrap prints the
              (configurable) username in the same log as the password. Not on a dev build (a
              dev backend creates no owner) or a demo build (demo/demo has its own hint). */}
          {!import.meta.env.DEV && import.meta.env.VITE_DEMO !== 'true' && (
            <details
              data-testid="login-first-install"
              className="rounded-md border border-border/80 bg-muted/15 px-3.5 py-2.5 text-[12px] text-muted-foreground"
            >
              <summary className="cursor-pointer text-foreground/85 font-medium">
                First sign-in on a new install?
              </summary>
              <div className="pt-2 space-y-1.5 leading-relaxed">
                <p>
                  The first boot creates one owner account and prints its username once in the
                  server log — with a one-time password, unless the operator set{' '}
                  <span className="font-mono">ARAN_ADMIN_PASSWORD</span>:
                </p>
                <p className="font-mono text-[11px] text-foreground break-all select-all">
                  docker compose -f compose.standalone.yml logs aran-app
                </p>
                <p>
                  You are asked to change it at first sign-in. Locked out? On the server, run{' '}
                  <span className="font-mono text-foreground select-all">./aran admin reset-password &lt;username&gt;</span>.
                </p>
              </div>
            </details>
          )}

          {/* Primary SSO button */}
          <button
            type="button"
            data-testid="login-submit"
            onClick={handleLogin}
            disabled={busy}
            className="w-full flex items-center justify-between gap-3 rounded-md bg-primary py-3.5 px-5 font-mono text-xs uppercase tracking-wider text-primary-foreground font-semibold hover:bg-primary/90 transition-colors duration-150 disabled:opacity-50 disabled:cursor-not-allowed select-none"
          >
            <span className="inline-flex items-center gap-2">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-300 shadow-[0_0_8px_rgba(110,231,183,0.7)]" />
              {busy ? 'redirecting to sign-in…' : 'continue with single sign-on'}
            </span>
            <ArrowRight size={14} className="opacity-60" />
          </button>

          {/* Dev credentials hint — dev builds only; a production login page advertising
              seeded credentials is a trust cost with no upside (redesign brief, entry MED). */}
          {import.meta.env.DEV && (
            <div className="rounded-md border border-border/80 bg-muted/25 p-3.5 font-mono text-[11px] space-y-2">
              <div className="font-bold text-primary text-[10px] tracking-wider uppercase select-none">
                developer instance
              </div>
              <div className="flex justify-between border-b border-border/40 pb-1.5">
                <span className="text-muted-foreground/70">identity</span>
                <span className="text-foreground/90 font-medium">alice</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground/70">secret</span>
                <span className="text-foreground/90 font-medium">aran</span>
              </div>
            </div>
          )}

          {/* Guided-tour demo hint — only in a build that opted into demo mode
              (VITE_DEMO), which is the same flag isDemoUser() reads. Without it the
              `demo` account signs in to its own empty tenant, so advertising it here
              would promise a populated scenario the build cannot produce. */}
          {import.meta.env.VITE_DEMO === 'true' && (
            <div className="rounded-md border border-primary/30 bg-primary/5 p-3.5 font-mono text-[11px] space-y-2">
              <div className="font-bold text-primary text-[10px] tracking-wider uppercase select-none">
                guided tour — a simulation, no setup required
              </div>
              <p className="text-foreground/80 leading-relaxed">
                Sign in as <span className="text-foreground font-medium">demo</span> / <span className="text-foreground font-medium">demo</span> to
                walk a populated scenario — three sources integrated into a global schema — with a
                tour on every page. It is simulated in the browser: no engine runs, and query
                results are canned demo rows chosen by the words in your query, not computed. The
                account is read-only: creating, editing, and heavy operations (bootstrap, compile,
                materialize) show a message instead of running.
              </p>
            </div>
          )}
        </div>

        <footer className="font-mono text-[10px] text-muted-foreground/60 tracking-wide">
          aran · v0.1
        </footer>
      </section>

      {/* ── Right: platform overview ──────────────────────────────────── */}
      <aside className="hidden md:flex flex-col bg-card/40 p-10 gap-8 overflow-y-auto">

        {/* Platform heading */}
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <span className="font-mono text-[10px] uppercase tracking-[0.18em] text-muted-foreground font-bold">
              Why Aran
            </span>
            <span className="flex-1 h-px bg-border" />
          </div>
          <h2 className="text-xl font-semibold tracking-tight text-foreground">
            One schema over all your sources
          </h2>
          <p className="text-sm text-muted-foreground leading-relaxed max-w-xl">
            Aran integrates the schemas of your relational databases, Iceberg tables and
            files. It suggests alignments from the data, you review them and promote a
            unified model, and then query everything as one graph — in the workbench, over
            JDBC, or through agents.
          </p>
        </div>

        {/* Integration pipeline */}
        <div className="space-y-2">
          <span className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
            Integration pipeline
          </span>
          <div className="flex items-stretch gap-0 rounded-md border border-border overflow-hidden">
            {[
              { step: '01', label: 'Connect',   sub: 'databases & files' },
              { step: '02', label: 'Align',     sub: 'suggested matches' },
              { step: '03', label: 'Integrate', sub: 'one unified schema' },
              { step: '04', label: 'Query',     sub: 'everything as one graph' },
              { step: '05', label: 'Serve',     sub: 'products · JDBC · agents' },
            ].map((item, i, arr) => (
              <React.Fragment key={item.step}>
                <div className={`flex-1 flex flex-col items-center justify-center py-3.5 px-2 text-center ${i === 2 ? 'bg-primary/8 border-x border-primary/20' : 'bg-muted/10'}`}>
                  <span className="font-mono text-[9px] uppercase tracking-widest text-muted-foreground/60">
                    {item.step}
                  </span>
                  <span className={`font-mono text-xs font-semibold mt-0.5 ${i === 2 ? 'text-primary' : 'text-foreground'}`}>
                    {item.label}
                  </span>
                  <span className="font-mono text-[9px] text-muted-foreground/70 mt-0.5">
                    {item.sub}
                  </span>
                </div>
                {i < arr.length - 1 && (
                  <div className="flex items-center px-0 bg-muted/5 border-x border-border/30">
                    <ArrowRight size={10} className="text-muted-foreground/40" />
                  </div>
                )}
              </React.Fragment>
            ))}
          </div>
        </div>

        {/* Feature cards */}
        <div className="space-y-2">
          <span className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
            Key capabilities
          </span>
          <div className="grid grid-cols-2 gap-3">
            {[
              {
                icon: <GitMerge size={15} className="text-violet-400" />,
                title: 'Automated alignment',
                desc: 'Aran suggests how your sources’ schemas line up, with evidence from the data itself. You accept or dismiss — nothing merges without you.',
              },
              {
                icon: <FolderGit2 size={15} className="text-primary" />,
                title: 'Any source',
                desc: 'Relational databases, Iceberg catalogs and uploaded files — read live or snapshotted into the lake, per dataset — plus SaaS APIs through Singer taps when your operator enables them.',
              },
              {
                icon: <Terminal size={15} className="text-emerald-400" />,
                title: 'Query workbench',
                desc: 'Ask one question across every source. Queries plan over the unified schema and show exactly what they read.',
              },
              {
                icon: <Database size={15} className="text-amber-400" />,
                title: 'Data quality built in',
                desc: 'Column profiles feed join suggestions and the planner, and the constraints you enforce filter every graph-tier query from the next one on, with the rejected rows set aside for review.',
              },
            ].map((f) => (
              <div
                key={f.title}
                className="flex flex-col gap-2 rounded-md border border-border bg-card p-4"
              >
                <div className="flex items-center gap-2">
                  {f.icon}
                  <span className="font-mono text-xs font-semibold text-foreground">{f.title}</span>
                </div>
                <p className="text-[11px] text-muted-foreground leading-relaxed">{f.desc}</p>
              </div>
            ))}
          </div>
        </div>

        {/* The hardcoded all-green "Platform status" panel is gone: a status surface that
            can show healthy while the platform is down costs more trust than it buys
            (redesign brief, entry MED). Real probes live in the app's top bar. */}

      </aside>
    </div>
  );
};

export default LoginView;
