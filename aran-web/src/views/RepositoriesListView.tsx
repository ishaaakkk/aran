import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import useProjectsStore, { preferredProjectId, projectSource } from '@/stores/projectsStore';
import { api } from '@/services/api';
import type { SourceFailure } from '@/lib/fanOut';
import { emptyListClaim, loadRepositoryPage } from '@/lib/listLoads';
import { LoadFailures } from '@/components/LoadFailures';
import { ListEmptyBody } from '@/components/ListEmptyBody';
import { BlockedReason } from '@/components/BlockedReason';
import { DataTable, Column } from '@/components/DataTable';
import { CONCEPTS } from '@/lib/concepts';
import { RepositoryDetailPanel } from '@/components/RepositoryDetailPanel';
import {
  RegisterRepositoryDialog,
  RegisterRepositoryDialogHandle,
} from '@/components/RegisterRepositoryDialog';
import { OChip } from '@/components/OChip';
import { StatusDot } from '@/components/StatusDot';
import { Dialog } from '@/components/ui/Dialog';
import { Toast } from '@/components/ui/Toast';
import { toastLifetimeMs, type ToastMessage } from '@/lib/toast';
import { Search, Plus, X, Pencil, Trash2, Activity, Loader2, Database } from 'lucide-react';
import type { DataRepository } from '@/services/types';
import { errorMessage } from '@/lib/errors';
import { REPOSITORIES_EMPTY_STATE } from '@/lib/repositoryKinds';
import {
  checkFromError,
  checkFromRepository,
  checkFromResult,
  connectionStatusView,
  type RowCheck,
} from '@/lib/connectionStatus';
import { useConfirm } from '@/components/ui/useConfirm';

