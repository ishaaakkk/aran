import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '@/services/api';
import { errorMessage } from '@/lib/errors';
import { CmdHeader } from '@/components/CmdHeader';
import { JobCard } from '@/components/JobCard';
import { ResultTable } from '@/components/ResultTable';
import { useProjectsStore } from '@/stores/projectsStore';
import { useAuthStore } from '@/stores/authStore';
import { useJobsStore } from '@/stores/jobsStore';
import { useJobsSettled } from '@/hooks/useJobEvents';
import { isFailure, isLive, isProductJob, latestJob, materializeBusy, resolveProductStatus } from '@/lib/jobs';
import { UseWithAgentsDialog } from '@/components/UseWithAgentsDialog';
import { ShapeAsCubeDialog } from '@/components/ShapeAsCubeDialog';
import { Dialog } from '@/components/ui/Dialog';
import { ErrorBanner } from '@/components/ui/ErrorBanner';
import { useConfirm } from '@/components/ui/useConfirm';
import { accessModeOf, ACCESS_MODE_COPY } from '@/lib/productAccessMode';
import { cubesEnabledFrom } from '@/lib/features';
import { CONCEPTS } from '@/lib/concepts';
import { localName } from '@/lib/iri';
import { copyText } from '@/lib/clipboard';
import { downloadResultCsv } from '@/lib/resultCsv';
import {
  allRowsDownload, builtAgainstLabel, connectionDetails, deleteImpact, deleteNeedsTypedName, downloadFailureMessage,
  focusAfterClose, openButtonTestId, previewsOnOpen, productDoors, tableNameOf, type ConnectionDetails,
} from '@/lib/productDoors';
import { docsUrl, type DocsPage } from '@/lib/docsLinks';
import { suggestBackendUrl } from '@/lib/mcpConfig';
import { ApiTokensDialog } from '@/components/ApiTokensDialog';
import { X } from 'lucide-react';
import type { CubeDto, DataProduct, DataProductStatus, Job, ProductAccessMode, QueryRunResult, SchemaVersion } from '@/services/types';
import './workbench.css';

/**
 * ProductsView (M8).
 *
 * A project's data products: named OQL views defined against a schema version,
 * either materialized to parquet snapshots in the lake or VIRTUAL (recomputed on
 * read). The list shows real freshness state (READY / STALE / FAILED — never
 * decorative); Refresh re-materializes as a job whose stages render live in a
 * JobCard. The detail drawer previews a MATERIALIZED product's snapshot, and
 * re-executes a VIRTUAL product's query.
 */

const STATUS_STYLES: Record<DataProductStatus, string> = {
  DRAFT: 'bg-muted/20 text-muted-foreground border-border',
  MATERIALIZING: 'bg-violet-500/15 text-violet-400 border-violet-500/30',
  READY: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
  STALE: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
  FAILED: 'bg-rose-500/15 text-rose-400 border-rose-500/30',
};

const StatusBadge: React.FC<{ status: DataProductStatus }> = ({ status }) => (
  <span
    className={`inline-flex items-center font-mono font-semibold uppercase tracking-wider border rounded-sm text-[9px] px-1.5 py-[2px] leading-tight select-none ${STATUS_STYLES[status]}`}
  >
    {status}
  </span>
);

const ACCESS_MODE_STYLES: Record<ProductAccessMode, string> = {
  // Deliberately as understated as DRAFT's status style — MATERIALIZED is the unmarked,
  // default case; VIRTUAL is the one that needs to catch the eye.
  MATERIALIZED: 'bg-muted/20 text-muted-foreground border-border',
  VIRTUAL: 'bg-sky-500/15 text-sky-400 border-sky-500/30',
};

/* What the chip SAYS. "Snapshot" describes what a reader gets; MATERIALIZED is the
   wire enum and stays out of the UI (design canvas, chosen system). The words live in
   ACCESS_MODE_COPY so the dialog, the list and the detail cannot drift apart (J6-F2). */
export const AccessBadge: React.FC<{ mode: ProductAccessMode }> = ({ mode }) => (
  <span
    data-testid="product-access-badge"
    title={ACCESS_MODE_COPY[mode].title}
    className={`inline-flex items-center font-semibold border rounded-full text-[11px] px-2 py-[2px] leading-tight select-none ${ACCESS_MODE_STYLES[mode]}`}
  >
    {ACCESS_MODE_COPY[mode].label}
  </span>
);

/**
 * What the list shows when it has no rows — which is two different facts (J6-F22): a load that
 * failed (say so, offer Retry; never "No data products yet", which reads as "create one" and
 * invites a duplicate) or a project with none (say where products are made, and link there).
 * Presentational, like ProductRow, so it renders in tests without the view's network effects.
 */
export const ProductsEmptyState: React.FC<{ error: string | null; onRetry: () => void }> = ({ error, onRetry }) =>
  error ? (
    <div className="p-4">
      <ErrorBanner
        title="Couldn’t load data products"
        testId="products-load-error"
        action={
          <button
            type="button"
            data-testid="products-retry"
            onClick={onRetry}
            className="text-[11px] px-2 py-1 border border-error/40 rounded hover:bg-error/10"
          >
            Retry
          </button>
        }
      >
        {error}
      </ErrorBanner>
    </div>
  ) : (
    <div className="p-4 text-[11px] font-mono text-muted-foreground flex flex-col items-start gap-2">
      <span>No data products yet. A product is a query you save from the workbench (“save as data product” under its result).</span>
      <Link
        to="/oql"
        className="text-[11px] px-2 py-1 border border-border rounded text-foreground hover:border-primary hover:text-primary"
      >
        Open the workbench
      </Link>
    </div>
  );

interface ProductRowProps {
  product: DataProduct;
  versionLabel: string;
  /**
   * The product's latest materialize job, when the page should show it: a live one, or a failure
   * nobody has dismissed. Found in the jobs store by product id, so it survives a navigation and
   * a reload (UI audit J6-F7) — and it covers a cube's star-table jobs, which no page started.
   */
  job?: Job | null;
  onOpenDetail: (product: DataProduct) => void;
  onUseWithAgents: (product: DataProduct) => void;
  onMaterialize: (product: DataProduct) => void;
  /** The card's own terminal callback. The page re-reads products through `useJobsSettled`. */
  onJobDone?: () => void;
  /** Dismiss the failed job's card; the jobs drawer keeps it. */
  onJobDismiss?: () => void;
  /** A retry from the failed job's card started this job. */
  onJobRetried?: (jobId: string) => void;
  /**
   * Refresh must wait (`materializeBusy`): a run is live, starting, or the row says
   * MATERIALIZING and the page does not yet know that run ended. Defaults to "a job is live".
   */
  busy?: boolean;
}

