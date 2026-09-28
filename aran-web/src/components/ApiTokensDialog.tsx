import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Key, X, Copy, Check, Trash2 } from 'lucide-react';
import { api } from '../services/api';
import type { ApiTokenSummary, CreatedApiToken, QueryCatalog } from '../services/types';
import { errorMessage } from '@/lib/errors';
import { Dialog } from '@/components/ui/Dialog';
import { exampleOqlFrom, pythonExportSnippet } from '@/lib/tokenSnippet';

interface ApiTokensDialogProps {
  isOpen: boolean;
  onClose: () => void;
}

const SCOPES = [
  { value: 'READ' as const, label: 'Read only', hint: 'Query, export and browse. Cannot change anything.' },
  { value: 'FULL' as const, label: 'Full access', hint: 'Everything you can do in the UI.' },
];

const EXPIRIES = [
  { value: 30, label: '30 days' },
  { value: 90, label: '90 days' },
  { value: 365, label: '1 year' },
  { value: 0, label: 'Never' },
];

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

interface TokenListProps {
  tokens: ApiTokenSummary[];
  loading: boolean;
  /** Loading the list failed — never rendered as "no tokens yet" (J6-F18). */
  loadError: string | null;
  onRetry: () => void;
  /** The token whose revoke is awaiting confirmation, if any. */
  confirmingId: string | null;
  onAskRevoke: (token: ApiTokenSummary) => void;
  onConfirmRevoke: (token: ApiTokenSummary) => void;
  onCancelRevoke: () => void;
  revoking: boolean;
}

/**
 * The token list. Presentational so its states render in vitest: a load failure is its own state
 * with Retry, and revoke is a two-step action whose confirmation names the token's scope and last
 * use — one click used to cut off a nightly export with no warning (J6-F18 / J7-F21).
 */