export const RepositoriesListView: React.FC = () => {
  // Selected field by field: `load()` fires several separate `set()` calls, and a
  // whole-store subscription re-renders this whole view on each one.
  const projects = useProjectsStore((s) => s.projects);
  const loadProjects = useProjectsStore((s) => s.load);
  const storeActiveProjectId = useProjectsStore((s) => s.activeProjectId);
  const registerDialogRef = useRef<RegisterRepositoryDialogHandle>(null);

  const [repositories, setRepositories] = useState<DataRepository[]>([]);
  // True from the first render: the Register button must not be live before the list it
  // reads exists (F9, J1-F16) — pressing it during the first load used to answer "Create a
  // project first" to a user with projects, and the e2e suite retried the click to cope.
  const [loading, setLoading] = useState(true);
  // Rendered, not discarded: a failed load used to fall through to the "No connections
  // found" onboarding screen, telling a user with real repositories they had none
  // (redesign brief, repositories HIGH #1). It could not actually be reached, though — the
  // project load swallowed its own failure and each project's failure went to the console
  // (J8-F2, §6.3 "reachability"). Both now land here or in `projectFailures`.
  const [error, setError] = useState<unknown>(null);
  const [projectFailures, setProjectFailures] = useState<SourceFailure[]>([]);
  const [noProjectNotice, setNoProjectNotice] = useState(false);

  const [search, setSearch] = useState('');
  const [filterType, setFilterType] = useState<string>('all');

  const [selectedRepo, setSelectedRepo] = useState<DataRepository | null>(null);
  const [isProjectSelectOpen, setIsProjectSelectOpen] = useState(false);
  const [activeProjectId, setActiveProjectId] = useState<string>('');

  const [testingRepoIds, setTestingRepoIds] = useState<ReadonlySet<string>>(new Set());
  const [deletingRepoId, setDeletingRepoId] = useState<string | null>(null);
  const [toast, setToast] = useState<ToastMessage | null>(null);
  /**
   * Each repository's last connection test: the one recorded server-side (V46), replaced by any
   * test run on this page. Nothing is probed on load (UI audit J2-F19) — the old page-load probe
   * ran every tap's discovery and a JDBC connection per database for a glance at the list, and,
   * posting the GET body back, dialed the '********' mask as the password (J2-F1).
   */
  const [checks, setChecks] = useState<Record<string, RowCheck | null>>({});
  const [datasetCounts, setDatasetCounts] = useState<Record<string, number>>({});
  const [confirm, confirmDialog] = useConfirm();

  /**
   * Test one stored repository with its stored credentials. Resolves with the row's new check;
   * a test that did not answer is "could not check", never a verdict about the source.
   */
  const testRepository = async (repo: DataRepository): Promise<RowCheck> => {
    setTestingRepoIds((prev) => new Set(prev).add(repo.id));
    let check: RowCheck;
    try {
      check = checkFromResult(await api.testStoredConnection(repo.projectId, repo.id));
    } catch (err: unknown) {
      check = checkFromError(err);
    }
    setChecks((prev) => ({ ...prev, [repo.id]: check }));
    setTestingRepoIds((prev) => {
      const next = new Set(prev);
      next.delete(repo.id);
      return next;
    });
    return check;
  };

  /** "Test all" — the explicit form of what page load used to do unasked. */
  const testAll = async () => {
    await Promise.all(
      repositories
        .filter((r) => connectionStatusView(r.type, null, false).testable)
        .map((r) => testRepository(r)),
    );
  };

  // J8-F8: a success hides itself; an error stays until dismissed or replaced (lib/toast.ts).
  // The timer is keyed so an older success's timeout cannot clear a newer message.
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (toastTimerRef.current) clearTimeout(toastTimerRef.current); }, []);
  const showToast = (message: string, type: 'success' | 'error' = 'success') => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    toastTimerRef.current = null;
    setToast({ message, type });
    const lifetime = toastLifetimeMs(type);
    if (lifetime !== null) toastTimerRef.current = setTimeout(() => setToast(null), lifetime);
  };

  const fetchAllRepositories = async () => {
    setLoading(true);
    setError(null);
    setProjectFailures([]);
    try {
      // The list is done — and Register live — as soon as the repositories are known; the
      // detail panel's dataset counts follow on their own (review of UA2: one slow source used to
      // hold the button disabled for a whole read deadline over a number it does not use).
      const counts = await loadRepositoryPage(
        projectSource(loadProjects),
        (projectId) => api.listRepositories(projectId),
        (projectId, repositoryId) => api.listDatasets(projectId, repositoryId),
        (result) => {
          setRepositories(result.repositories);
          setProjectFailures(result.failures);
          // Nothing is probed on load (J2-F19): rows show their stored last check (UA4).
          setChecks(Object.fromEntries(result.repositories.map((r) => [r.id, checkFromRepository(r)])));
          if (result.error != null) setError(result.error);
          setLoading(false);
        },
      );
      setDatasetCounts(counts);
    } catch (err: unknown) {
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchAllRepositories();
    // fetchAllRepositories is a plain function re-created every render, not memoized;
    // this intentionally runs once on mount rather than on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const typeOptions = [
    { label: 'All',         value: 'all' },
    { label: 'Relational',  value: 'RELATIONAL_DB' },
    { label: 'Object',      value: 'OBJECT_STORAGE' },
    { label: 'Tap',         value: 'SINGER_TAP' },
    { label: 'Iceberg',     value: 'ICEBERG_REST' },
    { label: 'File',        value: 'FILE_SYSTEM' },
  ];

  const filteredRepos = useMemo(() => {
    return repositories.filter((r) => {
      const matchesSearch =
        r.name.toLowerCase().includes(search.toLowerCase()) ||
        r.description.toLowerCase().includes(search.toLowerCase());

      const matchesType = filterType === 'all' || r.type === filterType;

      return matchesSearch && matchesType;
    });
  }, [repositories, search, filterType]);

  const rows = useMemo(() => {
    return filteredRepos.map((r, index) => {
      const proj = projects.find((p) => p.id === r.projectId);

      let endpoint = '';
      if (r.type === 'RELATIONAL_DB') {
        endpoint = `${r.engine.toLowerCase()}://${r.host}:${r.port}/${r.database}`;
      } else if (r.type === 'OBJECT_STORAGE') {
        endpoint = `${r.provider.toLowerCase()}://${r.bucketName}`;
      } else if (r.type === 'FILE_SYSTEM') {
        endpoint = 'uploads only';
      } else if (r.type === 'ICEBERG_REST') {
        endpoint = r.catalogUri;
      } else if (r.type === 'SINGER_TAP') {
        // A tap has no address to show: the endpoint is whatever the tap's own code dials.
        // Naming the tap is the only honest thing this column can say about it.
        endpoint = `tap://${r.tapId}`;
      }

      return {
        ...r,
        idx: String(index + 1).padStart(2, '0'),
        projectName: proj?.name || 'Unknown',
        projectColor: proj?.color || '#8B5CF6',
        endpointStr: endpoint,
        connStatus: connectionStatusView(r.type, checks[r.id] ?? null, testingRepoIds.has(r.id)),
      };
    });
  }, [filteredRepos, projects, checks, testingRepoIds]);

  const columns: Column[] = [
    { key: 'idx',          label: '',           width: '28px', num: true, mono: true },
    { key: 'name',         label: 'connection', mono: true },
    { key: 'type',         label: 'type',       width: '120px' },
    { key: 'projectName',  label: 'project',    width: '140px' },
    { key: 'endpointStr',  label: 'endpoint',   mono: true },
    { key: 'connStatus',   label: 'last test',  width: '150px' },
    { key: 'actions',      label: '',           width: '110px', align: 'right' },
  ];

  const handleRowClick = (row: { id: string }) => {
    const repo = repositories.find((r) => r.id === row.id) || null;
    setSelectedRepo(repo);
  };

  const registerBlockedReason = loading
    ? 'Loading your projects…'
    : error != null ? 'Unavailable until your repositories load' : null;

  const handleAddClick = () => {
    setSelectedRepo(null);
    if (projects.length === 0) {
      // Only reachable once the list has loaded (the button is disabled until then), so this
      // is a true "no project" — said in place with the way on, not in a toast that fades.
      setNoProjectNotice(true);
      return;
    }
    setActiveProjectId(preferredProjectId(projects, storeActiveProjectId));
    setIsProjectSelectOpen(true);
  };

  const handleProjectSelectConfirm = () => {
    setIsProjectSelectOpen(false);
    setTimeout(() => {
      registerDialogRef.current?.open();
    }, 100);
  };

  const handleEditClick = (e: React.MouseEvent, repo: DataRepository) => {
    e.stopPropagation();
    setSelectedRepo(null);
    setActiveProjectId(repo.projectId);
    setTimeout(() => {
      registerDialogRef.current?.open(repo);
    }, 100);
  };

  const testConnectionDirect = async (e: React.MouseEvent, repo: DataRepository) => {
    e.stopPropagation();
    const check = await testRepository(repo);
    if (check.state === 'ok') {
      showToast(check.message || `Connection to ${repo.name} succeeded.`, 'success');
    } else if (check.state === 'failed') {
      const detail = check.details ? ` — ${check.details}` : '';
      showToast(`${repo.name}: ${check.message}${detail}`, 'error');
    } else {
      showToast(`Could not check ${repo.name}: ${check.message}`, 'error');
    }
  };

  const deleteRepositoryDirect = async (e: React.MouseEvent, repo: DataRepository) => {
    e.stopPropagation();
    // J8-F14 / J2-F2: "Its datasets stop refreshing" was false — the V7 cascade deletes them, and
    // detachDataset rejects their alignments in every project first. Typed name: it is the widest
    // delete in the product and cannot be undone.
    const count = datasetCounts[repo.id];
    const ok = await confirm({
      title: `Delete the repository ${repo.name}?`,
      confirmLabel: 'Delete repository',
      testId: 'repository-delete',
      typeToConfirm: repo.name,
      consequences: [
        count === undefined
          ? 'Every dataset registered in it is deleted with it.'
          : `Its ${count} dataset${count === 1 ? ' is' : 's are'} deleted with it.`,
        'Every alignment that uses those datasets is rejected, in every project, and those integration models need a full rebuild.',
        'Datasets read live stop answering at once; uploaded ones keep serving the active schema\u2019s snapshot until the next rebuild is promoted.',
        'Stored credentials are deleted. Lake copies are kept.',
      ],
    });
    if (!ok) {
      return;
    }

    setDeletingRepoId(repo.id);
    try {
      await api.deleteRepository(repo.projectId, repo.id);
      showToast(`Repository ${repo.name} deleted.`);
      fetchAllRepositories();
    } catch (err: unknown) {
      showToast(errorMessage(err, 'Failed to delete connection.'), 'error');
    } finally {
      setDeletingRepoId(null);
    }
  };

  return (
    <div className="flex-grow flex flex-col p-6 gap-6 min-h-0 relative select-none">
      {confirmDialog}
      {/* Toast */}
      <Toast toast={toast} onDismiss={() => setToast(null)} testId="repos-toast" />

      {/* Toolbar */}
      <div className="flex flex-col md:flex-row items-stretch md:items-center justify-between gap-4 shrink-0">
        <div className="relative flex-1 max-w-sm">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            placeholder="Search repositories…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full h-8 rounded-md border border-border bg-card pl-9 pr-16 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary focus:border-primary transition-all"
          />
          <span className="absolute right-3 top-1/2 -translate-y-1/2 font-mono text-[10px] text-muted-foreground/70 tabular-nums select-none">
            {rows.length}/{repositories.length}
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-1.5 font-mono text-[10.5px]">
          {typeOptions.map((opt) => (
            <button
              key={opt.value}
              onClick={() => setFilterType(opt.value)}
              className={`h-8 px-3 rounded-md uppercase tracking-wider font-semibold border transition-all ${
                filterType === opt.value
                  ? 'border-primary/50 bg-primary/10 text-primary'
                  : 'border-border/60 bg-muted/10 text-muted-foreground hover:text-foreground'
              }`}
            >
              {opt.label}
            </button>
          ))}

          {/* Why Register is disabled, tied to the button and announced when it changes. */}
          <BlockedReason id="repos-register-reason" reason={registerBlockedReason} className="text-[11px] normal-case tracking-normal text-muted-foreground" />
          <button
            type="button"
            data-testid="repos-test-all"
            onClick={() => void testAll()}
            disabled={testingRepoIds.size > 0 || repositories.length === 0}
            title="Test every repository's stored connection now. Nothing is tested on page load."
            className="ml-1 inline-flex items-center gap-1.5 h-8 px-3 rounded-md border border-border/60 bg-muted/10 font-semibold text-muted-foreground hover:text-foreground uppercase tracking-wider transition-all disabled:opacity-50"
          >
            {testingRepoIds.size > 0 ? <Loader2 size={12} className="animate-spin" /> : <Activity size={12} />}
            {testingRepoIds.size > 0 ? `Testing ${testingRepoIds.size}…` : 'Test all'}
          </button>

          <button
            type="button"
            id="tour-repos-new"
            onClick={handleAddClick}
            data-testid="repos-new-button"
            disabled={registerBlockedReason !== null}
            aria-describedby={registerBlockedReason ? 'repos-register-reason' : undefined}
            className="ml-1 inline-flex items-center gap-1.5 h-8 px-4 rounded-md bg-primary font-semibold text-primary-foreground hover:bg-primary/90 transition-colors uppercase tracking-wider disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? <Loader2 size={12} className="animate-spin" aria-hidden="true" /> : <Plus size={12} aria-hidden="true" />}
            Register repository
          </button>
        </div>
      </div>

      {noProjectNotice && projects.length === 0 && (
        <div
          role="status"
          data-testid="repos-no-project"
          className="flex flex-wrap items-center gap-3 rounded-md border border-border bg-muted/20 px-4 py-2.5 text-xs shrink-0"
        >
          <span className="text-foreground">A repository belongs to a project, and you have none yet.</span>
          <Link to="/projects" state={{ create: true }} className="font-semibold text-primary underline underline-offset-2">
            Create a project
          </Link>
        </div>
      )}

      {!error && (
        <LoadFailures
          failures={projectFailures}
          total={projects.length}
          sourceNoun="project"
          onRetry={() => void fetchAllRepositories()}
          retrying={loading}
          testId="repos-partial-error"
        />
      )}

      {/* Table */}
      <div className="flex-grow overflow-y-auto">
        <DataTable
          command="Repositories"
          info={CONCEPTS.repository}
          columns={columns}
          rows={rows}
          onRowClick={handleRowClick}
          getRowTestId={(row) => `repos-row-${row.name}`}
          emptyMessage={
            // Broken — wholly or in part — must never masquerade as empty: only a complete, empty
            // load gets the onboarding screen and its Register.
            <ListEmptyBody
              claim={emptyListClaim({ loading, error, failures: projectFailures, loaded: repositories.length })}
              noun="repositories"
              loadingLabel="Aggregating sources..."
              errorTitle="Couldn't load your repositories"
              error={error}
              onRetry={() => void fetchAllRepositories()}
              errorTestId="repos-load-error"
              incompleteTestId="repos-incomplete-empty"
            >
              <div className="flex flex-col items-center gap-4 py-8">
                <div className="w-16 h-16 rounded-full bg-muted/50 border border-border flex items-center justify-center mb-2">
                  <Database className="w-8 h-8 text-muted-foreground/60" />
                </div>
                <div className="text-center space-y-1.5">
                  <h3 className="text-base font-semibold text-foreground tracking-tight">No repositories yet</h3>
                  <p className="text-xs text-muted-foreground max-w-sm">
                    {REPOSITORIES_EMPTY_STATE}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={handleAddClick}
                  className="mt-4 inline-flex items-center gap-2 h-9 px-5 rounded-md bg-primary font-semibold text-primary-foreground hover:bg-primary/90 transition-colors uppercase tracking-wider text-[11px] shadow-sm"
                >
                  <Plus size={14} />
                  Register repository
                </button>
              </div>
            </ListEmptyBody>
          }
          renderCell={{
            type: (row) => <OChip label={row.type.replace('_', ' ')} type="class" sm />,
            projectName: (row) => (
              <span className="font-mono text-xs text-foreground/90 inline-flex items-center gap-1.5 max-w-[120px]">
                <span
                  className="w-1.5 h-1.5 rounded-full shrink-0"
                  style={{ backgroundColor: row.projectColor }}
                />
                <span className="truncate">{row.projectName}</span>
              </span>
            ),
            connStatus: (row) => (
              // The title carries the whole story — what was checked, what it said, when.
              <span
                className="inline-flex items-center gap-2"
                title={row.connStatus.title}
                data-testid={`repos-status-${row.name}`}
              >
                <StatusDot
                  variant={row.connStatus.variant}
                  label={row.connStatus.label}
                  blink={row.connStatus.variant === 'accent'}
                />
                {row.connStatus.when && (
                  <span className="font-mono text-[10px] text-muted-foreground whitespace-nowrap">
                    {row.connStatus.when}
                  </span>
                )}
                <span className="sr-only">{row.connStatus.title}</span>
              </span>
            ),
            actions: (row) => (
              <div className="flex justify-end gap-1">
                {row.connStatus.testable && (
                  <button
                    type="button"
                    title={`Test ${row.name}'s stored connection`}
                    aria-label={`Test ${row.name}'s stored connection`}
                    data-testid={`repos-test-${row.name}`}
                    disabled={testingRepoIds.has(row.id)}
                    onClick={(e) => testConnectionDirect(e, row)}
                    className="w-[26px] h-[26px] rounded-md border border-border/80 bg-muted/10 text-primary hover:bg-primary/10 transition-colors disabled:opacity-40 flex items-center justify-center"
                  >
                    {testingRepoIds.has(row.id)
                      ? <Loader2 size={12} className="animate-spin" />
                      : <Activity size={12} />}
                  </button>
                )}
                <button
                  type="button"
                  title="Edit Connection"
                  disabled={deletingRepoId !== null || testingRepoIds.has(row.id)}
                  onClick={(e) => handleEditClick(e, row)}
                  className="w-[26px] h-[26px] rounded-md border border-border/80 bg-muted/10 text-muted-foreground hover:text-foreground transition-colors flex items-center justify-center"
                >
                  <Pencil size={12} />
                </button>
                <button
                  type="button"
                  title="Delete Connection"
                  disabled={deletingRepoId !== null}
                  onClick={(e) => deleteRepositoryDirect(e, row)}
                  className="w-[26px] h-[26px] rounded-md border border-border/80 bg-muted/10 text-rose-400 hover:bg-rose-500/10 transition-colors disabled:opacity-40 flex items-center justify-center"
                >
                  {deletingRepoId === row.id
                    ? <Loader2 size={12} className="animate-spin" />
                    : <Trash2 size={12} />}
                </button>
              </div>
            ),
          }}
        />
      </div>

      {/* Detail panel */}
      {/* J2-F25: the shared Dialog as a sheet, below the top bar — a click on Copy inside no
          longer closes it, and Escape does. */}
      {selectedRepo && (
        <Dialog
          open
          variant="sheet"
          onClose={() => setSelectedRepo(null)}
          label={`Repository ${selectedRepo.name}`}
        >
          <RepositoryDetailPanel
            repository={selectedRepo}
            project={projects.find((p) => p.id === selectedRepo.projectId)}
            datasetCount={datasetCounts[selectedRepo.id]}
            connection={connectionStatusView(
              selectedRepo.type, checks[selectedRepo.id] ?? null, testingRepoIds.has(selectedRepo.id))}
            onClose={() => setSelectedRepo(null)}
            onEdit={(r) => {
              setSelectedRepo(null);
              setActiveProjectId(r.projectId);
              setTimeout(() => {
                registerDialogRef.current?.open(r);
              }, 100);
            }}
            onDelete={(r) => {
              setSelectedRepo(null);
              const pseudoEvent = { stopPropagation: () => {} } as React.MouseEvent;
              deleteRepositoryDirect(pseudoEvent, r);
            }}
          />
        </Dialog>
      )}

      {/* Project selector modal */}
      {isProjectSelectOpen && (
        <Dialog
          open
          onClose={() => setIsProjectSelectOpen(false)}
          label="Select scope project"
          maxWidth="max-w-sm"
        >
            <div className="flex items-center justify-between border-b border-border bg-muted/20 px-5 py-3">
              <h2 className="font-mono text-xs uppercase tracking-widest text-foreground font-semibold">
                select scope project
              </h2>
              <button
                type="button"
                onClick={() => setIsProjectSelectOpen(false)}
                className="text-muted-foreground hover:text-foreground transition-colors"
              >
                <X size={16} />
              </button>
            </div>
            <div className="px-5 py-4 space-y-2">
              <p className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                Select target project
              </p>
              <div className="space-y-1.5 max-h-52 overflow-y-auto">
                {projects.map((p) => (
                  <button
                    key={p.id}
                    type="button"
                    onClick={() => setActiveProjectId(p.id)}
                    data-testid={`repos-project-${p.name}`}
                    className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-md border text-left transition-all ${
                      activeProjectId === p.id
                        ? 'border-primary/50 bg-primary/10 text-primary'
                        : 'border-border/60 bg-muted/5 text-foreground hover:bg-muted/20'
                    }`}
                  >
                    <span
                      className="w-2.5 h-2.5 rounded-full shrink-0"
                      style={{ backgroundColor: p.color || '#8B5CF6' }}
                    />
                    <span className="font-mono text-xs font-medium truncate flex-1">{p.name}</span>
                    {p.description && (
                      <span className="text-[10px] text-muted-foreground truncate max-w-[100px]">
                        {p.description}
                      </span>
                    )}
                  </button>
                ))}
              </div>
            </div>
            <div className="flex items-center justify-end gap-2 border-t border-border px-5 py-3 bg-muted/5">
              <button
                type="button"
                onClick={() => setIsProjectSelectOpen(false)}
                className="rounded-md border border-border px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-muted-foreground hover:text-foreground transition-colors"
              >
                cancel
              </button>
              <button
                type="button"
                onClick={handleProjectSelectConfirm}
                data-testid="repos-project-confirm"
                className="rounded-md bg-primary px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-primary-foreground hover:bg-primary/90 transition-colors"
              >
                continue
              </button>
            </div>
        </Dialog>
      )}

      {/* Register dialog */}
      <RegisterRepositoryDialog
        ref={registerDialogRef}
        projectId={activeProjectId}
        onSave={(saved) => {
          showToast('Repository saved.');
          // Save, then test — the saved configuration, once, because a person just asked for it
          // (Fivetran's "Save & Test"). A save forgets the previous check server-side, so without
          // this the row would read "Not tested" right after the dialog said it connected.
          void fetchAllRepositories().then(() => {
            if (connectionStatusView(saved.type, null, false).testable) void testRepository(saved);
          });
        }}
      />
    </div>
  );
};

export default RepositoriesListView;