/** One row of the products table, plus its job row when a materialization is running or failed.
 *  Presentational only (no state of its own) so it can be rendered directly in tests —
 *  `ProductsView` itself fetches over the network, which `renderToStaticMarkup` cannot wait on. */
export const ProductRow: React.FC<ProductRowProps> = ({
  product: p, versionLabel, job = null, onOpenDetail, onUseWithAgents, onMaterialize, onJobDone,
  onJobDismiss, onJobRetried, busy,
}) => {
  const virtual = accessModeOf(p) === 'VIRTUAL';
  // Register F5 for products: MATERIALIZING is only true while a job is live. A failed job makes
  // the row FAILED with its reason, and with no live job nothing may keep refresh disabled.
  const resolved = resolveProductStatus(p.status, job);
  const failedJob = job && (job.status === 'FAILED' || job.status === 'CANCELLED');
  const lastError = resolved.status === 'FAILED' && !failedJob ? (p.lastError ?? resolved.reason) : null;
  const refreshBlocked = busy ?? resolved.live;
  return (
    <React.Fragment>
      <tr
        // Name-keyed so a spec can assert on ONE product's row — the access badge and the
        // refresh button are per-row, and the claim `v37-virtual-products.spec.ts` makes is
        // that a VIRTUAL row has the badge and NOT the button while a MATERIALIZED row next
        // to it has both. An unscoped `product-access-badge` read cannot express that.
        data-testid={`product-row-${p.name}`}
        className="border-b border-border/50 hover:bg-muted/20 cursor-pointer"
        onClick={() => onOpenDetail(p)}
      >
        <td className="px-4 py-2 text-foreground">
          {/* The name is the keyboard way in (J6-F8): the row's click is a mouse convenience a
              keyboard or screen-reader user cannot reach. */}
          <button
            type="button"
            className="text-left text-foreground hover:underline underline-offset-2 focus-visible:underline"
            data-testid={openButtonTestId(p.name)}
            onClick={e => { e.stopPropagation(); onOpenDetail(p); }}
          >
            {p.name}
          </button>
        </td>
        {/* Access and lifecycle are two different facts — one says how reads work, the
            other whether the data needs attention — so they get two columns rather than
            two unexplained chips crammed into one (redesign brief, products MED). */}
        <td className="px-4 py-2">
          <StatusBadge status={resolved.status as DataProductStatus} />
        </td>
        <td className="px-4 py-2">
          <AccessBadge mode={accessModeOf(p)} />
        </td>
        <td className="px-4 py-2 text-muted-foreground" title={p.schemaVersionId}>
          schema {versionLabel}
        </td>
        <td className="px-4 py-2 text-right tabular-nums">
          {p.rowCount !== null ? p.rowCount.toLocaleString() : '—'}
        </td>
        <td className="px-4 py-2 text-muted-foreground">
          {virtual ? (
            // VIRTUAL never materializes, so "never" (the MATERIALIZED not-yet-refreshed
            // case) would read as broken rather than as the mode it actually is.
            <span className="text-sky-400">{ACCESS_MODE_COPY.VIRTUAL.rowFreshness}</span>
          ) : (
            p.materializedAt ? new Date(p.materializedAt).toLocaleString() : 'never'
          )}
        </td>
        <td className="px-4 py-2 text-right whitespace-nowrap">
          <button
            className="text-[10px] px-2 py-1 border border-border rounded hover:border-primary hover:text-primary"
            onClick={e => { e.stopPropagation(); onUseWithAgents(p); }}
            data-testid={`use-with-agents-${p.name}`}
            title="Read this product from Claude Desktop, Claude Code or another MCP client"
          >
            ⌁ agents
          </button>
          {!virtual && (
            <button
              className="ml-2 text-[10px] px-2 py-1 border border-border rounded hover:border-primary hover:text-primary"
              disabled={refreshBlocked}
              onClick={e => { e.stopPropagation(); void onMaterialize(p); }}
              data-testid={`materialize-${p.name}`}
              title={resolved.live
                ? 'A materialization of this product is running'
                : refreshBlocked
                  ? 'This product is materializing — waiting for its job to say it has finished (see Jobs)'
                  : 'Re-materialize from the current schema'}
            >
              ⟳ refresh
            </button>
          )}
        </td>
      </tr>
      {job && (
        <tr className="border-b border-border/50">
          <td colSpan={7} className="px-4 py-2">
            <JobCard
              jobId={job.id}
              title={`materialize ${p.name}`}
              onDone={onJobDone}
              onDismiss={onJobDismiss}
              onRetried={onJobRetried}
            />
          </td>
        </tr>
      )}
      {lastError && (
        // The last materialization's failure, under the row, even with no job card — a product
        // that failed yesterday still says why (J6-F7).
        <tr className="border-b border-border/50">
          <td colSpan={7} className="px-4 py-2 text-[11px] text-error" data-testid={`product-error-${p.name}`}>
            <span className="font-semibold">Last materialization failed: </span>
            <span className="select-text">{lastError}</span>
          </td>
        </tr>
      )}
    </React.Fragment>
  );
};

interface CubesSectionProps {
  projectId: string;
  /** The wizard's open state. Owned by `ProductsView` because the button that opens it lives in
   *  the products header — a cube is made of the same classes those products are. */
  shapeOpen: boolean;
  onShapeClose: () => void;
  /** Creating a cube writes one data product per star table, so the products list is stale. */
  onProductsChanged: () => void;
}

/**
 * Everything the cube surface is: the list, the wizard, the `cube.yaml` viewer — and the only
 * place `api.listCubes` is called from.
 *
 * That last part is the point of the extraction. The surface is behind a deployment flag
 * (`aran.cubes.enabled`, published as `features.cubes` on `GET /api/me`), and with the flag off
 * the backend does not merely refuse the routes — `CubeController` is not a bean, so they answer
 * 404. Rendering this component conditionally makes "no panel" and "no cube request" one fact
 * instead of two guards that could drift apart: the fetch lives in its own effect here, and an
 * unmounted component runs no effect.
 */
