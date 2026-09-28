import React, { useCallback, useEffect, useState } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { isAuthenticated, keycloakInitFailed, login, logout, onSessionEnded } from '../services/keycloak';
import { useAuthStore } from '../stores/authStore';
import { ErrorNotice } from './ErrorNotice';
import { Dialog } from './ui/Dialog';
import { SESSION_RETRY_BACKOFF, isTransient, pathOf, sessionGate } from './sessionGate';

interface RequireAuthProps {
  children: React.ReactNode;
}

/** A full-page frame for the states that replace the workspace. */
const Frame: React.FC<{ children: React.ReactNode; testId: string }> = ({ children, testId }) => (
  <div
    data-testid={testId}
    className="flex flex-col items-center justify-center min-h-screen bg-background text-foreground px-4"
  >
    <div className="w-full max-w-lg rounded-lg border border-border bg-card p-6 shadow-sm">{children}</div>
  </div>
);

/**
 * `/api/me` failed. Rendered where the user is, so the URL — a shared `/oql?q=` link included —
 * survives the failure (J1-F1 = J8-F1). A slow, unreachable or failing server is retried on a
 * back-off; a refusal is not, because retrying it cannot help.
 */
export const SessionProblem: React.FC<{
  error: unknown;
  onRetry: () => void;
  retrying: boolean;
  autoRetryInSeconds: number | null;
  from?: string;
}> = ({ error, onRetry, retrying, autoRetryInSeconds, from }) => {
  const [left, setLeft] = useState(autoRetryInSeconds);

  useEffect(() => {
    if (left === null || retrying) return;
    if (left <= 0) {
      onRetry();
      return;
    }
    const t = setTimeout(() => setLeft(left - 1), 1000);
    return () => clearTimeout(t);
  }, [left, retrying, onRetry]);

  const kind = (error as { kind?: string } | null)?.kind;
  const refused = kind === 'unauthenticated' || kind === 'forbidden';

  return (
    <Frame testId="session-problem">
      <ErrorNotice
        title={refused ? 'Aran did not accept your sign-in' : "Can't load your session"}
        error={error}
        // A refusal is answered the same way however often it is asked (second review of UA2):
        // its way on is signing out and in, below, not a Retry that repeats the 401.
        onRetry={refused ? undefined : onRetry}
        retrying={retrying}
        retryLabel="Retry now"
      >
        {/* The notice is role="alert", which is atomic: a counter ticking inside it re-announced
            the whole alert every second. Screen readers get one static sentence; the ticking
            number is for the eye only. */}
        {!refused && left !== null && left > 0 && !retrying && (
          <p className="text-xs text-muted-foreground" data-testid="session-auto-retry">
            <span aria-hidden="true">Retrying in {left} s…</span>
            <span className="sr-only">Aran will retry on its own in a few seconds.</span>
          </p>
        )}
        {refused ? (
          <>
            <p className="text-xs text-muted-foreground max-w-md">
              You are signed in, but the server refused the session. Signing out and in again
              usually fixes it; if it keeps happening, ask your administrator.
            </p>
            <details className="text-left text-[11px] text-muted-foreground max-w-md">
              <summary className="cursor-pointer select-none">Details for operators</summary>
              <p className="mt-1">
                Every request being refused right after sign-in usually means the address Aran is
                served at does not match its configured public URL (ARAN_PUBLIC_URL), so the
                token's issuer is rejected.
              </p>
            </details>
            {/* `from` survives the sign-out: the sign-in after it comes back here (J8-F1). */}
            <button
              type="button"
              onClick={() => logout(from)}
              className="h-8 px-4 rounded-md bg-primary text-primary-foreground text-[12px] font-semibold hover:bg-primary/90 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
            >
              Sign out and sign in again
            </button>
          </>
        ) : (
          <Link
            to="/offline"
            state={{ reason: 'session', from }}
            className="text-xs text-primary underline underline-offset-2"
          >
            Check which services are reachable
          </Link>
        )}
      </ErrorNotice>
    </Frame>
  );
};

/** Signed in, but a member of no organization — every workspace call would 403 (J1-F8). */
export const NoOrganization: React.FC<{ email: string; displayName: string }> = ({ email, displayName }) => (
  <Frame testId="no-organization">
    <div className="space-y-3 text-center">
      <h1 className="text-lg font-semibold tracking-tight">No organization yet</h1>
      <p className="text-sm text-muted-foreground">
        You are signed in as <span className="font-semibold text-foreground select-text">{displayName}</span>{' '}
        (<span className="font-mono select-text">{email}</span>), but this account does not belong to
        an organization in Aran yet.
      </p>
      <p className="text-sm text-muted-foreground">
        Ask an owner of your organization to add you from Administration › Users, then sign in again.
      </p>
      <button
        type="button"
        onClick={() => logout()}
        className="h-8 px-4 rounded-md border border-border text-[12px] font-semibold hover:border-primary hover:text-primary focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
      >
        Sign out
      </button>
    </div>
  </Frame>
);

