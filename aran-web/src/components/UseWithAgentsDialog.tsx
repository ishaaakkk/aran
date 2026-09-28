import React, { useMemo, useState } from 'react';
import { Bot, X } from 'lucide-react';
import type { DataProduct } from '@/services/types';
import { AGENT_DEFAULT_ROWS, AGENT_MAX_ROWS, AGENT_READABLE_STATUSES } from '@/lib/agentSidecar';
import { buildMcpClientConfig, suggestBackendUrl } from '@/lib/mcpConfig';
import { accessModeOf } from '@/lib/productAccessMode';
import { Dialog } from '@/components/ui/Dialog';
import { copyText } from '@/lib/clipboard';

/**
 * "Use with agents" — the connect panel for the MCP sidecar (`aran-mcp/`).
 *
 * MCP has no screen of its own because it is an endpoint, not a feature: the model
 * belongs to the user's own client (Claude Desktop, Claude Code, Cursor), and Aran
 * ships none. So all this does is hand over a configuration block and say what it
 * needs — the same job ApiTokensDialog does for scripts and notebooks.
 *
 * There is no per-product publish switch behind this. The sidecar runs on the user's
 * machine under their own READ token, so anything it can reach they can already open
 * here.
 */

// The sidecar's own rules, pinned against aran_mcp by lib/agentSidecar.test.ts.
const SIDECAR_READABLE = AGENT_READABLE_STATUSES;
const SIDECAR_MAX_ROWS = AGENT_MAX_ROWS;
const SIDECAR_DEFAULT_ROWS = AGENT_DEFAULT_ROWS;

interface UseWithAgentsDialogProps {
  product: DataProduct;
  projectId: string;
  onClose: () => void;
}

/**
 * Which URL the sidecar should call.
 *
 * In a packaged deployment the SPA is served by the backend, so the page's own origin
 * is right. Under `npm run dev` it is not: Vite serves the page on 5173 and proxies
 * /api to 8081, so the origin the browser sees is a dev server the sidecar cannot use.
 * Guessing silently would produce a config that fails with a connection error pointing
 * at the wrong thing, so the dev case is detected and called out rather than hidden.
 */