export const TokenList: React.FC<TokenListProps> = ({
  tokens, loading, loadError, onRetry, confirmingId, onAskRevoke, onConfirmRevoke, onCancelRevoke, revoking,
}) => {
  // Each row's revoke button, so "keep" can hand focus back to the control that asked.
  const revokeButtons = useRef<Record<string, HTMLButtonElement | null>>({});
  if (loadError) {
    return (
      <div role="alert" data-testid="api-token-load-error"
        className="text-[11px] text-foreground border border-error/40 bg-error/10 rounded p-2 flex items-center gap-2">
        <span className="flex-1">Could not load your tokens — {loadError}</span>
        <button type="button" onClick={onRetry} className="underline hover:text-primary">retry</button>
      </div>
    );
  }
  if (loading && tokens.length === 0) return <div className="text-[11px] text-muted-foreground" role="status">loading…</div>;
  if (tokens.length === 0) return <div className="text-[11px] text-muted-foreground">no tokens yet</div>;
  return (
    <table className="w-full text-[11px]" data-testid="api-token-list">
      <thead>
        <tr className="text-[10px] text-muted-foreground text-left">
          <th className="py-1">name</th><th>token</th><th>scope</th>
          <th>status</th><th>last used</th><th>expires</th><th><span className="sr-only">actions</span></th>
        </tr>
      </thead>
      <tbody>
        {tokens.map(t => (
          <React.Fragment key={t.id}>
            <tr className="border-t border-border">
              <td className="py-1.5 pr-2">{t.name}</td>
              <td className="font-mono text-muted-foreground pr-2">{t.prefix}…</td>
              <td className="pr-2">{t.scope === 'READ' ? 'read' : 'full'}</td>
              <td className="pr-2">
                <span className={
                  t.status === 'active' ? 'text-success'
                    : t.status === 'expired' ? 'text-warning' : 'text-muted-foreground'
                }>{t.status}</span>
              </td>
              {/* A token nobody has touched is a token worth revoking. */}
              <td className="pr-2 text-muted-foreground">{formatDate(t.lastUsedAt)}</td>
              <td className="pr-2 text-muted-foreground">{formatDate(t.expiresAt)}</td>
              <td className="text-right">
                {t.status !== 'revoked' && (
                  <button type="button" onClick={() => onAskRevoke(t)}
                    ref={el => { revokeButtons.current[t.id] = el; }}
                    data-testid={`api-token-revoke-${t.id}`}
                    aria-label={`Revoke token ${t.name}`}
                    title="Revoke — cannot be undone"
                    className="text-muted-foreground hover:text-error">
                    <Trash2 className="w-3.5 h-3.5" aria-hidden="true" />
                  </button>
                )}
              </td>
            </tr>
            {confirmingId === t.id && (
              <tr>
                <td colSpan={7} className="pb-2">
                  {/* An inline confirmation takes focus on its SAFE choice, and the group is
                      described by its warning, so a screen reader announces what is being asked the
                      moment it appears (the APG alert-dialog rule: focus the least destructive
                      action). Escape or "keep" hands focus back to the revoke button. */}
                  {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions --
                      the keydown is the confirmation's Escape, the same contract the Dialog
                      primitive implements on its panel; the group has no more-interactive owner. */}
                  <div role="group" aria-label={`Confirm revoking ${t.name}`} aria-describedby={`api-token-revoke-warning-${t.id}`}
                    data-testid={`api-token-revoke-confirm-panel-${t.id}`}
                    onKeyDown={e => {
                      if (e.key !== 'Escape') return;
                      // Escape here backs out of the revoke, not out of the whole tokens dialog.
                      e.stopPropagation();
                      onCancelRevoke();
                      revokeButtons.current[t.id]?.focus();
                    }}
                    className="border border-error/40 bg-error/5 rounded p-2 flex flex-wrap items-center gap-2 text-foreground">
                    <span id={`api-token-revoke-warning-${t.id}`} className="flex-1 min-w-[16rem]">
                      Revoke “{t.name}” ({t.scope === 'READ' ? 'read' : 'full access'},{' '}
                      {t.lastUsedAt ? `last used ${formatDate(t.lastUsedAt)}` : 'never used'})? Anything using it —
                      a script, a scheduled job, an MCP client — stops working at once. This cannot be undone.
                    </span>
                    {/* eslint-disable-next-line jsx-a11y/no-autofocus -- not page-load focus (what
                        the rule guards against): it moves focus into a confirmation the user's own
                        click just revealed, once, on mount — as the Dialog primitive does in an
                        effect. On the safe choice, so a stray Enter keeps the token. */}
                    <button type="button" autoFocus
                      onClick={() => { onCancelRevoke(); revokeButtons.current[t.id]?.focus(); }}
                      className="underline"
                      data-testid={`api-token-revoke-cancel-${t.id}`}>keep</button>
                    <button type="button" onClick={() => onConfirmRevoke(t)} disabled={revoking}
                      data-testid={`api-token-revoke-confirm-${t.id}`}
                      className="h-7 px-2 border border-error/50 rounded text-error hover:bg-error/10 disabled:opacity-50">
                      {revoking ? 'revoking…' : 'revoke'}
                    </button>
                  </div>
                </td>
              </tr>
            )}
          </React.Fragment>
        ))}
      </tbody>
    </table>
  );
};

/**
 * Create, review and revoke API tokens.
 *
 * <p>The whole design turns on one constraint: the secret exists exactly once, in the create
 * response. So the freshly-minted token gets a panel of its own that will not go away until it is
 * dismissed, rather than a toast — a toast that auto-hides while the user is switching windows
 * would lose the credential for good.
 */