/**
 * The token could not be refreshed (the realm's one-hour idle or two-hour absolute limit). This
 * used to be an immediate redirect that lost the page and anything unsaved on it (J7-F6, J8-F5).
 * Now the page stays behind this dialog: signing in comes back to the same URL, and "Stay on
 * this page" lets the user copy what they were writing first.
 */
export const SessionEndedDialog: React.FC<{ open: boolean; onStay: () => void; returnTo?: string }> = ({
  open, onStay, returnTo,
}) => (
  <Dialog open={open} onClose={onStay} label="Your session has ended" testId="session-ended-dialog">
    <div className="p-5 space-y-3">
      <h2 className="text-base font-semibold tracking-tight">Your session has ended</h2>
      <p className="text-sm text-muted-foreground">
        Aran signs you out after an hour without activity, or after two hours in any case. Sign in
        again to continue — you will come back to this page.
      </p>
      <p className="text-sm text-muted-foreground">
        Anything on this page that is not saved yet is lost when you sign in. To keep it, stay on
        the page and copy it first.
      </p>
      <div className="flex justify-end gap-2 pt-2">
        <button
          type="button"
          onClick={onStay}
          className="h-8 px-4 rounded-md border border-border text-[12px] font-semibold hover:border-primary hover:text-primary focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
        >
          Stay on this page
        </button>
        <button
          type="button"
          onClick={() => login(returnTo)}
          className="h-8 px-4 rounded-md bg-primary text-primary-foreground text-[12px] font-semibold hover:bg-primary/90 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
        >
          Sign in again
        </button>
      </div>
    </div>
  </Dialog>
);

/**
 * What "Stay on this page" leaves behind: a strip that keeps saying so, with the way on. The
 * dialog is not reopened — background requests (a job poll every 1.5 s) keep failing while the
 * user copies their work, and a modal that came back on each would make staying impossible.
 */
export const SessionEndedBanner: React.FC<{ returnTo?: string }> = ({ returnTo }) => (
  <div
    role="alert"
    data-testid="session-ended-banner"
    className="fixed bottom-4 left-1/2 -translate-x-1/2 z-50 flex items-center gap-3 rounded-md border border-error/40 bg-card px-4 py-2.5 text-xs shadow-lg"
  >
    <span className="text-foreground">Your session has ended — nothing more can be saved until you sign in.</span>
    <button
      type="button"
      onClick={() => login(returnTo)}
      className="h-7 px-3 rounded-md bg-primary text-primary-foreground text-[12px] font-semibold hover:bg-primary/90 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
    >
      Sign in again
    </button>
  </div>
);

export const RequireAuth: React.FC<RequireAuthProps> = ({ children }) => {
  // Selected field by field: this component wraps every protected route, so a
  // whole-store subscription re-renders the entire app tree on any auth action.
  const status = useAuthStore((s) => s.sessionStatus);
  const sessionError = useAuthStore((s) => s.sessionError);
  const me = useAuthStore((s) => s.me);
  const loadMe = useAuthStore((s) => s.loadMe);
  const sessionExpired = useAuthStore((s) => s.sessionExpired);
  const setSessionExpired = useAuthStore((s) => s.setSessionExpired);
  const location = useLocation();
  const [retries, setRetries] = useState(0);
  const [stayed, setStayed] = useState(false);

  const authenticated = isAuthenticated();

  useEffect(() => {
    if (keycloakInitFailed || !authenticated) return;
    if (status === 'idle') void loadMe();
  }, [status, authenticated, loadMe]);

  useEffect(() => onSessionEnded(() => setSessionExpired(true)), [setSessionExpired]);

  const retry = useCallback(() => {
    setRetries((n) => n + 1);
    void loadMe();
  }, [loadMe]);

  const here = pathOf(location);
  const gate = sessionGate({ keycloakInitFailed, authenticated, status, me });

  if (gate === 'offline') {
    return <Navigate to="/offline" state={{ reason: 'identity', from: here }} replace />;
  }
  if (gate === 'login') {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  if (gate === 'problem') {
    const auto = isTransient(sessionError) && retries < SESSION_RETRY_BACKOFF.length
      ? SESSION_RETRY_BACKOFF[retries]
      : null;
    return (
      <SessionProblem
        key={retries}
        error={sessionError}
        onRetry={retry}
        retrying={false}
        autoRetryInSeconds={auto}
        from={here}
      />
    );
  }
  if (gate === 'loading') {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen bg-background text-foreground font-mono">
        <div className="w-10 h-10 border-4 border-primary border-t-transparent rounded-full animate-spin mb-4" />
        <span className="text-[10px] uppercase tracking-widest text-muted-foreground select-none">
          authenticating session...
        </span>
      </div>
    );
  }
  if (gate === 'no-organization' && me) {
    return <NoOrganization email={me.email} displayName={me.displayName} />;
  }

  return (
    <>
      {children}
      <SessionEndedDialog open={sessionExpired && !stayed} onStay={() => setStayed(true)} returnTo={here} />
      {sessionExpired && stayed && <SessionEndedBanner returnTo={here} />}
    </>
  );
};

export default RequireAuth;