export const CubesSection: React.FC<CubesSectionProps> = ({
  projectId, shapeOpen, onShapeClose, onProductsChanged,
}) => {
  const [cubes, setCubes] = useState<CubeDto[]>([]);
  const [cubesError, setCubesError] = useState<string | null>(null);
  // The cube.yaml viewer, keyed off the cube whose YAML is on screen.
  const [yamlFor, setYamlFor] = useState<CubeDto | null>(null);
  const [yaml, setYaml] = useState<string | null>(null);
  const [yamlError, setYamlError] = useState<string | null>(null);
  const [confirm, confirmDialog] = useConfirm();

  const refreshCubes = (pid = projectId) => {
    if (!pid) return;
    api.listCubes(pid)
      .then(list => { setCubes(list); setCubesError(null); })
      .catch((e: unknown) => setCubesError(errorMessage(e, 'Failed to load cubes.')));
  };

  useEffect(() => {
    // Clears the previous project's cubes before fetching the new one's — a network fetch has
    // no render-time equivalent.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setCubes([]);
    refreshCubes(projectId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  async function removeCube(cube: CubeDto) {
    // Still the browser's confirm: products now delete through DeleteProductDialog, and moving
    // this onto the one shared confirmation is J8-F14 (package A7). A cube deletes the star it
    // created — CubeService.delete removes every member product — so the confirmation says so.
    const ok = await confirm({
      title: `Delete cube "${cube.name}"?`,
      confirmLabel: 'Delete cube',
      testId: 'cube-delete',
      consequences: [
        `Its ${cube.products.length} star products (fact + dimensions) are deleted with it.`,
        'Anything reading those products over JDBC, the Iceberg catalog or an agent can no longer find them.',
        'This cannot be undone.',
      ],
    });
    if (!ok) return;
    try {
      await api.deleteCube(projectId, cube.id);
      refreshCubes();
    } catch (e: unknown) {
      setCubesError(errorMessage(e, 'Failed to delete the cube.'));
    }
  }

  function openYaml(cube: CubeDto) {
    setYamlFor(cube);
    setYaml(null);
    setYamlError(null);
    api.getCubeYaml(projectId, cube.id)
      .then(setYaml)
      .catch((e: unknown) => setYamlError(errorMessage(e, 'Failed to load cube.yaml.')));
  }

  return (
    <>
      {confirmDialog}
      {/* Cubes sit below the products table rather than in a section of their own: a cube is
          made of the same integrated classes, and creating one writes products here. */}
      <div className="bg-card border border-border rounded overflow-hidden">
        <CmdHeader
          command="Cubes"
          right={<span>{cubes.length} cube{cubes.length === 1 ? '' : 's'}</span>}
        />
        {cubesError && <ErrorBanner className="m-4 font-mono">{cubesError}</ErrorBanner>}
        {!projectId ? (
          <div className="p-4 text-[11px] font-mono text-muted-foreground">
            Select a project to list its cubes.
          </div>
        ) : cubes.length === 0 ? (
          <div className="p-4 text-[11px] font-mono text-muted-foreground">
            No cubes yet. `shape as cube` walks a class into a star — fact, measures, dimensions,
            hierarchies — pre-filled from the evidence already profiled.
          </div>
        ) : (
          <table className="w-full text-[11px] font-mono" data-testid="cubes-list">
            <thead>
              <tr className="border-b border-border text-left text-muted-foreground">
                <th className="px-4 py-2 font-semibold">name</th>
                <th className="px-4 py-2 font-semibold">fact</th>
                <th className="px-4 py-2 font-semibold text-right">dimensions</th>
                <th className="px-4 py-2 font-semibold">created</th>
                <th className="px-4 py-2 font-semibold"></th>
              </tr>
            </thead>
            <tbody>
              {cubes.map(c => (
                <tr key={c.id} data-testid={`cube-row-${c.name}`} className="border-b border-border/50 hover:bg-muted/20">
                  <td className="px-4 py-2 text-foreground">{c.name}</td>
                  <td className="px-4 py-2 text-muted-foreground" title={c.factClassIri}>
                    {localName(c.factClassIri)}
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums text-muted-foreground">
                    {c.definition.dimensions.length}
                  </td>
                  <td className="px-4 py-2 text-muted-foreground">
                    {new Date(c.createdAt).toLocaleString()}
                  </td>
                  <td className="px-4 py-2 text-right whitespace-nowrap">
                    <button
                      className="text-[10px] px-2 py-1 border border-border rounded hover:border-primary hover:text-primary"
                      onClick={() => openYaml(c)}
                      data-testid={`cube-yaml-${c.name}`}
                      title="The cube definition as its canonical cube.yaml"
                    >
                      view cube.yaml
                    </button>
                    <button
                      className="ml-2 text-[10px] px-2 py-1 border border-border rounded text-rose-400 hover:border-rose-400"
                      onClick={() => void removeCube(c)}
                      data-testid={`cube-delete-${c.name}`}
                    >
                      delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <ShapeAsCubeDialog
        open={shapeOpen}
        projectId={projectId}
        onClose={onShapeClose}
        onCreated={(cube) => {
          // Each star table materializes as its own job. Registered as started here, so the rows
          // re-read when those jobs end even if they finish before any list saw them run.
          for (const jobId of cube.jobIds) void useJobsStore.getState().started(jobId);
          refreshCubes();
          onProductsChanged();
        }}
      />

      <Dialog
        open={yamlFor !== null}
        onClose={() => setYamlFor(null)}
        label={`cube.yaml for ${yamlFor?.name ?? ''}`}
        maxWidth="max-w-3xl"
        className="font-mono"
        testId="cube-yaml-dialog"
      >
        <div className="h-9 flex items-center px-4 bg-muted/30 border-b border-border text-[11px] text-muted-foreground">
          <span className="text-primary">$</span>&nbsp;cat {yamlFor?.name}/cube.yaml
        </div>
        <div className="p-4 text-[11px]">
          {yamlError ? (
            <ErrorBanner className="p-2">{yamlError}</ErrorBanner>
          ) : yaml === null ? (
            <div className="text-muted-foreground">loading…</div>
          ) : (
            <pre className="bg-muted/30 border border-border rounded p-3 overflow-auto max-h-[60vh] text-foreground whitespace-pre">
              {yaml}
            </pre>
          )}
        </div>
      </Dialog>
    </>
  );
};

/** Rows a product preview asks for — a page, and said to be one (R5). */
export const PREVIEW_ROWS = 100;

/**
 * A value a user pastes into another tool, with a copy button that reports what really happened —
 * a refused clipboard (an insecure origin, a denied permission) says so instead of "copied".
 */
const CopyableCode: React.FC<{ value: string; label: string; testId: string; inline?: boolean }> = ({
  value, label, testId, inline,
}) => {
  const [outcome, setOutcome] = useState<'copied' | 'failed' | null>(null);
  const copy = () => {
    const settle = (next: 'copied' | 'failed') => {
      setOutcome(next);
      setTimeout(() => setOutcome(null), 2500);
    };
    if (!navigator.clipboard) { settle('failed'); return; }
    navigator.clipboard.writeText(value).then(() => settle('copied'), () => settle('failed'));
  };
  const Wrapper = inline ? 'span' : 'div';
  return (
    <Wrapper className={inline ? 'inline-flex items-baseline gap-1.5' : 'flex items-start gap-2 min-w-0'}>
      <code className="text-foreground break-all" data-testid={testId}>{value}</code>
      <button
        type="button"
        className="shrink-0 text-[10px] px-1.5 py-px border border-border rounded hover:border-primary hover:text-primary"
        aria-label={`Copy ${label}`}
        onClick={copy}
      >
        {outcome === 'copied' ? '✓ copied' : outcome === 'failed' ? 'copy failed — select it' : '⎘ copy'}
      </button>
      <span role="status" className="sr-only">
        {outcome === 'copied' ? `Copied ${label}` : outcome === 'failed' ? `Could not copy ${label}` : ''}
      </span>
    </Wrapper>
  );
};

/** A link into the user docs, which open in a new tab and say so. */
const DocsLink: React.FC<{ page: DocsPage; children: React.ReactNode }> = ({ page, children }) => (
  <a href={docsUrl(page)} target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">
    {children} ↗<span className="sr-only"> (opens in a new tab)</span>
  </a>
);

interface ProductDetailProps {
  product: DataProduct;
  pinLabel: string;
  versionNumberById: Record<string, number>;
  /** The deployment's URLs as an external client reaches them (J6-F4). */
  connection: ConnectionDetails;
  /** The URLs were derived for a page on the Vite dev server (`suggestBackendUrl`). */
  connectionIsDevGuess: boolean;
  onManageTokens: () => void;
  preview: QueryRunResult | null;
  previewLoading: boolean;
  previewError: string | null;
  onRunPreview: () => void;
  /** true = copied, false = the clipboard refused (the value stays selectable), null = idle. */
  snapshotCopied: boolean | null;
  onCopySnapshot: () => void;
  downloading: boolean;
  downloadError: string | null;
  onDownloadAll: () => void;
  onUseWithAgents: () => void;
  onDelete: () => void;
  onClose: () => void;
  /** Focus lands here when the panel opens, so it is announced and scrolled to (J6-F8). */
  ref?: React.Ref<HTMLElement>;
}

/**
 * One product's detail panel. Presentational — `ProductsView` owns the fetches — so the states
 * that matter can be rendered directly in tests: the live-preview gate (J6-F19), the doors that
 * serve it (J6-F4), what its rows mean (UI-OD12) and why delete is unavailable (J6-F17).
 */
export const ProductDetail: React.FC<ProductDetailProps> = ({
  product: detail, pinLabel, versionNumberById, connection, connectionIsDevGuess, onManageTokens,
  preview, previewLoading, previewError, onRunPreview,
  snapshotCopied, onCopySnapshot, downloading, downloadError, onDownloadAll, onUseWithAgents,
  onDelete, onClose, ref,
}) => {
  const virtual = accessModeOf(detail) === 'VIRTUAL';
  const provenance = builtAgainstLabel(detail, versionNumberById);
  const doors = productDoors(detail);
  const allRows = allRowsDownload(detail);
  return (
    <section
      ref={ref}
      tabIndex={-1}
      aria-label={`Data product ${detail.name}`}
      data-testid="product-detail"
      className="bg-card border border-border rounded overflow-hidden scroll-mt-4 outline-none focus-visible:ring-1 focus-visible:ring-primary"
    >
      <CmdHeader
        command={`${detail.name}`}
        right={
          <>
            <StatusBadge status={detail.status} />
            <AccessBadge mode={accessModeOf(detail)} />
            {/* Disabled with its reason rather than refused after a confirm (J6-F17): a star
                member goes with its cube (DataProductService.delete). */}
            <button
              type="button"
              className="text-error hover:underline text-[10px] disabled:text-muted-foreground disabled:no-underline disabled:cursor-not-allowed"
              data-testid="product-delete"
              disabled={!!detail.memberOfCube}
              title={detail.memberOfCube ? `Part of cube '${detail.memberOfCube}' — delete the cube to remove its whole star` : undefined}
              onClick={onDelete}
            >
              delete
            </button>
            <button type="button" onClick={onClose} aria-label="Close product details">
              <X className="w-3.5 h-3.5" aria-hidden="true" />
            </button>
          </>
        }
      />
      <div className="p-4 space-y-4 font-mono text-[11px]">
        <div className="text-muted-foreground">
          {virtual ? (
            <>Virtual — reads whatever schema is ACTIVE now, not the version pinned at
              creation ({pinLabel}). It has no snapshot and never materializes.</>
          ) : (
            <>Pinned to schema {pinLabel}. Changing the pinned version means creating a new product for now.
              {' '}Products over federated datasets materialize from the live source.</>
          )}
        </div>
        {provenance && (
          // What the rows mean (UI-OD12): a refresh runs under ACTIVE, so the build's version, not
          // the definition-time one, is the provenance a reader needs — dbt Cloud shows the run a
          // model was last built by, Foundry a dataset's build.
          <div className="text-foreground" data-testid="product-built-against">{provenance}</div>
        )}
        {detail.memberOfCube && (
          <div className="text-muted-foreground" data-testid="product-cube-member">
            Part of cube <span className="text-foreground">{detail.memberOfCube}</span> — it is deleted with its cube, not on its own.
          </div>
        )}
        {detail.description && <div className="text-foreground">{detail.description}</div>}
        <div>
          <div className="text-[10px] text-muted-foreground mb-1 uppercase tracking-wider">oql</div>
          <pre className="bg-muted/30 border border-border rounded p-3 overflow-x-auto text-foreground whitespace-pre-wrap">
            {detail.oql}
          </pre>
        </div>
        {detail.snapshotId && (
          <div className="flex items-center gap-2">
            <span className="text-[10px] text-muted-foreground uppercase tracking-wider">snapshot</span>
            <code className="text-foreground">{detail.snapshotId}</code>
            <button
              type="button"
              className="text-[10px] px-2 py-0.5 border border-border rounded hover:border-primary hover:text-primary"
              onClick={onCopySnapshot}
            >
              {snapshotCopied === true ? '✓ copied' : snapshotCopied === false ? 'couldn’t copy — select it' : '⎘ copy'}
            </button>
          </div>
        )}
        {detail.lastError && (
          <div role="alert" className="border border-error/40 bg-error/10 rounded p-3 text-foreground">
            {detail.lastError}
          </div>
        )}

        {/* Where it can be read from, and how to get there (J6-F4): the served table name is
            normalised and may carry a suffix, so it is shown rather than guessed from the product
            name, beside the URL, driver and catalog a client needs — Databricks' "Connection
            details", Snowsight's "Connect a tool", each value copyable, each door linked to its
            guide. */}
        <div data-testid="product-connect">
          <div className="text-[10px] text-muted-foreground mb-1 uppercase tracking-wider">connect</div>
          <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-2">
            <dt className="text-muted-foreground">JDBC</dt>
            <dd className="space-y-1 min-w-0">
              {doors.jdbc
                ? <CopyableCode value={doors.jdbc} label="the SQL statement" testId="product-connect-jdbc" />
                : <div className="text-muted-foreground">not a table yet — refresh it to build its first snapshot</div>}
              <CopyableCode value={connection.jdbcUrl} label="the JDBC URL" testId="product-connect-jdbc-url" />
              <div className="text-muted-foreground">
                driver <code className="text-foreground" data-testid="product-connect-jdbc-driver">{connection.jdbcDriverClass}</code>
                {' '}({connection.jdbcDriverArtifact}) · <DocsLink page="programmatic-access/jdbc">JDBC guide</DocsLink>
              </div>
            </dd>
            <dt className="text-muted-foreground">Iceberg</dt>
            <dd className="space-y-1 min-w-0">
              {doors.iceberg.served ? (
                <>
                  <CopyableCode value={doors.iceberg.table} label="the Iceberg table" testId="product-connect-iceberg-table" />
                  <div className="text-muted-foreground">
                    REST catalog <CopyableCode inline value={connection.icebergCatalogUri} label="the Iceberg catalog URI" testId="product-connect-iceberg-uri" />
                    {' '}· namespace <code className="text-foreground">{connection.icebergNamespace}</code>
                  </div>
                </>
              ) : (
                <div className="text-muted-foreground" data-testid="product-connect-iceberg-unserved">{doors.iceberg.reason}</div>
              )}
              <div className="text-muted-foreground"><DocsLink page="programmatic-access/iceberg">Iceberg guide</DocsLink></div>
            </dd>
            <dt className="text-muted-foreground">Agents</dt>
            <dd>
              <button type="button" className="text-primary hover:underline" onClick={onUseWithAgents}>
                use with agents (MCP) →
              </button>
            </dd>
            <dt className="text-muted-foreground">Token</dt>
            <dd className="text-muted-foreground">
              Every door authenticates with an API token (<span className="text-foreground">READ</span> is enough) — it
              is the password in the JDBC URL and the bearer token for Iceberg.{' '}
              <button type="button" className="text-primary hover:underline" onClick={onManageTokens} data-testid="product-connect-tokens">
                create or manage API tokens
              </button>
              {' '}(also under your account menu › API Tokens).
            </dd>
          </dl>
          {connectionIsDevGuess && (
            <div className="mt-2 text-warning" data-testid="product-connect-dev-note">
              This page is on the Vite dev server, which proxies the API; the URLs above point at the
              backend itself — correct the host if yours runs elsewhere.
            </div>
          )}
        </div>

        <div>
          <div className="text-[10px] text-muted-foreground mb-1 uppercase tracking-wider">
            preview · {ACCESS_MODE_COPY[virtual ? 'VIRTUAL' : 'MATERIALIZED'].previewHeader}
          </div>
          {!virtual && !detail.snapshotId ? (
            <div className="text-muted-foreground">Not materialized yet — refresh to create the first snapshot.</div>
          ) : previewLoading ? (
            <div className="text-muted-foreground" role="status">loading preview…</div>
          ) : previewError ? (
            <div role="alert" className="border border-error/40 bg-error/10 rounded p-3 text-foreground space-y-2">
              <div>{previewError}</div>
              {virtual && (
                <button type="button" className="text-primary hover:underline" onClick={onRunPreview}>retry live preview</button>
              )}
            </div>
          ) : preview ? (
            // A VIRTUAL product's preview is a LIVE re-execution, so its row count is the
            // only place the browser can watch live denial-constraint enforcement happen
            // (V37's whole reason to exist). Testid'd so a spec reads this table and not
            // the products list above it.
            <>
              <div className="text-[10px] text-muted-foreground mb-1" data-testid="product-preview-scope">
                {preview.rowCount.toLocaleString()} row{preview.rowCount === 1 ? '' : 's'} shown
                {(preview.hasMore ?? preview.truncated) ? ` — the first ${PREVIEW_ROWS}; download for every row` : ''}
              </div>
              <div className="overflow-x-auto" data-testid="product-preview">
                <ResultTable result={preview} />
              </div>
            </>
          ) : virtual ? (
            // An explicit gesture (J6-F19): a click on a row used to run the whole OQL live,
            // possibly against production sources. Databricks and Snowsight preview a view only
            // when asked.
            <button
              type="button"
              data-testid="product-preview-run"
              className="text-[10px] px-2 py-1 border border-border rounded hover:border-primary hover:text-primary"
              onClick={onRunPreview}
            >
              ▶ run live preview — queries its sources now (first {PREVIEW_ROWS} rows)
            </button>
          ) : null}
        </div>

        {/* Getting the data out (J6-F4): this page is what is on screen; every row is the snapshot
            itself for a MATERIALIZED product (Aran's own gated file route — no lake URL leaves the
            server), or a live recompute for a VIRTUAL one. */}
        <div className="flex flex-wrap items-center gap-2" data-testid="product-download">
          <span className="text-[10px] text-muted-foreground uppercase tracking-wider">download</span>
          <button
            type="button"
            data-testid="product-download-page"
            className="text-[10px] px-2 py-1 border border-border rounded hover:border-primary hover:text-primary disabled:opacity-40 disabled:cursor-not-allowed"
            disabled={!preview}
            title={preview ? undefined : 'Load the preview first'}
            onClick={() => preview && downloadResultCsv(preview, `${tableNameOf(detail)}-preview.csv`)}
          >
            ⤓ this page · CSV
          </button>
          <button
            type="button"
            data-testid="product-download-all"
            className="text-[10px] px-2 py-1 border border-border rounded hover:border-primary hover:text-primary disabled:opacity-40 disabled:cursor-not-allowed"
            disabled={!allRows.available || downloading}
            title={allRows.available ? undefined : allRows.reason}
            onClick={onDownloadAll}
          >
            {downloading ? 'downloading…' : virtual ? '⤓ all rows · CSV (recomputed live)' : '⤓ all rows · parquet'}
          </button>
          {downloadError && <span role="alert" className="text-error">{downloadError}</span>}
        </div>
      </div>
    </section>
  );
};

interface DeleteProductDialogProps {
  product: DataProduct;
  deleting: boolean;
  error: string | null;
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * Delete, with its impact named (J6-F17): which readers stop resolving, and — for a product
 * anything can read — the name typed back, the GitHub/Databricks pattern for an irreversible
 * delete of something others depend on. Built on the one Dialog primitive (Escape, focus trap).
 */
export const DeleteProductDialog: React.FC<DeleteProductDialogProps> = ({
  product, deleting, error, onCancel, onConfirm,
}) => {
  const [typed, setTyped] = useState('');
  const typedRequired = deleteNeedsTypedName(product);
  const confirmed = !typedRequired || typed === product.name;
  return (
    <Dialog open onClose={onCancel} label={`Delete data product ${product.name}`} testId="product-delete-dialog" className="font-mono">
      <div className="h-9 flex items-center px-4 bg-muted/30 border-b border-border text-[12px] font-semibold text-foreground">
        Delete “{product.name}”?
      </div>
      <div className="p-4 space-y-3 text-[11px]">
        <div className="text-foreground">This cannot be undone. What stops working:</div>
        <ul className="list-disc pl-4 space-y-1 text-foreground" data-testid="product-delete-impact">
          {deleteImpact(product).map(line => <li key={line}>{line}</li>)}
        </ul>
        {typedRequired && (
          <div>
            <label htmlFor="product-delete-confirm-input" className="block text-muted-foreground mb-1">
              Type <span className="text-foreground">{product.name}</span> to confirm
            </label>
            <input
              id="product-delete-confirm-input"
              data-testid="product-delete-confirm-input"
              autoComplete="off"
              className="w-full bg-muted border border-border rounded px-2 py-1.5 text-foreground"
              value={typed}
              onChange={e => setTyped(e.target.value)}
            />
          </div>
        )}
        {error && <div role="alert" className="border border-error/40 bg-error/10 rounded p-2 text-foreground">{error}</div>}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn btn-ghost sm" onClick={onCancel}>cancel</button>
          <button
            type="button"
            data-testid="product-delete-confirm"
            className="btn sm text-error disabled:opacity-40 disabled:cursor-not-allowed"
            disabled={!confirmed || deleting}
            onClick={onConfirm}
          >
            {deleting ? 'deleting…' : 'delete product'}
          </button>
        </div>
      </div>
    </Dialog>
  );
};

interface ProductsViewProps {
  /**
   * Overrides the deployment capability. Only tests pass it: the app reads `features.cubes`
   * from the `/api/me` payload the auth store already holds (this view renders behind
   * `RequireAuth`, so that payload has arrived). The seam exists because a zustand hook reads
   * the store's INITIAL state under `renderToStaticMarkup`, which is how every component test
   * in this project renders.
   */
  cubesEnabled?: boolean;
}

export const ProductsView: React.FC<ProductsViewProps> = ({ cubesEnabled: cubesEnabledOverride }) => {
  // Selected field by field: `load()` fires several separate `set()` calls, and a
  // whole-store subscription re-renders this whole view on each one.
  const projects = useProjectsStore((s) => s.projects);
  const loadProjects = useProjectsStore((s) => s.load);
  // Global, not per-visit: arriving from the workbench right after saving a product
  // used to land on an empty "select a project" gate every time (redesign brief).
  const activeProjectId = useProjectsStore((s) => s.activeProjectId);
  const setActiveProject = useProjectsStore((s) => s.setActiveProject);
  const projectId = activeProjectId ?? '';
  const setProjectId = (id: string) => setActiveProject(id || null);
  const [products, setProducts] = useState<DataProduct[]>([]);
  const [versions, setVersions] = useState<SchemaVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Materialize jobs come from the jobs store, found by product id — not from page state, which
  // forgot a running refresh on navigation and dropped a failed card the moment it arrived (J6-F7).
  const storeJobs = useJobsStore(s => s.jobs);
  const dismissedJobs = useJobsStore(s => s.dismissed);
  const jobsLoaded = useJobsStore(s => s.loadedAt !== null);
  const latestFor = (p: DataProduct) => latestJob(Object.values(storeJobs), j => isProductJob(j, p.id));
  const jobFor = (p: DataProduct) => {
    const latest = latestFor(p);
    if (!latest) return null;
    if (isLive(latest)) return latest;
    return isFailure(latest) && !dismissedJobs[latest.id] ? latest : null;
  };
  // Products whose materialize was requested and whose job this page cannot see yet — the gap
  // between the click and the job reaching the store, in which a second click would start a
  // second run.
  const [starting, setStarting] = useState<Record<string, true>>({});

  // Detail drawer.
  const [detail, setDetail] = useState<DataProduct | null>(null);
  const [preview, setPreview] = useState<QueryRunResult | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [snapshotCopied, setSnapshotCopied] = useState<boolean | null>(null);
  const previewRequestRef = useRef<string | null>(null);
  const detailRef = useRef<HTMLElement>(null);
  // Name of the product whose open button takes focus once the detail has closed.
  const focusReturnRef = useRef<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [deleteFor, setDeleteFor] = useState<DataProduct | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();
  const linkedProductId = searchParams.get('product');

  // The MCP connect panel. Keyed off a product rather than a boolean so the dialog
  // always has one to name, and closes by being set back to null.
  const [agentsFor, setAgentsFor] = useState<DataProduct | null>(null);
  // The Connect block's "create or manage API tokens" opens the one tokens dialog (J6-F4).
  const [tokensOpen, setTokensOpen] = useState(false);

  // Cubes: the OLAP shaping layer over the same integrated classes these products are
  // made of, so it belongs on this page rather than in a section of its own — see CubesSection,
  // which owns all of it including the only call to api.listCubes.
  //
  // Whether it is here at all is the DEPLOYMENT's answer, not this bundle's: one build serves
  // every deployment, so `GET /api/me` publishes `features.cubes` (from `aran.cubes.enabled`,
  // off by default). With it off the cube routes answer 404 — CubeController is not even a
  // bean — so a wizard button and a panel here could produce nothing but errors.
  const publishedCubes = useAuthStore(s => cubesEnabledFrom(s.me));
  const cubesEnabled = cubesEnabledOverride ?? publishedCubes;
  const [shapeOpen, setShapeOpen] = useState(false);

  useEffect(() => {
    // Read live rather than off the render snapshot: this runs once on mount
    // (`loadProjects` is a stable zustand action) and must see the current list.
    if (useProjectsStore.getState().projects.length === 0) void loadProjects();
    api.listSchemaVersions().then(setVersions).catch(() => {});
  }, [loadProjects]);

  const versionNumberById = useMemo(() => {
    const m: Record<string, number> = {};
    versions.forEach(v => { m[v.id] = v.versionNumber; });
    return m;
  }, [versions]);

  const refresh = (pid = projectId) => {
    if (!pid) return;
    setLoading(true);
    api.listProducts(pid)
      .then(list => {
        setProducts(list);
        setError(null);
        setDetail(d => (d ? list.find(p => p.id === d.id) ?? null : null));
      })
      .catch((e: unknown) => setError(errorMessage(e, 'Failed to load data products.')))
      .finally(() => setLoading(false));
  };

  // A materialize that ends — started here, from a cube, or before a navigation — re-reads the
  // products, so the row's status, rows and "data as of" catch up without a reload.
  useJobsSettled(products.map(latestFor), () => refresh());

  useEffect(() => {
    // Clears the previous project's products before fetching the new one's — a
    // network fetch (refresh) has no render-time equivalent.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setProducts([]);
    setDetail(null);
    if (projectId) refresh(projectId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  async function materialize(product: DataProduct) {
    // Never offered for a VIRTUAL product (ProductRow hides the button, and the backend
    // rejects the call outright) — this guard is only for a stale reference reaching here
    // some other way; it costs nothing to keep it honest too.
    if (accessModeOf(product) === 'VIRTUAL') return;
    setStarting(prev => ({ ...prev, [product.id]: true }));
    try {
      const { jobId } = await api.materializeProduct(projectId, product.id);
      // The row re-reads when this job ends through useJobsSettled above, which hears a job this
      // tab started even when it first sees it finished. Awaited so the button stays disabled
      // until the job is in the store and holds it disabled itself.
      await useJobsStore.getState().started(jobId);
    } catch (e: unknown) {
      setError(errorMessage(e, 'Failed to start materialization.'));
    } finally {
      setStarting(prev => {
        const next = { ...prev };
        delete next[product.id];
        return next;
      });
    }
  }

  function openDetail(product: DataProduct) {
    setDetail(product);
    setPreview(null);
    setPreviewError(null);
    setPreviewLoading(false);
    setDownloadError(null);
    previewRequestRef.current = null;
    // Never a live run on open (J6-F19) — see previewsOnOpen.
    if (previewsOnOpen(product)) loadPreview(product);
  }

  function loadPreview(product: DataProduct) {
    // A response for a product no longer on screen is dropped rather than shown under the wrong
    // name — opening A then B used to let A's slower preview land in B's panel.
    const token = `${product.id}:${Date.now()}`;
    previewRequestRef.current = token;
    setPreview(null);
    setPreviewError(null);
    setPreviewLoading(true);
    api.previewProduct(projectId, product.id, PREVIEW_ROWS)
      .then(r => { if (previewRequestRef.current === token) setPreview(r); })
      .catch((e: unknown) => { if (previewRequestRef.current === token) setPreviewError(errorMessage(e, 'Preview failed.')); })
      .finally(() => { if (previewRequestRef.current === token) setPreviewLoading(false); });
  }

  function closeDetail(deleted = false) {
    previewRequestRef.current = null;
    // The opener (a row's name button) is on screen, but the Dialog-style "focus back to the
    // opener" cannot apply to a panel that is not a dialog — focus would fall to the page body.
    focusReturnRef.current = detail ? focusAfterClose(detail.id, products, deleted) : null;
    setDetail(null);
  }

  // Runs after the panel has unmounted: puts focus on the row the user came from, or on the next
  // row after a delete (its own row is on its way out of the list).
  useEffect(() => {
    const name = focusReturnRef.current;
    if (detail || name === null) return;
    focusReturnRef.current = null;
    const target = openButtonTestId(name);
    Array.from(document.querySelectorAll<HTMLElement>('[data-testid^="product-open-"]'))
      .find(el => el.dataset.testid === target)
      ?.focus();
  }, [detail]);

  // J6-F8: a product opened from a long list used to render below the fold with nothing moving;
  // the panel now scrolls into view and takes focus, so it is seen and announced.
  useEffect(() => {
    const el = detailRef.current;
    if (!el) return;
    el.scrollIntoView?.({ block: 'start', behavior: 'smooth' });
    el.focus({ preventScroll: true });
  }, [detail?.id]);

  // `/products?product=<id>` — where "open data products" lands after a save (J6-F5): the
  // product opens once its project's list has arrived, and the parameter is consumed.
  useEffect(() => {
    if (!linkedProductId || loading) return;
    const linked = products.find(p => p.id === linkedProductId);
    if (!linked) return;
    // Opening the detail may start a preview fetch — no render-time equivalent.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    openDetail(linked);
    setSearchParams(params => { params.delete('product'); return params; }, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linkedProductId, products, loading]);

  async function downloadAll(product: DataProduct) {
    setDownloading(true);
    setDownloadError(null);
    try {
      if (accessModeOf(product) === 'VIRTUAL') {
        // A VIRTUAL product IS its OQL over ACTIVE, so the uncapped export of that OQL is every
        // row it has right now (recomputed — the button says so).
        await api.exportOql(product.oql, 'csv');
      } else if (product.snapshotId) {
        await api.downloadProductSnapshot(product.id, product.snapshotId, `${tableNameOf(product)}.parquet`);
      }
    } catch (e: unknown) {
      setDownloadError(downloadFailureMessage(e));
    } finally {
      setDownloading(false);
    }
  }

  async function remove(product: DataProduct) {
    setDeleting(true);
    setDeleteError(null);
    try {
      await api.deleteProduct(projectId, product.id);
      setDeleteFor(null);
      closeDetail(true);
      refresh();
    } catch (e: unknown) {
      // Kept in the dialog, where the decision was made, rather than behind it.
      setDeleteError(errorMessage(e, 'Failed to delete the product.'));
    } finally {
      setDeleting(false);
    }
  }

  async function copySnapshotId(id: string) {
    // J5-F17: the real outcome, not "copied" regardless.
    setSnapshotCopied(await copyText(id));
    setTimeout(() => setSnapshotCopied(null), 2500);
  }

  const pinLabel = (p: DataProduct) => {
    const n = versionNumberById[p.schemaVersionId];
    return n !== undefined ? `v${n}` : p.schemaVersionId.slice(0, 8);
  };

  // Where an external client reaches this deployment, for the detail's Connect block. Read off the
  // page's location only while a detail is on screen, the one place it is shown.
  const clientBackend = detail ? suggestBackendUrl(window.location) : { url: '', isDevGuess: false };

  return (
    <div className="flex flex-col gap-4 p-4 max-w-6xl mx-auto w-full">
      <div className="bg-card border border-border rounded overflow-hidden">
        <CmdHeader
          command="Data products"
          info={CONCEPTS.dataProduct}
          right={
            <>
              <select
                id="tour-products-project"
                className="bg-muted border border-border rounded px-2 py-1 text-[11px] font-mono text-foreground"
                value={projectId}
                onChange={e => setProjectId(e.target.value)}
              >
                <option value="">— select a project —</option>
                {projects.map(p => (
                  <option key={p.id} value={p.id}>{p.name}</option>
                ))}
              </select>
              <span>{products.length} product{products.length === 1 ? '' : 's'}</span>
              {cubesEnabled && (
                <button
                  className="text-[10px] px-2 py-1 border border-border rounded hover:border-primary hover:text-primary disabled:opacity-40 disabled:cursor-not-allowed"
                  data-testid="shape-as-cube-open"
                  disabled={!projectId}
                  onClick={() => setShapeOpen(true)}
                  title="Shape an integrated class into a star — fact, measures, dimensions, hierarchies"
                >
                  ▦ shape as cube
                </button>
              )}
            </>
          }
        />
        {/* An error over a populated list (a failed refresh or delete) sits above it; over an
            empty one, ProductsEmptyState carries it, so a failure never reads as "none yet". */}
        {error && products.length > 0 && (
          <ErrorBanner className="m-4">{error}</ErrorBanner>
        )}
        {!projectId ? (
          <div className="p-4 text-[11px] font-mono text-muted-foreground">
            Select a project to list its data products.
          </div>
        ) : loading ? (
          <div className="p-4 text-[11px] font-mono text-muted-foreground">loading…</div>
        ) : error && products.length === 0 ? (
          // The failure is shown above; "No data products yet" beside it would claim the
          // project has none (J8-F2).
          null
        ) : products.length === 0 ? (
          <ProductsEmptyState error={error} onRetry={() => refresh()} />
        ) : (
          <table id="tour-products-list" className="w-full text-[11px] font-mono">
            <thead>
              <tr className="border-b border-border text-left text-muted-foreground">
                <th className="px-4 py-2 font-semibold">name</th>
                <th className="px-4 py-2 font-semibold">status</th>
                <th className="px-4 py-2 font-semibold">access</th>
                <th className="px-4 py-2 font-semibold" title="The schema version the product was defined against — provenance, not a pin: a refresh or a virtual read runs under the active one">defined on</th>
                <th className="px-4 py-2 font-semibold text-right">rows</th>
                <th className="px-4 py-2 font-semibold">data as of</th>
                <th className="px-4 py-2 font-semibold"></th>
              </tr>
            </thead>
            <tbody>
              {products.map(p => (
                <ProductRow
                  key={p.id}
                  product={p}
                  versionLabel={pinLabel(p)}
                  job={jobFor(p)}
                  busy={!!starting[p.id] || materializeBusy(p.status, latestFor(p), jobsLoaded)}
                  onOpenDetail={openDetail}
                  onUseWithAgents={setAgentsFor}
                  onMaterialize={materialize}
                  // A failed job keeps its card until dismissed or retried; a finished one
                  // re-reads the products (useJobsSettled above).
                  onJobDismiss={() => {
                    const shown = jobFor(p);
                    if (shown) useJobsStore.getState().dismiss(shown.id);
                  }}
                  onJobRetried={() => refresh()}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {cubesEnabled && (
        <CubesSection
          projectId={projectId}
          shapeOpen={shapeOpen}
          onShapeClose={() => setShapeOpen(false)}
          onProductsChanged={() => refresh()}
        />
      )}

      {detail && (
        <ProductDetail
          ref={detailRef}
          product={detail}
          pinLabel={pinLabel(detail)}
          versionNumberById={versionNumberById}
          connection={connectionDetails(clientBackend.url)}
          connectionIsDevGuess={clientBackend.isDevGuess}
          onManageTokens={() => setTokensOpen(true)}
          preview={preview}
          previewLoading={previewLoading}
          previewError={previewError}
          onRunPreview={() => loadPreview(detail)}
          snapshotCopied={snapshotCopied}
          onCopySnapshot={() => detail.snapshotId && copySnapshotId(detail.snapshotId)}
          downloading={downloading}
          downloadError={downloadError}
          onDownloadAll={() => void downloadAll(detail)}
          onUseWithAgents={() => setAgentsFor(detail)}
          onDelete={() => { setDeleteError(null); setDeleteFor(detail); }}
          onClose={() => closeDetail()}
        />
      )}

      {deleteFor && (
        <DeleteProductDialog
          product={deleteFor}
          deleting={deleting}
          error={deleteError}
          onCancel={() => setDeleteFor(null)}
          onConfirm={() => void remove(deleteFor)}
        />
      )}

      {agentsFor && (
        <UseWithAgentsDialog
          product={agentsFor}
          projectId={projectId}
          onClose={() => setAgentsFor(null)}
        />
      )}

      {tokensOpen && <ApiTokensDialog isOpen onClose={() => setTokensOpen(false)} />}
    </div>
  );
};

export default ProductsView;