export const ApiTokensDialog: React.FC<ApiTokensDialogProps> = ({ isOpen, onClose }) => {
  const [tokens, setTokens] = useState<ApiTokenSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [revoking, setRevoking] = useState(false);
  const [catalog, setCatalog] = useState<QueryCatalog | null>(null);

  const [name, setName] = useState('');
  const [scope, setScope] = useState<'READ' | 'FULL'>('READ');
  const [expiryDays, setExpiryDays] = useState(90);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<CreatedApiToken | null>(null);
  const [copied, setCopied] = useState(false);

  // No synchronous setState: the first statement is the request, so every state update happens
  // after an await. That keeps the open-effect below from triggering a cascading render, and it
  // means re-opening the dialog shows the last known list while the refresh is in flight rather
  // than flashing a spinner over data that is almost certainly still correct.
  const refresh = useCallback(async () => {
    try {
      const list = await api.listApiTokens();
      setTokens(list);
      setLoadError(null);
    } catch (e: unknown) {
      // Its own state, not the create/revoke banner and never "no tokens yet" (J6-F18).
      setLoadError(errorMessage(e, 'the server did not answer.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // Fetch-on-open. The rule fires because `refresh` sets state, even though every one of those
    // updates happens after an await — its analysis follows the callback, not the timing. The
    // alternative React recommends is fetching in the handler that opens the dialog, but that
    // handler lives in IconRail, so it would mean exposing an imperative handle purely to satisfy
    // a lint rule. Same trade-off, same disable, as EditProfileDialog.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (isOpen) void refresh();
  }, [isOpen, refresh]);

  // The Python example names a class this tenant actually has (J7-F21); fetched once a secret is
  // on screen, which is the only time the example is shown. A failure leaves the placeholder.
  useEffect(() => {
    if (!created || catalog) return;
    let cancelled = false;
    api.queryEngineCatalog()
      .then(c => { if (!cancelled) setCatalog(c); })
      .catch(() => { /* the snippet falls back to a commented placeholder */ });
    return () => { cancelled = true; };
  }, [created, catalog]);

  if (!isOpen) return null;

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) {
      setError('Give the token a name so you can recognise it later.');
      return;
    }
    setCreating(true);
    setError('');
    try {
      const issued = await api.createApiToken({
        name: name.trim(),
        scope,
        expiryDays: expiryDays === 0 ? 0 : expiryDays,
      });
      setCreated(issued);
      setName('');
      setCopied(false);
      await refresh();
    } catch (e: unknown) {
      setError(errorMessage(e, 'Could not create the token.'));
    } finally {
      setCreating(false);
    }
  };

  const handleRevoke = async (token: ApiTokenSummary) => {
    setError('');
    setRevoking(true);
    try {
      await api.revokeApiToken(token.id);
      setConfirmingId(null);
      await refresh();
    } catch (e: unknown) {
      setError(errorMessage(e, 'Could not revoke the token.'));
    } finally {
      setRevoking(false);
    }
  };

  const copySecret = () => {
    if (!created) return;
    navigator.clipboard.writeText(created.secret).then(
      () => { setCopied(true); setTimeout(() => setCopied(false), 2500); },
      () => setError('Could not copy — select the token and copy it manually.'),
    );
  };

  return (
    <Dialog open onClose={onClose} label="API tokens" maxWidth="max-w-2xl"
      className="max-h-[85vh] overflow-y-auto" testId="api-tokens-dialog">
      <div>
        <div className="flex items-center justify-between px-5 py-3 border-b border-border">
          <div className="flex items-center gap-2">
            <Key className="w-4 h-4 text-primary" />
            <h2 className="text-[13px] font-semibold">API tokens</h2>
          </div>
          <button type="button" onClick={onClose} aria-label="Close API tokens"
            className="text-muted-foreground hover:text-foreground">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 flex flex-col gap-5">
          <p className="text-[11px] text-muted-foreground leading-relaxed">
            A token lets a script, notebook, scheduled job or MCP client talk to Aran without a
            browser login. It acts as you, in this tenant, until it expires or you revoke it.
            READ is enough for querying and for serving data products to an agent.
          </p>

          {error && (
            <div role="alert" className="text-[11px] text-foreground border border-error/40 bg-error/10 rounded p-2"
              data-testid="api-tokens-error">{error}</div>
          )}

          {/*
            The secret is unrecoverable, so this panel is deliberately modal-ish and persistent:
            it stays until explicitly dismissed. A toast would be the wrong shape — it can vanish
            while the user is tabbed away pasting it somewhere, and there is no second chance.
          */}
          {created && (
            <div className="border border-primary/40 bg-primary/5 rounded p-3 flex flex-col gap-2"
              data-testid="api-token-secret">
              <div className="text-[11px] font-semibold text-primary">
                Copy this now — it is not shown again
              </div>
              <div className="flex items-center gap-2">
                <code className="flex-1 font-mono text-[11px] break-all bg-background border border-border rounded px-2 py-1.5">
                  {created.secret}
                </code>
                <button type="button" onClick={copySecret} data-testid="api-token-copy"
                  className="flex items-center gap-1 h-8 px-2 text-[11px] border border-border rounded hover:border-primary/40">
                  {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                  {copied ? 'copied' : 'copy'}
                </button>
              </div>
              <details className="text-[11px] text-muted-foreground">
                <summary className="cursor-pointer hover:text-foreground">Use it from Python</summary>
                <pre className="mt-2 p-2 bg-background border border-border rounded overflow-x-auto font-mono text-[10px]"
                  data-testid="api-token-python">
                  {pythonExportSnippet(window.location.origin, created.secret, exampleOqlFrom(catalog))}
                </pre>
              </details>
              <button type="button" onClick={() => setCreated(null)}
                className="self-start text-[11px] text-muted-foreground hover:text-foreground underline">
                I have saved it
              </button>
            </div>
          )}

          <form onSubmit={handleCreate} className="flex flex-col gap-3 border border-border rounded p-3">
            <div className="text-[11px] font-semibold">New token</div>
            <input
              value={name}
              onChange={e => setName(e.target.value)}
              aria-label="Token name"
              placeholder="What is it for? e.g. nightly export"
              maxLength={120}
              data-testid="api-token-name"
              className="h-8 px-2 text-[11px] bg-background border border-border rounded focus:border-primary/50 outline-none"
            />
            <div className="flex flex-wrap gap-3">
              <label className="flex flex-col gap-1 text-[10px] text-muted-foreground">
                scope
                <select value={scope} onChange={e => setScope(e.target.value as 'READ' | 'FULL')}
                  data-testid="api-token-scope"
                  className="h-8 px-2 text-[11px] bg-background border border-border rounded">
                  {SCOPES.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-[10px] text-muted-foreground">
                expires
                <select value={expiryDays} onChange={e => setExpiryDays(Number(e.target.value))}
                  data-testid="api-token-expiry"
                  className="h-8 px-2 text-[11px] bg-background border border-border rounded">
                  {EXPIRIES.map(x => <option key={x.value} value={x.value}>{x.label}</option>)}
                </select>
              </label>
            </div>
            <div className="text-[10px] text-muted-foreground">
              {SCOPES.find(s => s.value === scope)?.hint}
            </div>
            <button type="submit" disabled={creating} data-testid="api-token-create"
              className="self-start h-8 px-3 text-[11px] font-medium bg-primary/10 text-primary border border-primary/30 rounded hover:bg-primary/20 disabled:opacity-50">
              {creating ? 'creating…' : 'create token'}
            </button>
          </form>

          <div className="flex flex-col gap-2">
            <div className="text-[11px] font-semibold">Your tokens</div>
            <TokenList
              tokens={tokens}
              loading={loading}
              loadError={loadError}
              onRetry={() => void refresh()}
              confirmingId={confirmingId}
              onAskRevoke={t => { setError(''); setConfirmingId(t.id); }}
              onConfirmRevoke={t => void handleRevoke(t)}
              onCancelRevoke={() => setConfirmingId(null)}
              revoking={revoking}
            />
          </div>
        </div>
      </div>
    </Dialog>
  );
};

export default ApiTokensDialog;