export const UseWithAgentsDialog: React.FC<UseWithAgentsDialogProps> = ({
  product,
  projectId,
  onClose,
}) => {
  // null = idle; true/false = the last copy's real outcome (J5-F17: never "copied" on a refusal).
  const [copied, setCopied] = useState<boolean | null>(null);
  const { url, isDevGuess } = useMemo(() => suggestBackendUrl(window.location), []);

  const config = useMemo(() => buildMcpClientConfig(url, projectId), [url, projectId]);

  const copy = async () => {
    setCopied(await copyText(config));
    setTimeout(() => setCopied(null), 2500);
  };

  // A VIRTUAL product's snapshotId is null BY DESIGN, forever — keying the warning off
  // snapshotId alone told those users to refresh something the product neither offers nor
  // needs, while the real sidecar reads a virtual product's rows fine (V37, watched live
  // 2026-08-27: 21 real rows, and live DC enforcement reached the sidecar too).
  const virtual = accessModeOf(product) === 'VIRTUAL';
  // Keyed off STATUS, the way the sidecar filters (aran_mcp/_products.py READABLE_STATUSES):
  // a FAILED or MATERIALIZING product keeps its old snapshotId but is not offered to an agent,
  // which then answers "no readable data product" (J6-F11).
  const readableByAgents = SIDECAR_READABLE.has(product.status);
  const notMaterialized = !readableByAgents;

  return (
    <Dialog
      open
      onClose={onClose}
      label={`Use ${product.name} with agents`}
      maxWidth="max-w-2xl"
      className="max-h-[85vh] overflow-y-auto"
      testId="use-with-agents-dialog"
    >
      <div>
        <div className="flex items-center justify-between px-5 py-3 border-b border-border">
          <div className="flex items-center gap-2">
            <Bot className="w-4 h-4 text-primary" />
            <h2 className="text-[13px] font-semibold">Use “{product.name}” with agents</h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="text-muted-foreground hover:text-foreground"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 flex flex-col gap-5 font-mono text-[11px]">
          <p className="text-muted-foreground leading-relaxed">
            Aran can serve this data product over{' '}
            <span className="text-foreground">MCP</span>, so an agent you already run —
            Claude Desktop, Claude Code, Cursor — can read it. Aran ships no model and
            runs no inference: your client’s model does the language part, Aran answers
            with data. The sidecar is read-only: it can list products and read rows, and
            change nothing.
          </p>

          {/* J6-F3: the scoped truth, not the old "never leaves" promise. The sidecar process is
              local, but what it returns is handed to the agent, and a hosted client ships its
              context to the model provider; the token is the user's, not the product's. */}
          <div
            className="border border-border bg-muted/30 rounded p-3 text-foreground leading-relaxed"
            data-testid="use-with-agents-privacy"
          >
            <span className="font-semibold">Where the rows go.</span> The sidecar runs on your
            machine and calls this Aran server. Every row it returns enters the agent’s
            context, and a client that uses a hosted model (Claude Desktop, Claude Code,
            Cursor) sends that context to its model provider. The READ token you paste can
            read everything you can read in this organization, not only this product — do not
            connect data your organization may not send to that provider.
          </div>

          {virtual && readableByAgents && (
            <div
              className="border border-emerald-500/40 bg-emerald-500/10 rounded p-3 text-emerald-300 leading-relaxed"
              data-testid="use-with-agents-virtual-live"
            >
              This product is virtual: it is recomputed on every call from the sources the
              active schema points at — live sources live, uploaded ones as of their last
              upload or refresh — so it is ready for agents now, with nothing to refresh. Its
              reads take the agent’s row limit like any other product’s. If a schema
              change breaks its query, agent reads fail with an error rather than returning
              stale rows.
            </div>
          )}

          {notMaterialized && (
            <div
              className="border border-amber-500/40 bg-amber-500/10 rounded p-3 text-amber-300 leading-relaxed"
              data-testid="use-with-agents-not-materialized"
              data-status={product.status}
            >
              {product.status === 'FAILED'
                ? 'Its last refresh failed, so the sidecar does not offer it and an agent cannot read it. Refresh it successfully first.'
                : product.status === 'MATERIALIZING'
                  ? 'A refresh is running; an agent can read it once it is READY.'
                  : <>This product has no snapshot yet, so an agent cannot read it. Refresh it
                      first — products only become visible to the sidecar once they are
                      materialized.</>}
            </div>
          )}
          <p className="text-muted-foreground leading-relaxed" data-testid="use-with-agents-row-cap">
            One read returns at most {SIDECAR_MAX_ROWS} rows ({SIDECAR_DEFAULT_ROWS} unless the agent
            asks for more) — an agent sees a sample of a large product, not all of it.
          </p>

          <ol className="flex flex-col gap-3 list-decimal pl-4 text-muted-foreground leading-relaxed">
            <li>
              Install the sidecar once. It is not published as a package yet, so it installs
              from a source checkout of Aran: in the checkout, run{' '}
              <code className="text-foreground">make aran-mcp</code> (Python 3.10 or newer).
            </li>
            <li>
              Create an API token with scope <span className="text-foreground">READ</span>:
              your avatar at the bottom of the left rail →{' '}
              <span className="text-foreground">API Tokens</span>. READ is deliberately
              enough — the sidecar only ever issues GETs.
            </li>
            <li>Put this in your MCP client’s configuration, then restart it:</li>
          </ol>

          <div>
            <div className="flex items-center justify-between mb-1">
              <span className="text-[10px] text-muted-foreground uppercase tracking-wider">
                claude_desktop_config.json
              </span>
              <button
                type="button"
                onClick={() => void copy()}
                data-testid="use-with-agents-copy"
                className="text-[10px] px-2 py-0.5 border border-border rounded hover:border-primary hover:text-primary"
              >
                {copied === true ? '✓ copied' : copied === false ? 'couldn’t copy — select the text below' : '⎘ copy'}
              </button>
            </div>
            <pre
              className="bg-muted/30 border border-border rounded p-3 overflow-x-auto text-foreground whitespace-pre"
              data-testid="use-with-agents-config"
            >
              {config}
            </pre>
          </div>

          <ul className="flex flex-col gap-2 text-muted-foreground leading-relaxed">
            <li>
              — Replace <code className="text-foreground">command</code> with the real
              absolute path to <code className="text-foreground">aran-mcp</code>; a client
              launches it as a subprocess and will not expand{' '}
              <code className="text-foreground">~</code> or use your shell’s PATH.
            </li>
            <li>
              — Put the token in <code className="text-foreground">env</code>, not on the
              command line: arguments show up in process listings.
            </li>
            {isDevGuess && (
              <li data-testid="use-with-agents-dev-note">
                — <span className="text-amber-400">This page is on the Vite dev server</span>{' '}
                (:5173), which proxies the API. The sidecar talks to the backend directly,
                so <code className="text-foreground">ARAN_URL</code> is set to{' '}
                <code className="text-foreground">{url}</code> above — correct it if your
                backend is elsewhere.
              </li>
            )}
            <li>
              — <code className="text-foreground">ARAN_PROJECT</code> limits the agent to
              this project. Drop it to expose every product your token can read; it
              controls noise, not access.
            </li>
          </ul>

          <div className="border-t border-border pt-4 text-muted-foreground leading-relaxed">
            Then ask your agent for it by name, for example:{' '}
            <span className="text-foreground">“read the {product.name} product from aran”</span>.
            It can list products and read rows; it cannot change anything.
          </div>
        </div>
      </div>
    </Dialog>
  );
};
