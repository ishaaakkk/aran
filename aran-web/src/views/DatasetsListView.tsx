import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useStore } from 'zustand';
import useProjectsStore, { preferredProjectId, projectSource } from '@/stores/projectsStore';
import { api } from '@/services/api';
import { emptyListClaim, loadDatasetList, projectRepositoryClaim } from '@/lib/listLoads';
import type { SourceFailure } from '@/lib/fanOut';
import { ErrorNotice } from '@/components/ErrorNotice';
import { LoadFailures } from '@/components/LoadFailures';
import { ListEmptyBody } from '@/components/ListEmptyBody';
import { BlockedReason } from '@/components/BlockedReason';
import { DataTable, Column } from '@/components/DataTable';
import { CONCEPTS } from '@/lib/concepts';
import { RegisterDatasetDialog, RegisterDatasetDialogHandle } from '@/components/RegisterDatasetDialog';
import { LocalGraphModal } from '@/components/LocalGraphModal';
import { SchemaDriftPanel } from '@/components/SchemaDriftPanel';
import { OChip } from '@/components/OChip';
import { JobCard } from '@/components/JobCard';
import { Dialog } from '@/components/ui/Dialog';
import { Toast } from '@/components/ui/Toast';
import { toastLifetimeMs, type ToastMessage } from '@/lib/toast';
import { Search, Plus, X, Pencil, Trash2, Eye, Table, FileText, Loader2, ShieldAlert, Sparkles, Network } from 'lucide-react';
import type { DatasetPreview, DatasetRefreshPolicy, DataRepository, DriftReport, GraphNode, GraphEdge, Job, ModelConstraint } from '@/services/types';
import { decide, repairableRenames, statusAfterDriftCheck } from '@/lib/driftModel';
import { errorMessage } from '@/lib/errors';
import { constraintToggleToast } from '@/lib/impactCopy';
import { useConfirm } from '@/components/ui/useConfirm';
import { readsLive } from '@/lib/datasetAccess';
import { createModelSession } from '@/components/datasets/modelSession';
import type { SchemaGraphInstance } from '@/lib/schema-graph';
import { settleFor, wrapperSeedSelection, type AggregatedDataset } from '@/components/datasets/types';
import { AccessBadge, DatasetOverviewTab } from '@/components/datasets/DatasetOverviewTab';
import { DatasetPreviewTab } from '@/components/datasets/DatasetPreviewTab';
import { DatasetProfileTab } from '@/components/datasets/DatasetProfileTab';
import { DatasetConstraintsTab } from '@/components/datasets/DatasetConstraintsTab';
import { DatasetWrapperTab } from '@/components/datasets/DatasetWrapperTab';
import { useJobsStore } from '@/stores/jobsStore';
import { usePendingDatasetWatch } from '@/hooks/usePendingDatasetWatch';
import { DatasetFailureNote } from '@/components/datasets/DatasetFailureNote';
import { useJobsSettled } from '@/hooks/useJobEvents';
import {
  datasetBusyLabel,
  formatDuration,
  isDatasetJob,
  isFailure,
  isLive,
  isProfilingJob,
  jobDurationMs,
  latestJob,
  resolveDatasetStatus,
  retryFor,
} from '@/lib/jobs';
import { profilingRequest, runErrorsOf, type ProfileLoad } from '@/components/datasets/profileModel';
import { sourceSchemaColumns } from '@/components/datasets/sourceSchema';
import type { SourceSchemaState } from '@/components/datasets/DatasetOverviewTab';

export type { AggregatedDataset } from '@/components/datasets/types';

export const DatasetsListView: React.FC = () => {
  const navigate = useNavigate();
  const { datasetId: routeDatasetId } = useParams<{ datasetId: string }>();
  // Selected field by field: `load()` fires several separate `set()` calls, and a
  // whole-store subscription re-renders this whole view on each one.
  const projects = useProjectsStore((s) => s.projects);
  const loadProjects = useProjectsStore((s) => s.load);
  const storeActiveProjectId = useProjectsStore((s) => s.activeProjectId);
  const setStoreActiveProject = useProjectsStore((s) => s.setActiveProject);

  const registerDialogRef = useRef<RegisterDatasetDialogHandle>(null);

  const [datasets, setDatasets] = useState<AggregatedDataset[]>([]);
  const [repositories, setRepositories] = useState<DataRepository[]>([]);
  // True from the first render: Register must not be live before the projects and
  // repositories its wizard lists have loaded (F9, J1-F16, J2-F31).
  const [loading, setLoading] = useState(true);
  // A failed load is rendered, never shown as "No datasets found" (J2-F22, FRONTEND-6) — this
  // used to be `[, setError]`, set and never read. Sources that failed while others answered
  // are named above the rows instead (J8-F2).
  const [error, setError] = useState<unknown>(null);
  const [loadFailures, setLoadFailures] = useState<SourceFailure[]>([]);
  // Projects whose repository list failed: the wizard must not call them empty (review of UA2).
  const [unreadProjectIds, setUnreadProjectIds] = useState<string[]>([]);
  const [sourceCount, setSourceCount] = useState(0);
  const [registerNotice, setRegisterNotice] = useState<'no-project' | 'no-repository' | 'repositories-unread' | null>(null);
  const [isDark, setIsDark] = useState(() => document.documentElement.classList.contains('dark'));

  useEffect(() => {
    const observer = new MutationObserver(() => {
      setIsDark(document.documentElement.classList.contains('dark'));
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, []);

  const [search, setSearch] = useState('');
  const [filterType, setFilterType] = useState<string>('all');

  const [isRegisterSelectOpen, setIsRegisterSelectOpen] = useState(false);
  const [registerStep, setRegisterStep] = useState<1 | 2>(1);
  const [activeProjectId, setActiveProjectId] = useState<string>('');
  const [activeRepoId, setActiveRepoId] = useState<string>('');

  const [deletingDatasetId, setDeletingDatasetId] = useState<string | null>(null);
  const [confirm, confirmDialog] = useConfirm();
  const [, setEditingDataset] = useState<AggregatedDataset | null>(null);
  const [toast, setToast] = useState<ToastMessage | null>(null);

  // Local Graph (bootstrapping) states
  const [bootstrapLoading, setBootstrapLoading] = useState<string | null>(null); // datasetId being bootstrapped
  /**
   * datasetId → the bootstrap job watching over it, for the jobs this page started.
   * A reload loses the mapping (jobs are not indexed by dataset server-side), which
   * is why a PENDING row without one falls back to polling itself.
   */
  const [bootstrapJobs, setBootstrapJobs] = useState<Record<string, string>>({});

  /**
   * Schema drift review. A re-bootstrap now asks what it would break before it writes; when the
   * answer is "something already compiled into the active schema", this holds the report and the
   * modal stays open until the user decides. Null means no review is in flight.
   */
  const [driftReview, setDriftReview] = useState<{ dataset: AggregatedDataset; report: DriftReport } | null>(null);
  const [driftChecking, setDriftChecking] = useState(false);
  /**
   * The dataset a bootstrap job was started for. The job overlay used to key off the graph modal's
   * dataset, so a re-read started from a drift review opened on the list (J2-F16) ran with no
   * progress shown and no completion handled.
   */
  const [bootstrapTarget, setBootstrapTarget] = useState<AggregatedDataset | null>(null);
  const [driftBusy, setDriftBusy] = useState(false);
  const [selectedRenames, setSelectedRenames] = useState<string[]>([]);
  const [graphDataset, setGraphDataset] = useState<AggregatedDataset | null>(null);
  const [graphData, setGraphData] = useState<{ nodes: GraphNode[]; edges: GraphEdge[]; tripleCount: number } | null>(null);
  const [graphLoading, setGraphLoading] = useState(false);
  const [graphSelectedNode, setGraphSelectedNode] = useState<GraphNode | null>(null);
  const graphInstanceRef = useRef<SchemaGraphInstance | null>(null);

  const [selectedDataset, setSelectedDataset] = useState<AggregatedDataset | null>(null);
  const [activeDetailTab, setActiveDetailTab] = useState<'metadata' | 'preview' | 'profile' | 'constraints' | 'wrapper'>('metadata');
  const [detailPreviewData, setDetailPreviewData] = useState<DatasetPreview | null>(null);
  const [detailPreviewLoading, setDetailPreviewLoading] = useState(false);
  const [detailPreviewError, setDetailPreviewError] = useState<string | null>(null);
  /** The open dataset's stored column profiles (J3-F1), keyed by the dataset they are for (J3-F8). */
  const [profileLoad, setProfileLoad] = useState<ProfileLoad | null>(null);
  /**
   * Profiling runs in flight, and the per-column failures each last reported, by dataset id — so a
   * run started on one dataset never shows as running, or lands its result, on another (J3-F8).
   */
  const [profilingRuns, setProfilingRuns] = useState<Record<string, boolean>>({});
  const [profileRunErrors, setProfileRunErrors] = useState<Record<string, Record<string, string>>>({});

  // A preview answers for the selection it was run on (dataset + class), and is shown only while
  // that selection is on screen — a preview of acm that answered after dblp was opened is not
  // dblp's (third review of UA2).
  // Keyed by the selection AND the SQL it ran (fourth review of UA2): a re-seeded editor must not sit
  // beside the rows of an edit that was discarded.
  const [wrapperPreview, setWrapperPreview] = useState<{ selection: string; sql: string; data: DatasetPreview } | null>(null);
  const [previewingSelection, setPreviewingSelection] = useState<string | null>(null);

  // Each toast lives four seconds from when it is shown; a newer one restarts the clock, and the
  // cleanup clears it on unmount.
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // J8-F8: a success hides itself; an error stays until dismissed or replaced (lib/toast.ts).
  // Stable, so the model session below can hold it for the view's lifetime.
  const showToast = React.useCallback((message: string, type: 'success' | 'error' = 'success') => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    toastTimerRef.current = null;
    setToast({ message, type });
    const lifetime = toastLifetimeMs(type);
    if (lifetime !== null) toastTimerRef.current = setTimeout(() => setToast(null), lifetime);
  }, []);

  // ── Integration-model state for the wrapper & constraints tabs (M6) ─────────
  // The wrapper users edit lives on model_source and reaches the engine at compile.
  // (There was a third, display-only Dataset.wrapper; it was deleted in V27 because nothing
  // read it.) Constraints live on model_constraint, keyed by the
  // integrated class IRI the catalog exposes (so they can filter its int_ view).
  //
  // Held in a store of its own (components/datasets/modelSession.ts) rather than in this view's
  // state: a wrapper save or an enrol answers long after it is made, and it — and every re-read
  // it triggers — must land on the dataset it was made on, never on the one opened since (third
  // review of UA2, J4-F11). The store is where that rule lives and where it is tested.
  const [session] = useState(() => createModelSession({ api, notify: showToast }));
  const modelSources = useStore(session, (s) => s.modelSources);
  const modelConstraints = useStore(session, (s) => s.modelConstraints);
  // V42: constraints the server flagged as orphaned (their integrated class is gone from the
  // active catalog). Held apart from `modelConstraints` because that list is filtered by the
  // selected class and an orphan has no class to be selected under.
  const orphanedConstraints = useStore(session, (s) => s.orphanedConstraints);
  const datasetClasses = useStore(session, (s) => s.datasetClasses);
  const modelLoading = useStore(session, (s) => s.modelLoading);
  // A model that could not be read is not a dataset with no sources: the Wrapper tab used to offer
  // "Enroll in model" over a failed load (J8-F2).
  const modelError = useStore(session, (s) => s.modelError);
  const catalogError = useStore(session, (s) => s.catalogError);
  const selectedWrapperClass = useStore(session, (s) => s.selectedWrapperClass);
  const selectedConstraintClass = useStore(session, (s) => s.selectedConstraintClass);
  const wrapperText = useStore(session, (s) => s.wrapperText);
  const seededSelection = useStore(session, (s) => s.seededSelection);
  // J4-F11's wrapper half: in-flight state keyed by what it is FOR — a save by dataset and class,
  // an enrol by dataset — so another dataset's Save is neither busy nor refused meanwhile.
  const savingWrappers = useStore(session, (s) => s.savingWrappers);
  const saveChecks = useStore(session, (s) => s.saveChecks);
  const checkingSaves = useStore(session, (s) => s.checkingSaves);
  const enrollingDatasets = useStore(session, (s) => s.enrolling);
  const enrolChecks = useStore(session, (s) => s.enrolChecks);
  const checkingEnrols = useStore(session, (s) => s.checkingEnrols);
  const [constraintExpr, setConstraintExpr] = useState<string>('');
  const [savingConstraint, setSavingConstraint] = useState(false);
  // Constraint discovery (Phase 3B): LIMA runs on the job substrate like compile, so these only
  // track submitted jobs — `handleDiscoverJobDone` below reloads the model when one lands. Keyed
  // by classIri, same precedent as `bootstrapJobs` below (keyed by datasetId): discovery is a
  // per-class action, so switching the class dropdown mid-job must not show class A's card,
  // disabled button, or completion toast while class B is the one on screen.
  const [discoveringClasses, setDiscoveringClasses] = useState<Record<string, boolean>>({});
  const [discoverJobs, setDiscoverJobs] = useState<Record<string, string>>({});
  // V43: classIri → the server's own sentence about the last discovery run on that class, kept
  // only when the run persisted nothing. Discovery over a real dependency can complete having
  // found something genuine that this product cannot yet express as a single-row constraint, and
  // a toast that has already faded is not an answer to "I clicked discover and nothing appeared".
  const [discoveryNotes, setDiscoveryNotes] = useState<Record<string, string>>({});

  // Put the opened dataset on the session and read its project's model + active catalog, so the
  // wrapper tab can list its enrolled classes and the constraints tab its integrated classes.
  // Both are re-read after every write, mirroring useIntegrationModel. The editor is seeded by the
  // session when — and only when — the selection moves (V49; see `wrapperSeedSelection`).
  useEffect(() => {
    // Clears a stale preview ahead of the network fetch below, which has no
    // render-time equivalent.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setWrapperPreview(null);
    // A different dataset (or the panel closing) invalidates any in-flight discovery UI carried
    // over from the last one — same hard-reset-on-dataset-change rule as the preview clear above,
    // not a class-by-class merge (a classIri shared by two datasets that both contribute to it is
    // an edge case not worth tracking through a context switch this size).
    setDiscoveringClasses({});
    setDiscoverJobs({});
    void session.getState().open(selectedDataset);
  }, [selectedDataset, session]);

  const fetchAllDatasetsAndRepos = async () => {
    setLoading(true);
    setError(null);
    setLoadFailures([]);
    try {
      const result = await loadDatasetList(
        projectSource(loadProjects),
        (projectId) => api.listRepositories(projectId),
        (projectId, repositoryId) => api.listDatasets(projectId, repositoryId),
      );
      setRepositories(result.repositories);
      setUnreadProjectIds(result.unreadProjectIds);
      setDatasets(result.datasets.map((ds, index) => ({ ...ds, idx: String(index + 1).padStart(2, '0') })));
      setLoadFailures(result.failures);
      setSourceCount(result.sources);
      if (result.error) setError(result.error);
    } catch (err: unknown) {
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchAllDatasetsAndRepos();
    // fetchAllDatasetsAndRepos is a plain function re-created every render, not
    // memoized; this intentionally runs once on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * The open dataset's refresh policy, or null when it has no upstream to refresh from.
   *
   * Loaded per open panel rather than with the dataset list, because most datasets have no policy
   * and the list view shows nothing about freshness — fetching one per repository on mount would
   * be a request whose answer is usually empty and always unread.
   *
   * Carries the dataset id the answer is FOR, not just the answer, for two reasons: a response
   * for a dataset the user has already navigated away from must not be rendered against the one
   * now open (requests can land out of order), and "still loading" must not render as "no
   * upstream" — both would be a bare null, and the panel treats null as a statement about the
   * dataset rather than about the request.
   */
  const [refreshPolicyState, setRefreshPolicyState] =
    useState<{ datasetId: string; policy: DatasetRefreshPolicy | null } | null>(null);

  // Only the newest read or write of the policy may land: since J2-F11 a settling job re-reads
  // the policy, and such a read issued just before a schedule change was saved used to answer
  // after it and put the old policy back (a6-refresh, Tier A merge).
  const refreshPolicySeqRef = useRef(0);
  const loadRefreshPolicy = React.useCallback(async (row: { id: string; projectId: string; repositoryId: string } | null) => {
    if (!row) return;
    const seq = ++refreshPolicySeqRef.current;
    try {
      const all = await api.listRefreshPolicies(row.projectId, row.repositoryId);
      if (seq !== refreshPolicySeqRef.current) return;
      setRefreshPolicyState({ datasetId: row.id, policy: all.find((p) => p.datasetId === row.id) ?? null });
    } catch (err) {
      // Left unset rather than set to null: a failed request is not evidence that the dataset has
      // no upstream, and claiming it is would tell the user their data cannot be refreshed because
      // the backend happened to be unreachable.
      console.error('Failed to load refresh policy:', err);
    }
  }, []);

  /**
   * The open dataset's bootstrapped source schema, for the Overview (J2-F13). Keyed by the dataset
   * it is FOR, for the same two reasons as the refresh policy above.
   */
  const [sourceSchema, setSourceSchema] = useState<SourceSchemaState | null>(null);

  const loadSourceSchema = React.useCallback(async (row: { id: string }) => {
    setSourceSchema({ datasetId: row.id, status: 'loading' });
    let next: SourceSchemaState;
    try {
      next = { datasetId: row.id, status: 'ready', columns: sourceSchemaColumns(await api.fetchSourceGraph(row.id)) };
    } catch (err: unknown) {
      next = { datasetId: row.id, status: 'error', message: errorMessage(err, 'the request failed') };
    }
    setSourceSchema(settleFor<SourceSchemaState>(next));
  }, []);

  /**
   * Read the stored profiles for a dataset. `replace` is for opening it: the new dataset's state
   * takes over whatever was shown. Without it (a run completing), the answer lands only while the
   * state still describes that dataset — the user may have moved on (J3-F8).
   */
  const loadProfiles = async (row: { id: string; projectId: string; repositoryId: string }, replace: boolean) => {
    const loading: ProfileLoad = { datasetId: row.id, status: 'loading' };
    setProfileLoad(replace ? loading : settleFor<ProfileLoad>(loading));
    let next: ProfileLoad;
    try {
      const res = await api.listDatasetProfiles(row.projectId, row.repositoryId, row.id);
      next = { datasetId: row.id, status: 'ready', groups: res.groups };
    } catch (err: unknown) {
      next = { datasetId: row.id, status: 'error', message: errorMessage(err, 'the request failed') };
    }
    setProfileLoad(settleFor<ProfileLoad>(next));
  };

  /** Re-read one dataset from the server and fold it into the list (and the open panel). */
  const refreshDataset = async (row: { id: string; projectId: string; repositoryId: string }) => {
    try {
      const list = await api.listDatasets(row.projectId, row.repositoryId);
      const fresh = list.find((d) => d.id === row.id);
      if (!fresh) return;
      setDatasets((prev) => prev.map((d) => (d.id === fresh.id ? { ...d, ...fresh } : d)));
      setSelectedDataset((prev) => (prev && prev.id === fresh.id ? { ...prev, ...fresh } : prev));
      if (selectedDataset?.id === row.id) {
        void loadRefreshPolicy(row);
        // A bootstrap that just landed has written the schema the Overview shows.
        if (fresh.bootstrapStatus !== 'PENDING') void loadSourceSchema(row);
      }
    } catch (err) {
      console.error(`Failed to refresh dataset ${row.id}:`, err);
    }
  };

  // Every job this tab knows about (GET /api/jobs + the ones it started), so a row's bootstrap,
  // import or refresh is found by dataset id after a navigation or reload (UI audit A6).
  const storeJobs = useJobsStore((s) => s.jobs);
  const dismissedJobs = useJobsStore((s) => s.dismissed);
  const jobList = useMemo(() => Object.values(storeJobs), [storeJobs]);
  const datasetJob = (datasetId: string) => latestJob(jobList, (j) => isDatasetJob(j, datasetId));

  /** A job worth a card on the open dataset: one still running, or a failure nobody dismissed. */
  const shownJob = (job: Job | null) => {
    if (!job) return null;
    if (isLive(job)) return job;
    return isFailure(job) && !dismissedJobs[job.id] ? job : null;
  };
  const selectedDatasetJob = selectedDataset ? datasetJob(selectedDataset.id) : null;
  const selectedProfileJob = selectedDataset
    ? latestJob(jobList, (j) => isProfilingJob(j, selectedDataset.id))
    : null;
  const detailJob = shownJob(selectedDatasetJob);
  const profileJob = shownJob(selectedProfileJob);

  // When the open dataset's job ends, re-read what it changed: the row and its freshness after a
  // bootstrap, import or refresh (J2-F11); the stored profile after a profiling run this page
  // re-attached to or retried (J3-F7).
  useJobsSettled([selectedDatasetJob, selectedProfileJob], (job) => {
    if (!selectedDataset) return;
    if (isProfilingJob(job, selectedDataset.id)) {
      // The tab shows stored evidence (J3-F1): re-read it once the run has written it.
      if (job.status === 'SUCCEEDED') void loadProfiles(selectedDataset, true);
      return;
    }
    void refreshDataset(selectedDataset);
    void loadRefreshPolicy(selectedDataset);
  });

  /**
   * A dataset mid-change (PENDING, or a live refresh) is followed to its real end so its badge
   * flips on its own: each repository's list is re-read once per tick (J2-F24 — forty pending
   * imports were forty pollers), immediately when one of its jobs ends, and a PENDING row whose
   * job already failed stops being watched and reads Failed (register F5).
   */
  usePendingDatasetWatch(datasets, jobList, (fresh) => {
    setDatasets((prev) => prev.map((d) => {
      const f = fresh.find((x) => x.id === d.id);
      return f ? { ...d, ...f } : d;
    }));
    setSelectedDataset((prev) => {
      const f = prev && fresh.find((x) => x.id === prev.id);
      return f && prev ? { ...prev, ...f } : prev;
    });
  });

  const typeOptions = [
    { label: 'All',        value: 'all' },
    { label: 'Relational', value: 'RELATIONAL_DB' },
    { label: 'Object',     value: 'OBJECT_STORAGE' },
    { label: 'Files',      value: 'FILE_SYSTEM' },
    { label: 'Iceberg',    value: 'ICEBERG_REST' },
  ];

  const filteredDatasets = useMemo(() => {
    return datasets.filter((ds) => {
      const matchesSearch =
        ds.name.toLowerCase().includes(search.toLowerCase()) ||
        ds.description.toLowerCase().includes(search.toLowerCase()) ||
        ds.repoName.toLowerCase().includes(search.toLowerCase());

      const matchesType = filterType === 'all' || ds.repoType === filterType;

      return matchesSearch && matchesType;
    });
  }, [datasets, search, filterType]);

  const columns: Column[] = [
    { key: 'idx', label: '', width: '28px', num: true, mono: true },
    { key: 'name', label: 'dataset identifier', mono: true },
    { key: 'description', label: 'description' },
    { key: 'repoName', label: 'repository location', width: '150px' },
    { key: 'repoType', label: 'origin type', width: '120px' },
    { key: 'accessMode', label: 'access', width: '96px' },
    { key: 'projectName', label: 'project', width: '130px' },
    { key: 'actions', label: 'actions', width: '120px', align: 'right' },
  ];

  const handleRegisterClick = () => {
    // Reachable only once the list has loaded (the button is disabled until then), so an empty
    // project list is true — said in place, with the way on, instead of a 4 s toast.
    if (projects.length === 0) {
      setRegisterNotice('no-project');
      return;
    }
    setRegisterNotice(null);
    setActiveProjectId(preferredProjectId(projects, storeActiveProjectId));
    setRegisterStep(1);
    setIsRegisterSelectOpen(true);
  };

  const handleProjectConfirm = () => {
    const claim = projectRepositoryClaim(activeProjectId, repositories, unreadProjectIds);
    if (claim !== 'some') {
      // A project whose repository list failed to load is not a project with none (review of UA2).
      setRegisterNotice(claim === 'unread' ? 'repositories-unread' : 'no-repository');
      return;
    }
    const projectRepos = repositories.filter((r) => r.projectId === activeProjectId);
    setRegisterNotice(null);
    setActiveRepoId(projectRepos[0].id);
    setRegisterStep(2);
  };

  const handleRepoConfirm = () => {
    setIsRegisterSelectOpen(false);
    setEditingDataset(null);
    setTimeout(() => {
      registerDialogRef.current?.open();
    }, 100);
  };

  const handleEditClick = (e: React.MouseEvent, row: AggregatedDataset) => {
    e.stopPropagation();
    setEditingDataset(row);
    setActiveProjectId(row.projectId);
    setActiveRepoId(row.repositoryId);
    setTimeout(() => {
      registerDialogRef.current?.open(row);
    }, 100);
  };

  const deleteDatasetDirect = async (e: React.MouseEvent, row: AggregatedDataset) => {
    e.stopPropagation();
    // J8-F14 / J2-F15: name what the delete does. detachDataset rejects the dataset's alignments
    // in EVERY project and flags those models for a rebuild; a live dataset's queries fail at once
    // (nothing is left to read), an upload's keep reading the active schema's snapshot until the
    // next promoted rebuild drops its classes. The lake copy is not deleted.
    const live = readsLive(repositories.find((r) => r.id === row.repositoryId), row);
    const ok = await confirm({
      title: `Delete dataset ${row.name}?`,
      confirmLabel: 'Delete dataset',
      testId: 'dataset-delete',
      consequences: [
        'Its registration and its source schema are removed.',
        'Every alignment that uses it is rejected, in every project, and those integration models need a full rebuild.',
        live
          ? 'It is read live, so queries, products and constraints over the classes it feeds fail from now on.'
          : 'The active schema keeps serving its last snapshot until the next rebuild is promoted, which drops the classes only it feeds.',
        'Its lake copy, if any, is kept.',
      ],
    });
    if (!ok) {
      return;
    }

    setDeletingDatasetId(row.id);
    try {
      await api.deleteDataset(row.projectId, row.repositoryId, row.id);
      showToast(`Dataset ${row.name} removed successfully.`);
      fetchAllDatasetsAndRepos();
    } catch (err: unknown) {
      showToast(errorMessage(err, 'Failed to delete dataset.'), 'error');
    } finally {
      setDeletingDatasetId(null);
    }
  };

  /**
   * Bootstrap the dataset (if not already done / forced) then load and display its source graph.
   * If the graph is already in Fuseki (tripleCount > 0) we skip bootstrapping.
   */
  const openLocalGraph = async (e: React.MouseEvent, row: AggregatedDataset) => {
    e.stopPropagation();
    if (!row.snapshotId) {
      showToast('Cannot bootstrap: dataset has no bound snapshot.', 'error');
      return;
    }

    setGraphDataset(row);
    setGraphData(null);
    setGraphSelectedNode(null);
    setGraphLoading(true);

    try {
      const existing = await api.fetchSourceGraph(row.id);

      if (existing.empty) {
        // Say which of the three it is — the question this used to ask back was the user's (J2-F5).
        const shown = resolveDatasetStatus(row.bootstrapStatus, datasetJob(row.id));
        showToast(
          shown.status === 'FAILED'
            ? `No source graph: the bootstrap failed${shown.reason ? ` — ${shown.reason}` : ''}.`
            : shown.status === 'PENDING'
              ? 'No source graph yet: the bootstrap is still running.'
              : 'No source graph for this dataset yet.',
          'error',
        );
        setGraphDataset(null);
        setGraphLoading(false);
        return;
      }

      setGraphData({ nodes: existing.nodes, edges: existing.edges, tripleCount: existing.tripleCount });
    } catch (err: unknown) {
      showToast(errorMessage(err, 'Failed to load source graph.'), 'error');
      setGraphDataset(null);
    } finally {
      setGraphLoading(false);
      setBootstrapLoading(null);
    }
  };

  /**
   * Re-bootstrap the open dataset — but check first what that would break.
   *
   * Re-bootstrapping used to clear the source graph unconditionally, so a renamed column took
   * every mapping that referenced it with no warning. Now the drift check runs first and only a
   * BREAKING verdict interrupts: warnings and additions apply straight through, because a dialog
   * that appears on every added column is one people learn to click past.
   */
  const reBootstrapGraph = async () => {
    if (!graphDataset?.snapshotId) return;
    const target = graphDataset;

    setDriftChecking(true);
    let report: DriftReport;
    try {
      report = await api.checkDatasetDrift(target.projectId, target.repositoryId, target.id);
    } catch (err: unknown) {
      // A drift check that cannot run is not permission to skip the gate: the whole point is to
      // stop writing blind, and proceeding here would be exactly that.
      showToast(errorMessage(err, 'Could not check for schema drift — nothing was changed.'), 'error');
      setDriftChecking(false);
      return;
    }
    setDriftChecking(false);

    if (decide(report).action === 'review') {
      // Pre-tick the renames: they are the repair the user almost always wants, and the panel
      // shows the column names so an unwanted one is one click to drop.
      setSelectedRenames(repairableRenames(report).map((r) => r.fromIri));
      setDriftReview({ dataset: target, report });
      setDatasets((prev) =>
        prev.map((d) => (d.id === target.id ? { ...d, bootstrapStatus: 'DRIFTED' } : d))
      );
      return;
    }
    await startBootstrapJob(target, false);
  };

  /**
   * A failed row's Retry (J2-F5): the failed job's own operation again (`retryFor` — a bootstrap,
   * or the refresh that re-reads an import's upstream), or, when that job is no longer in the
   * jobs list, a re-bootstrap of the snapshot the dataset has. Nothing is offered that cannot run.
   */
  const datasetRetry = (row: AggregatedDataset, job: Job | null) => {
    if (job && isFailure(job)) {
      const again = retryFor(job, api);
      if (again) return again;
    }
    if (row.snapshotId) {
      return { label: 'Retry bootstrap', run: () => api.bootstrapDataset(row.id) };
    }
    return null;
  };

  const runDatasetRetry = async (row: AggregatedDataset, retry: { label: string; run: () => Promise<{ jobId: string }> }) => {
    try {
      const { jobId } = await retry.run();
      useJobsStore.getState().started(jobId);
      showToast(`${retry.label} started for ${row.name}.`);
    } catch (err: unknown) {
      showToast(errorMessage(err, `Could not retry ${row.name}.`), 'error');
    }
  };

  /**
   * "Review changes" (J2-F16): read what re-reading the source would change and show the report,
   * whatever it says — a user who asked to review is owed the answer, including "nothing changed".
   * Works for a live dataset too (the drift probe reads a federated source through the same scan
   * queries use); the Network icon it used to require is only offered for snapshot datasets.
   * Exemplars: dbt Cloud and Fivetran surface schema changes as a reviewable list on the object,
   * not as a hover title on a badge.
   */
  const reviewDrift = async (target: AggregatedDataset) => {
    setDriftChecking(true);
    let report: DriftReport;
    try {
      report = await api.checkDatasetDrift(target.projectId, target.repositoryId, target.id);
    } catch (err: unknown) {
      showToast(errorMessage(err, 'Could not check the source for schema changes.'), 'error');
      return;
    } finally {
      setDriftChecking(false);
    }
    setSelectedRenames(repairableRenames(report).map((r) => r.fromIri));
    setDriftReview({ dataset: target, report });
    // A BREAKING report has already marked the dataset DRIFTED on the server; say so here too,
    // or "Keep current schema" leaves a row reading READY until the next reload.
    const status = statusAfterDriftCheck(target.bootstrapStatus, report);
    if (status !== target.bootstrapStatus) {
      setDatasets((prev) => prev.map((d) => (d.id === target.id ? { ...d, bootstrapStatus: status } : d)));
      setSelectedDataset((prev) => (prev && prev.id === target.id ? { ...prev, bootstrapStatus: status } : prev));
    }
  };

  /** Submit the bootstrap job itself. `force` applies a schema the drift gate withheld. */
  const startBootstrapJob = async (target: AggregatedDataset, force: boolean) => {
    setBootstrapTarget(target);
    setBootstrapLoading(target.id);
    setGraphData(null);
    setGraphSelectedNode(null);
    try {
      const { jobId } = force
        ? await api.forceBootstrapDataset(target.id)
        : await api.bootstrapDataset(target.id);
      useJobsStore.getState().started(jobId);
      setBootstrapJobs((prev) => ({ ...prev, [target.id]: jobId }));
    } catch (err: unknown) {
      showToast(errorMessage(err, 'Failed to re-bootstrap.'), 'error');
      setBootstrapLoading(null);
      setBootstrapTarget(null);
    }
  };

  /** Apply the withheld schema as-is, leaving the affected mappings dangling for now. */
  const applyDriftAnyway = async () => {
    if (!driftReview) return;
    const target = driftReview.dataset;
    setDriftBusy(true);
    setDriftReview(null);
    setDriftBusy(false);
    await startBootstrapJob(target, true);
  };

  /**
   * Repoint the confirmed renames, then apply.
   *
   * Order matters: repairing first means that by the time the new graph lands, the mappings
   * already name the columns it contains. Applying first would leave a window in which the
   * active schema references columns the source no longer has.
   */
  const repairDriftAndApply = async () => {
    if (!driftReview) return;
    const target = driftReview.dataset;
    const renames = repairableRenames(driftReview.report)
      .filter((r) => selectedRenames.includes(r.fromIri))
      .map((r) => ({ fromIri: r.fromIri, toIri: r.toIri }));
    if (renames.length === 0) return;

    setDriftBusy(true);
    try {
      const result = await api.repairDatasetDrift(
        target.projectId, target.repositoryId, target.id, renames,
      );
      showToast(`Repointed ${result.rewritten} mapping${result.rewritten === 1 ? '' : 's'}.`);
    } catch (err: unknown) {
      // Nothing is applied when the repair fails — the old graph is still live and still correct.
      showToast(errorMessage(err, 'Repair failed — the schema was left unchanged.'), 'error');
      setDriftBusy(false);
      return;
    }
    setDriftBusy(false);
    setDriftReview(null);
    await startBootstrapJob(target, true);
  };

  /** The re-bootstrap job finished: refresh the row, then the graph it just rewrote. */
  const handleBootstrapJobDone = async (target: AggregatedDataset, job: Job) => {
    setBootstrapLoading(null);
    setBootstrapTarget(null);
    await refreshDataset(target);

    if (job.status !== 'SUCCEEDED') {
      showToast(job.errorMessage ?? 'Re-bootstrap failed.', 'error');
      return;
    }

    // The job succeeded but the operator withheld the schema. This is reachable without going
    // through the review panel — a bootstrap started elsewhere (a fresh upload, an API call) hits
    // the same gate — so the outcome has to be legible here too, not only where we prompted.
    if (job.result?.applied === 'false') {
      showToast(job.result.driftSummary ?? 'Schema drift: the new schema was not applied.', 'error');
      return;
    }

    if (selectedDataset?.id === target.id) void loadSourceSchema(target);
    try {
      const updated = await api.fetchSourceGraph(target.id);
      setGraphData({ nodes: updated.nodes, edges: updated.edges, tripleCount: updated.tripleCount });
      showToast(`Source schema re-read for ${target.name}: ${updated.tripleCount} triples.`);
    } catch (err: unknown) {
      showToast(errorMessage(err, 'Failed to load the rebuilt source graph.'), 'error');
    }
  };

  // Unified Detail Panel Handlers.
  // `selectDataset` seeds the detail state; `openDataset` additionally puts the dataset
  // in the URL — the detail is a routed page (/datasets/:datasetId), so it can be
  // linked, refreshed and opened in a second tab. The URL→state effect below calls
  // selectDataset directly so a deep link doesn't re-navigate.
  const selectDataset = (row: AggregatedDataset, tab: typeof activeDetailTab = 'metadata') => {
    setSelectedDataset(row);
    // Fetched from the event that opens the panel rather than from an effect watching
    // selectedDataset: opening is the thing that needs the policy, and an effect would be a
    // second, indirect statement of the same trigger (react.dev/learn/you-might-not-need-an-effect).
    // Nothing is cleared here — the answer carries its dataset id, so the panel simply shows no
    // freshness block until the one for THIS dataset arrives.
    void loadRefreshPolicy(row);
    setActiveDetailTab(tab);
    setDetailPreviewData(null);
    setDetailPreviewError(null);

    void loadSourceSchema(row);
    void loadProfiles(row, true);
  };

  // The URL is the ONLY writer of which dataset is open. The event handlers below navigate
  // and stash the requested tab; the URL→state effect performs the selection when the route
  // commits. The previous shape — handlers eagerly calling selectDataset/setSelectedDataset(null)
  // AND navigating, with the effect also writing selection — was two writers racing: React
  // Router processes navigate() asynchronously, so for a render or more the effect saw the OLD
  // route with the NEW selection and "corrected" it (a just-opened detail flash-closed via the
  // !routeDatasetId branch; a just-closed one flash-reopened). Under load the e2e suite caught
  // both directions as a visible dataset-detail with no name, or a detail that reopened after
  // close (v32/v34 hangs, 2026-08-27).
  const pendingDetailTab = React.useRef<typeof activeDetailTab | null>(null);

  const openDataset = (row: AggregatedDataset, tab: typeof activeDetailTab = 'metadata') => {
    pendingDetailTab.current = tab;
    if (routeDatasetId !== row.id) {
      navigate(`/datasets/${row.id}`);
    } else {
      // Already on the route (e.g. a second action button on the open row): the effect will
      // not re-fire, so apply the tab directly.
      setActiveDetailTab(tab);
      pendingDetailTab.current = null;
    }
  };

  /**
   * Close by navigating only — the URL is the source of truth for which dataset is open, and the
   * URL→state effect above clears the selection when `routeDatasetId` goes away.
   *
   * **V49b: clearing the state here as well made the panel flicker back open.** `navigate` does not
   * land in the same commit as a `setState`, so for one render the state said "nothing selected"
   * while the URL still said `/datasets/:id` — and that effect, whose whole job is to re-derive the
   * selection from the URL, dutifully re-opened the panel. It then closed again when the navigation
   * landed. Watched in a Playwright trace: `close()` asserted the panel gone, `open()` found it
   * visible ~100 ms later and therefore skipped its own row click, and the click that followed
   * waited ten minutes for a tab on a panel that had gone for good.
   */
  const closeDataset = () => {
    navigate('/datasets');
  };

  // URL → state: a deep link, browser back/forward, or a handler's navigate() above drives the
  // detail. Runs after the aggregate fetch so a refresh on /datasets/:id can actually find its row.
  useEffect(() => {
    if (!routeDatasetId) {
      // Back to the list (browser back included): drop the selection.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSelectedDataset((prev) => (prev ? null : prev));
      return;
    }
    if (selectedDataset?.id === routeDatasetId) return;
    const row = datasets.find((d) => d.id === routeDatasetId);
    if (row) {
      selectDataset(row, pendingDetailTab.current ?? 'metadata');
      pendingDetailTab.current = null;
    }
    // selectDataset is a plain function re-created every render; keying on it would
    // re-run this on every render. The three real inputs are listed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeDatasetId, datasets, selectedDataset?.id]);

  // "Not found" is a claim about the whole list, so it is made only when every source answered;
  // with a source unaccounted for, the dataset may be in it (J8-F2).
  const listComplete = !loading && !error && loadFailures.length === 0;
  const registerBlockedReason = loading
    ? 'Loading your projects and repositories…'
    : error ? 'Unavailable until your datasets load' : null;
  const routeDatasetAbsent = !!routeDatasetId && !loading && !datasets.some((d) => d.id === routeDatasetId);
  const routeDatasetMissing = routeDatasetAbsent && listComplete;
  const routeDatasetUnknown = routeDatasetAbsent && !listComplete;

  const handleRowClick = (row: AggregatedDataset) => openDataset(row, 'metadata');

  const loadDetailPreview = async (dataset: AggregatedDataset, force = false) => {
    if (!force && (detailPreviewData || detailPreviewLoading)) return;
    setDetailPreviewLoading(true);
    setDetailPreviewError(null);
    try {
      const res = await api.previewDatasetById(dataset.projectId, dataset.repositoryId, dataset.id);
      setDetailPreviewData(res);
    } catch (err: unknown) {
      // A failed preview shows the source's own message. It never falls back to rows.
      setDetailPreviewData(null);
      setDetailPreviewError(errorMessage(err, 'Failed to retrieve preview.'));
    } finally {
      setDetailPreviewLoading(false);
    }
  };

  /**
   * Profile the dataset's snapshot, then re-read what was stored. The tab shows the persisted
   * profile, never the run's raw payload: the payload of a partly failed run is what crashed the
   * page (J3-F9), and it is not what the planner and discovery read anyway.
   */
  const runDetailProfiling = async (dataset: AggregatedDataset) => {
    const id = dataset.id;
    setProfilingRuns((prev) => ({ ...prev, [id]: true }));
    setProfileRunErrors((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    try {
      const res = await api.runOperatorJob('profiling', profilingRequest(dataset), {
        // The job's own card replaces the tab's scripted narration, and the job stays findable
        // after a navigation (J3-F7).
        onSubmitted: (jobId) => useJobsStore.getState().started(jobId),
      });
      if (res.success) {
        const errors = runErrorsOf(res.data?.profile);
        setProfileRunErrors((prev) => ({ ...prev, [id]: errors }));
        const failed = Object.keys(errors).length;
        // Named, because the user may be looking at another dataset by now.
        showToast(failed === 0
          ? `Profiled ${dataset.name}.`
          : `Profiled ${dataset.name}; ${failed} column${failed === 1 ? '' : 's'} could not be profiled.`,
          failed === 0 ? 'success' : 'error');
      } else {
        showToast(`Profiling ${dataset.name} failed: ${res.errorMessage || 'no reason was reported.'}`, 'error');
      }
    } catch (err: unknown) {
      showToast(`Profiling ${dataset.name} failed: ${errorMessage(err, 'the request failed.')}`, 'error');
    } finally {
      setProfilingRuns((prev) => {
        const next = { ...prev };
        delete next[id];
        return next;
      });
      void loadProfiles(dataset, false);
    }
  };

  useEffect(() => {
    if (selectedDataset && activeDetailTab === 'preview') {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      loadDetailPreview(selectedDataset);
    }
    // loadDetailPreview is a plain function re-created every render, not memoized —
    // depending on it would re-run this on every render regardless of tab/dataset.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedDataset, activeDetailTab]);

  // Save the edited wrapper onto the selected class's model_source row; compile writes it back
  // over the class's nx:wrapperSql, which is what the query engine executes (M6).
  // Two wrapper fields now, since Dataset.wrapper was dropped.
  // In-flight, timeout and re-read handling live in the session (modelSession.ts: saveWrapper).
  const handleSaveWrapper = () => void session.getState().saveWrapper();

  // Enrol the dataset in the project's model — seeds one editable wrapper per source class.
  const handleEnrollDataset = () => void session.getState().enrol();

  // ── model constraints (M6) ─────────────────────────────────────────────────
  const handleAddConstraint = async () => {
    if (!selectedDataset || !selectedConstraintClass || !constraintExpr.trim()) return;
    setSavingConstraint(true);
    try {
      await api.addConstraint(selectedDataset.projectId, {
        classIri: selectedConstraintClass,
        expression: constraintExpr.trim(),
      });
      setConstraintExpr('');
      showToast('Constraint added (unenforced).', 'success');
      await session.getState().load(selectedDataset);
    } catch (err: unknown) {
      // The server validates the expression against the class's columns via DuckDB and
      // returns the parse error as the RFC-7807 detail — surface it verbatim.
      showToast(errorMessage(err, 'Invalid constraint expression.'), 'error');
    } finally {
      setSavingConstraint(false);
    }
  };

  const handleToggleConstraintEnforced = async (c: ModelConstraint) => {
    if (!selectedDataset) return;
    try {
      await api.patchConstraint(selectedDataset.projectId, c.id, !c.enforced);
      // J4-F4: enforcement is read per query, so the toast says "next query", never "recompile".
      const className = session.getState().datasetClasses.find((k) => k.iri === c.classIri)?.name ?? null;
      showToast(constraintToggleToast(!c.enforced, className), 'success');
      await session.getState().load(selectedDataset);
    } catch (err: unknown) {
      showToast(errorMessage(err, 'Failed to update constraint.'), 'error');
    }
  };

  const handleDeleteConstraint = async (c: ModelConstraint) => {
    if (!selectedDataset) return;
    try {
      await api.deleteConstraint(selectedDataset.projectId, c.id);
      await session.getState().load(selectedDataset);
    } catch (err: unknown) {
      showToast(errorMessage(err, 'Failed to delete constraint.'), 'error');
    }
  };

  // ── constraint discovery (Phase 3B) ─────────────────────────────────────────
  // LIMA samples the class's rows, so this runs on the job substrate exactly like
  // compile: submit, watch the returned job (<JobCard> below), reload on success.
  // There is no separate accept step — a survivor lands unenforced and is enforced
  // through handleToggleConstraintEnforced above, same as a manual constraint.
  //
  // Both maps are keyed by classIri (the tab always passes its own resolved `selCls.iri`, never
  // this view's `selectedConstraintClass`, which can briefly lag it) so that switching the class
  // dropdown while a job is in flight can never misattribute its card, disabled button, or
  // completion toast to whichever class happens to be on screen when it finishes.
  const handleDiscoverConstraints = async (classIri: string) => {
    if (!selectedDataset) return;
    setDiscoveringClasses(prev => ({ ...prev, [classIri]: true }));
    // Clear the previous run's note up front: it describes a run that is now being replaced.
    setDiscoveryNotes(prev => {
      const next = { ...prev };
      delete next[classIri];
      return next;
    });
    try {
      const { jobId } = await api.discoverConstraints(selectedDataset.projectId, {
        datasetId: selectedDataset.id,
        classIri,
      });
      setDiscoverJobs(prev => ({ ...prev, [classIri]: jobId }));
    } catch (err: unknown) {
      showToast(errorMessage(err, 'Failed to start constraint discovery.'), 'error');
      setDiscoveringClasses(prev => {
        const next = { ...prev };
        delete next[classIri];
        return next;
      });
    }
  };

  const handleDiscoverJobDone = (classIri: string, job: Job) => {
    setDiscoveringClasses(prev => {
      const next = { ...prev };
      delete next[classIri];
      return next;
    });
    setDiscoverJobs(prev => {
      const next = { ...prev };
      delete next[classIri];
      return next;
    });
    // Named so the toast is unambiguous even if the user has since switched to another class.
    const className = datasetClasses.find(c => c.iri === classIri)?.name ?? classIri;
    if (job.status === 'SUCCEEDED') {
      // V43: the server phrases the outcome, because "complete — review candidates below" was a
      // lie whenever nothing was persisted, and the honest wording depends on WHY nothing was
      // (found nothing at all, versus found row-PAIR dependencies that cannot be expressed as a
      // constraint on one row). `summary` is built in IntegrationModelService.discoverySummary,
      // which also leads with the degraded reason when there is one.
      const summary = job.result?.summary;
      const persisted = Number(job.result?.persisted ?? '0');
      // A discovery that could not run — the lake down, the wrapper broken, LIMA crashing — still
      // SUCCEEDS with zero candidates, because one awkward class must not fail the whole request
      // (ConstraintDiscoveryOperator.discoverOverWrapperOutput). That outage was once
      // indistinguishable here from a class that genuinely has no constraints. It is the one case
      // that gets the error toast: an empty-but-honest run is still a completed job, and there is
      // no third toast kind to introduce for it — its honesty is in the sentence, and (because a
      // toast fades in four seconds while "why is the list still empty?" does not) in the note
      // pinned to the tab below.
      const degraded = job.result?.degraded === 'true';
      showToast(
        summary
          ? `${className}: ${summary}`
          : degraded
            ? `Constraint discovery for ${className} could not complete — ${job.result?.reason ?? 'no reason reported'}.`
            : `Constraint discovery complete for ${className}.`,
        degraded ? 'error' : 'success',
      );
      if (summary && persisted === 0) {
        setDiscoveryNotes(prev => ({ ...prev, [classIri]: summary }));
      }
      void session.getState().reload();
    } else {
      // FAILED or CANCELLED — nothing was persisted worth reloading for.
      showToast(job.errorMessage ?? `Constraint discovery for ${className} did not complete.`, 'error');
    }
  };

  const handlePreviewWrapper = async () => {
    const ds = selectedDataset;
    const selection = wrapperSeedSelection(ds?.id, selectedWrapperClass);
    if (!ds || selection === null) return;
    setPreviewingSelection(selection);
    const sql = wrapperText;
    try {
      const res = await api.previewWrapper(ds.projectId, ds.repositoryId, ds.id, sql);
      setWrapperPreview({ selection, sql, data: res });
    } catch (err: unknown) {
      const message = errorMessage(err, 'Failed to preview wrapper.');
      showToast(session.getState().dataset?.id === ds.id ? message : `${ds.name}: ${message}`, 'error');
    } finally {
      setPreviewingSelection((current) => (current === selection ? null : current));
    }
  };

  const projectRepos = useMemo(() => {
    return repositories.filter((r) => r.projectId === activeProjectId);
  }, [repositories, activeProjectId]);

  /** The dataset a running bootstrap job belongs to, wherever it was started from (J2-F16). */
  const overlayTarget = bootstrapTarget ?? graphDataset;
  // What the wrapper editor is editing: the dataset the route has open and its selected class.
  // Every keyed piece of wrapper state is read through it.
  const wrapperSelection = wrapperSeedSelection(selectedDataset?.id, selectedWrapperClass);

  return (
    <div className="flex-grow flex flex-col p-6 gap-6 min-h-0 relative select-none">
      {/* Toast popup */}
      <Toast toast={toast} onDismiss={() => setToast(null)} testId="datasets-toast" />

      {/* List (toolbar + grid) — hidden while a dataset detail route is open: the
          detail is a page now, not an overlay on top of the list. */}
      {!routeDatasetId && (<>
      {/* Toolbar */}
      <div className="flex flex-col md:flex-row items-stretch md:items-center justify-between gap-4 shrink-0">
        <div className="relative flex-1 max-w-sm">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            placeholder="Search datasets..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full h-8 rounded-md border border-border bg-card pl-9 pr-16 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary focus:border-primary transition-all"
          />
          <span className="absolute right-3 top-1/2 -translate-y-1/2 font-mono text-[10px] text-muted-foreground/70 tabular-nums select-none">
            {filteredDatasets.length}/{datasets.length}
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
          <BlockedReason id="datasets-register-reason" reason={registerBlockedReason} />
          <button
            type="button"
            id="tour-datasets-new"
            data-testid="datasets-register-button"
            onClick={handleRegisterClick}
            disabled={registerBlockedReason !== null}
            aria-describedby={registerBlockedReason ? 'datasets-register-reason' : undefined}
            className="ml-1 inline-flex items-center gap-1.5 h-8 px-4 rounded-md bg-primary font-semibold text-primary-foreground hover:bg-primary/90 transition-colors text-[12.5px] disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? <Loader2 size={12} className="animate-spin" aria-hidden="true" /> : <Plus size={12} aria-hidden="true" />}
            Register dataset
          </button>
        </div>
      </div>

      {registerNotice === 'no-project' && projects.length === 0 && (
        <div role="status" data-testid="datasets-no-project" className="flex flex-wrap items-center gap-3 rounded-md border border-border bg-muted/20 px-4 py-2.5 text-xs shrink-0">
          <span className="text-foreground">A dataset lives in a repository inside a project, and you have no project yet.</span>
          <Link to="/projects" state={{ create: true }} className="font-semibold text-primary underline underline-offset-2">
            Create a project
          </Link>
        </div>
      )}

      {!error && (
        <LoadFailures
          failures={loadFailures}
          total={sourceCount}
          sourceNoun="source"
          onRetry={() => void fetchAllDatasetsAndRepos()}
          retrying={loading}
          testId="datasets-partial-error"
        />
      )}

      {/* Grid Content */}
      <div id="tour-datasets-list" className="flex-grow overflow-y-auto">
        <DataTable
          command="Datasets"
          info={CONCEPTS.bootstrapping}
          columns={columns}
          rows={filteredDatasets}
          onRowClick={handleRowClick}
          getRowTestId={(row) => `datasets-row-${row.name}`}
          emptyMessage={
            <ListEmptyBody
              claim={emptyListClaim({ loading, error, failures: loadFailures, loaded: datasets.length })}
              noun="datasets"
              loadingLabel="Aggregating schemas..."
              errorTitle="Couldn't load your datasets"
              error={error}
              onRetry={() => void fetchAllDatasetsAndRepos()}
              errorTestId="datasets-load-error"
              incompleteTestId="datasets-incomplete-empty"
            >
              <div className="flex flex-col items-center gap-4 py-8">
                <div className="w-16 h-16 rounded-full bg-muted/50 border border-border flex items-center justify-center mb-2">
                  <Table className="w-8 h-8 text-muted-foreground/60" />
                </div>
                <div className="text-center space-y-1.5">
                  <h3 className="text-base font-semibold text-foreground tracking-tight">No datasets found</h3>
                  <p className="text-xs text-muted-foreground max-w-sm">
                    Register datasets from your connected repositories to preview data, profile columns, and discover constraints.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={handleRegisterClick}
                  className="mt-4 inline-flex items-center gap-2 h-9 px-5 rounded-md bg-primary font-semibold text-primary-foreground hover:bg-primary/90 transition-colors uppercase tracking-wider text-[11px] shadow-sm"
                >
                  <Plus size={14} />
                  Register Dataset
                </button>
              </div>
            </ListEmptyBody>
          }
          renderCell={{
            name: (row) => {
              const parts = row.name.split('.');
              const ext = parts.length > 1 ? parts[parts.length - 1].toLowerCase() : '';
              const isFile = ['csv', 'json', 'tsv', 'parquet', 'avro'].includes(ext);
              // The row's own status, corrected by its latest job (register F5, J2-F5): a PENDING
              // row whose job failed is Failed, and a Failed row carries the job's reason.
              const rowJob = datasetJob(row.id);
              const shown = resolveDatasetStatus(row.bootstrapStatus, rowJob);
              const rowRetry = shown.status === 'FAILED' ? datasetRetry(row, rowJob) : null;
              const rowDuration = shown.live && rowJob ? jobDurationMs(rowJob) : null;
              return (
                <span className="font-mono text-xs text-foreground flex items-center gap-1.5">
                  {isFile ? (
                    <FileText size={12} className="text-sky-400 shrink-0" />
                  ) : (
                    <Table size={12} className="text-primary shrink-0" />
                  )}
                  {row.name}
                  {isFile && (
                    <span className="font-mono text-[8px] uppercase tracking-wider font-extrabold px-1.5 rounded-sm bg-sky-500/10 text-sky-400 border border-sky-500/20">
                      {ext}
                    </span>
                  )}
                  {shown.status === 'PENDING' && (
                    <span data-testid={`datasets-status-${row.name}`} className="font-mono text-[8px] uppercase tracking-wider font-extrabold px-1.5 rounded-sm bg-amber-500/10 text-amber-400 border border-amber-500/20 animate-pulse" title="Bootstrapping in progress...">
                      Bootstrapping…
                    </span>
                  )}
                  {shown.status === 'FAILED' && (
                    <span data-testid={`datasets-status-${row.name}`} className="font-mono text-[8px] uppercase tracking-wider font-extrabold px-1.5 rounded-sm bg-rose-500/10 text-rose-400 border border-rose-500/20" title={shown.reason ? `Failed: ${shown.reason}` : 'Failed. Open the dataset for the last job’s details.'}>
                      Failed
                    </span>
                  )}
                  {/* What a live job is doing, and for how long — beside the badge, never inside
                      it: the badge's literal text is an e2e contract. */}
                  {shown.live && rowJob && (
                    <span data-testid={`datasets-stage-${row.name}`} className="font-mono text-[9.5px] text-muted-foreground truncate max-w-[220px]">
                      {rowJob.progressStage ?? (rowJob.status === 'PENDING' ? 'queued' : 'running')}
                      {rowDuration != null ? ` · ${formatDuration(rowDuration)}` : ''}
                    </span>
                  )}
                  {shown.status === 'FAILED' && (
                    <DatasetFailureNote
                      name={row.name}
                      reason={shown.reason}
                      retryLabel={rowRetry?.label}
                      onRetry={rowRetry ? () => void runDatasetRetry(row, rowRetry) : undefined}
                    />
                  )}
                  {shown.status === 'READY' && (
                    <span data-testid={`datasets-status-${row.name}`} className="font-mono text-[8px] uppercase tracking-wider font-extrabold px-1.5 rounded-sm bg-emerald-500/10 text-emerald-400 border border-emerald-500/20" title="Source graph ready">
                      Ready
                    </span>
                  )}
                  {shown.status === 'DRIFTED' && (
                    // Not a failure: the dataset is serving its previous schema perfectly well.
                    // The new one was withheld because applying it would break something mapped.
                    <span
                      data-testid={`datasets-status-${row.name}`}
                      className="font-mono text-[8px] uppercase tracking-wider font-extrabold px-1.5 rounded-sm bg-orange-500/10 text-orange-400 border border-orange-500/20"
                      title="The source has changed shape in a way that breaks existing mappings. The previous schema is still live until you decide."
                    >
                      SCHEMA DRIFT
                    </span>
                  )}
                  {row.bootstrapStatus === 'DRIFTED' && (
                    // The badge's only instruction used to be its hover title, and the review sat
                    // behind a Network icon scrolled out of view — absent altogether for a live
                    // dataset (J2-F16). A sibling of the badge, so its testid still reads only
                    // "SCHEMA DRIFT" (d1-schema-drift).
                    <button
                      type="button"
                      data-testid={`datasets-review-drift-${row.name}`}
                      disabled={driftChecking}
                      onClick={(e) => { e.stopPropagation(); void reviewDrift(row); }}
                      // The warning token, not orange-400: it has a light-theme value that keeps
                      // this, the row's only instruction, at AA on a white card.
                      className="font-mono text-[9px] uppercase tracking-wider font-semibold px-1.5 py-0.5 rounded-sm border border-warning/40 text-warning hover:bg-warning/10 disabled:opacity-50"
                    >
                      Review changes
                    </button>
                  )}
                </span>
              );
            },
            repoType: (row) => <OChip label={row.repoType.replace('_', ' ')} type="class" sm />,
            accessMode: (row) => <AccessBadge mode={row.accessMode} testId={`datasets-access-${row.name}`} />,
            projectName: (row) => (
              <span className="font-mono text-xs text-foreground/90 flex items-center gap-1.5 max-w-[120px] truncate">
                <span
                  className="w-1.5 h-1.5 rounded-full shrink-0"
                  style={{ backgroundColor: row.projectColor }}
                />
                {row.projectName}
              </span>
            ),
            actions: (row) => (
              <div className="flex justify-end gap-1">
                <button
                  type="button"
                  title="Preview data"
                  onClick={(e) => { e.stopPropagation(); openDataset(row, 'preview'); }}
                  className="w-[26px] h-[26px] rounded-md border border-border/80 bg-muted/10 text-primary hover:bg-primary/10 transition-colors flex items-center justify-center"
                >
                  <Eye size={12} />
                </button>
                {row.snapshotId && (
                  <>
                    <button
                      type="button"
                      data-testid={`datasets-graph-${row.name}`}
                      title="Local Graph — view bootstrapped source schema"
                      disabled={bootstrapLoading === row.id || graphLoading}
                      onClick={(e) => openLocalGraph(e, row)}
                      className="w-[26px] h-[26px] rounded-md border border-violet-500/40 bg-violet-500/10 text-violet-400 hover:bg-violet-500/20 transition-colors disabled:opacity-40 flex items-center justify-center"
                    >
                      {bootstrapLoading === row.id ? (
                        <Loader2 size={12} className="animate-spin" />
                      ) : (
                        <Network size={12} />
                      )}
                    </button>
                    {/* The list does not know whether a profile is stored (that is one read per row);
                        the tab does. This used to guess from Dataset.columns, a field never sent,
                        so it always offered "Run profiling" (J2-F13). */}
                    <button
                      type="button"
                      title="Open column profile"
                      aria-label={`Open the column profile of ${row.name}`}
                      onClick={(e) => { e.stopPropagation(); openDataset(row, 'profile'); }}
                      className="w-[26px] h-[26px] rounded-md border border-border/80 bg-muted/10 text-primary hover:bg-primary/10 transition-colors flex items-center justify-center"
                    >
                      <Sparkles size={12} />
                    </button>
                  </>
                )}
                <button
                  type="button"
                  data-testid={`datasets-edit-${row.name}`}
                  title="Edit Metadata"
                  onClick={(e) => handleEditClick(e, row)}
                  className="w-[26px] h-[26px] rounded-md border border-border/80 bg-muted/10 text-muted-foreground hover:text-foreground transition-colors flex items-center justify-center"
                >
                  <Pencil size={12} />
                </button>
                <button
                  type="button"
                  title="Remove Dataset"
                  disabled={deletingDatasetId !== null}
                  onClick={(e) => deleteDatasetDirect(e, row)}
                  className="w-[26px] h-[26px] rounded-md border border-border/80 bg-muted/10 text-rose-400 hover:bg-rose-500/10 transition-colors disabled:opacity-40 flex items-center justify-center"
                >
                  {deletingDatasetId === row.id ? (
                    <Loader2 size={12} className="animate-spin" />
                  ) : (
                    <Trash2 size={12} />
                  )}
                </button>
              </div>
            ),
          }}
        />
      </div>
      </>)}

      {/* Deep link, but part of the list could not be read: the dataset may be in it. */}
      {routeDatasetUnknown && !selectedDataset && (
        <ErrorNotice
          testId="dataset-detail-load-error"
          title="Couldn't load this dataset"
          error={error ?? loadFailures[0]?.error}
          onRetry={() => void fetchAllDatasetsAndRepos()}
        >
          <button type="button" onClick={closeDataset} className="text-xs text-primary underline underline-offset-2">
            ← back to datasets
          </button>
        </ErrorNotice>
      )}

      {/* Deep link to a dataset this tenant doesn't have (or one just deleted). */}
      {routeDatasetMissing && !selectedDataset && (
        <div className="flex flex-col items-center justify-center gap-3 py-16 font-mono">
          <span className="text-sm text-foreground font-semibold">Dataset not found</span>
          <span className="text-xs text-muted-foreground">It may have been removed, or the link belongs to another workspace.</span>
          <button
            type="button"
            onClick={closeDataset}
            className="mt-2 rounded border border-border px-4 py-1.5 text-xs uppercase tracking-widest text-muted-foreground hover:text-foreground transition-colors"
          >
            ← back to datasets
          </button>
        </div>
      )}

      {/* Dataset flow steps selector dialog */}
      {/* J2-F26: on the shared Dialog — Escape, focus trap, a name — instead of a role="button"
          backdrop. */}
      {isRegisterSelectOpen && (
        <Dialog
          open
          onClose={() => setIsRegisterSelectOpen(false)}
          label={`Register dataset — step ${registerStep} of 2`}
          maxWidth="max-w-sm"
          className="mx-4 rounded"
        >
            <div className="flex items-center justify-between border-b border-border bg-muted/20 px-5 py-3">
              <h2 className="font-mono text-xs uppercase tracking-widest text-foreground font-semibold">
                Register Dataset — Step {registerStep} of 2
              </h2>
              <button
                type="button"
                onClick={() => setIsRegisterSelectOpen(false)}
                aria-label="Close"
                className="text-muted-foreground hover:text-foreground transition-colors"
              >
                <X size={16} />
              </button>
            </div>

            <div className="px-5 py-4 space-y-2">
              {registerStep === 1 ? (
                <>
                  <p className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Select target project
                  </p>
                  {registerNotice === 'no-repository' && (
                    <div role="status" data-testid="datasets-no-repository" className="rounded-md border border-border bg-muted/20 px-3 py-2 text-xs space-y-1">
                      <p className="text-foreground">
                        {projects.find((p) => p.id === activeProjectId)?.name ?? 'This project'} has no repository yet.
                      </p>
                      {/* Makes it the working project first, so the Repositories chooser preselects
                          it rather than whatever was active before (J1-F9: no misfiling). */}
                      <Link
                        to="/repositories"
                        onClick={() => setStoreActiveProject(activeProjectId)}
                        className="font-semibold text-primary underline underline-offset-2"
                      >
                        Register a repository in it first
                      </Link>
                    </div>
                  )}
                  {registerNotice === 'repositories-unread' && (
                    <ErrorNotice
                      compact
                      testId="datasets-project-repositories-unread"
                      title={`${projects.find((p) => p.id === activeProjectId)?.name ?? 'This project'}'s repositories could not be read.`}
                      error={loadFailures.find((f) => f.name === projects.find((p) => p.id === activeProjectId)?.name)?.error}
                      onRetry={() => { setRegisterNotice(null); void fetchAllDatasetsAndRepos(); }}
                      retrying={loading}
                    />
                  )}
                  <div className="space-y-1.5 max-h-52 overflow-y-auto">
                    {projects.map((p) => (
                      <button
                        key={p.id}
                        type="button"
                        data-testid={`datasets-project-${p.name}`}
                        onClick={() => { setActiveProjectId(p.id); setRegisterNotice(null); }}
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
                </>
              ) : (
                <>
                  <p className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Select source connection
                  </p>
                  <div className="space-y-1.5 max-h-52 overflow-y-auto">
                    {projectRepos.map((r) => (
                      <button
                        key={r.id}
                        type="button"
                        data-testid={`datasets-repo-${r.name}`}
                        onClick={() => setActiveRepoId(r.id)}
                        className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-md border text-left transition-all ${
                          activeRepoId === r.id
                            ? 'border-primary/50 bg-primary/10 text-primary'
                            : 'border-border/60 bg-muted/5 text-foreground hover:bg-muted/20'
                        }`}
                      >
                        <span className="font-mono text-xs font-medium truncate flex-1">{r.name}</span>
                        <span className="font-mono text-[9px] uppercase tracking-wider text-muted-foreground shrink-0">
                          {r.type.replace('_', ' ')}
                        </span>
                      </button>
                    ))}
                  </div>
                </>
              )}
            </div>

            <div className="flex items-center justify-end gap-2 border-t border-border px-5 py-3 bg-muted/5">
              <button
                type="button"
                onClick={() => setIsRegisterSelectOpen(false)}
                className="rounded border border-border px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-muted-foreground hover:text-foreground transition-colors"
              >
                cancel
              </button>
              {registerStep === 1 ? (
                <button
                  type="button"
                  data-testid="datasets-project-confirm"
                  onClick={handleProjectConfirm}
                  className="rounded bg-primary px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-primary-foreground hover:bg-primary/90 transition-colors"
                >
                  next step
                </button>
              ) : (
                <button
                  type="button"
                  data-testid="datasets-repo-confirm"
                  onClick={handleRepoConfirm}
                  className="rounded bg-primary px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-primary-foreground hover:bg-primary/90 transition-colors"
                >
                  confirm
                </button>
              )}
            </div>
        </Dialog>
      )}


      {/* Unified Dataset Detail — a routed page (/datasets/:datasetId), not a modal:
          linkable, refreshable, browser back works, and wrapper SQL gets real height
          instead of a textarea three layers deep (redesign brief, datasets HIGH #2). */}
      {selectedDataset && (
          <div
            data-testid="dataset-detail"
            className="w-full rounded border border-border bg-card shadow-2xl overflow-hidden flex flex-col flex-grow min-h-0 text-foreground font-mono"
          >
            {/* Header */}
            <div className="flex items-center justify-between border-b border-border bg-muted/20 px-5 py-3 shrink-0">
              <h2 className="text-[13px] text-primary font-semibold flex items-center gap-2">
                <button
                  type="button"
                  onClick={closeDataset}
                  title="Back to datasets"
                  className="text-muted-foreground hover:text-foreground transition-colors text-[13px]"
                >
                  ← Datasets
                </button>
                <span className="text-muted-foreground/40">/</span>
                <Table size={14} className="text-primary" />
                <span className="font-mono text-[14px]" data-testid="dataset-detail-name">{selectedDataset.name}</span>
              </h2>
              <div className="flex-1" />
              {(selectedDataset.bootstrapStatus === 'READY' || selectedDataset.bootstrapStatus === 'DRIFTED') && (
                <button
                  type="button"
                  data-testid="dataset-review-drift"
                  disabled={driftChecking}
                  onClick={() => void reviewDrift(selectedDataset)}
                  title="Read the source's schema now and show what it would change, before anything is applied"
                  className={`mr-3 rounded border px-3 py-1 font-mono text-[10px] uppercase tracking-wider transition-colors disabled:opacity-50 ${
                    selectedDataset.bootstrapStatus === 'DRIFTED'
                      ? 'border-warning/40 text-warning hover:bg-warning/10'
                      : 'border-border text-muted-foreground hover:text-foreground'
                  }`}
                >
                  {driftChecking ? 'Checking the source…' : selectedDataset.bootstrapStatus === 'DRIFTED' ? 'Review changes' : 'Check for schema changes'}
                </button>
              )}
              <button
                type="button"
                data-testid="dataset-close"
                onClick={closeDataset}
                className="text-muted-foreground hover:text-foreground transition-colors"
              >
                <X size={16} />
              </button>
            </div>

            {/* The dataset's latest bootstrap, import or refresh: running, or failed and not yet
                dismissed — the job, not a guess about it. Found in the jobs store by dataset id,
                so it is still here after a navigation or reload (J2-F5, J2-F11, J2-F24). */}
            {detailJob && (
              <div className="px-5 py-3 border-b border-border bg-muted/10 shrink-0" data-testid="dataset-job">
                <JobCard
                  jobId={detailJob.id}
                  onRetried={() => void refreshDataset(selectedDataset)}
                  onDismiss={() => useJobsStore.getState().dismiss(detailJob.id)}
                />
              </div>
            )}

            {/* Tabs Selector */}
            <div className="flex border-b border-border bg-muted/10 shrink-0 overflow-x-auto">
              <button
                type="button"
                onClick={() => setActiveDetailTab('metadata')}
                className={`px-5 py-3 text-xs font-semibold uppercase tracking-wider border-r border-border transition-all whitespace-nowrap ${
                  activeDetailTab === 'metadata'
                    ? 'bg-card text-primary border-b-2 border-b-primary -mb-[1px]'
                    : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                Overview
              </button>
              <button
                type="button"
                data-testid="dataset-tab-preview"
                onClick={() => setActiveDetailTab('preview')}
                className={`px-5 py-3 text-xs font-semibold uppercase tracking-wider border-r border-border transition-all whitespace-nowrap ${
                  activeDetailTab === 'preview'
                    ? 'bg-card text-primary border-b-2 border-b-primary -mb-[1px]'
                    : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                Preview
              </button>
              <button
                type="button"
                data-testid="dataset-tab-profile"
                onClick={() => setActiveDetailTab('profile')}
                className={`px-5 py-3 text-xs font-semibold uppercase tracking-wider border-r border-border transition-all whitespace-nowrap ${
                  activeDetailTab === 'profile'
                    ? 'bg-card text-primary border-b-2 border-b-primary -mb-[1px]'
                    : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                Profile
              </button>
              <button
                type="button"
                data-testid="dataset-tab-constraints"
                onClick={() => setActiveDetailTab('constraints')}
                className={`px-5 py-3 text-xs font-semibold uppercase tracking-wider border-r border-border transition-all whitespace-nowrap ${
                  activeDetailTab === 'constraints'
                    ? 'bg-card text-primary border-b-2 border-b-primary -mb-[1px]'
                    : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                Constraints
              </button>
              <button
                type="button"
                data-testid="dataset-tab-wrapper"
                onClick={() => setActiveDetailTab('wrapper')}
                className={`px-5 py-3 text-xs font-semibold uppercase tracking-wider border-r border-border transition-all whitespace-nowrap ${
                  activeDetailTab === 'wrapper'
                    ? 'bg-card text-primary border-b-2 border-b-primary -mb-[1px]'
                    : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                Wrapper
              </button>
            </div>

            {/* Tab Body */}
            <div className="flex-1 overflow-y-auto p-6 bg-card flex flex-col min-h-0">
              {activeDetailTab === 'metadata' && (
                <DatasetOverviewTab
                  dataset={selectedDataset}
                  schema={sourceSchema}
                  onRetrySchema={() => void loadSourceSchema(selectedDataset)}
                  refreshPolicy={refreshPolicyState?.policy ?? null}
                  // Withheld until the answer for THIS dataset has arrived, so the panel never
                  // shows another dataset's policy or claims "no upstream" while still loading.
                  refreshReady={refreshPolicyState?.datasetId === selectedDataset.id}
                  refreshBusyLabel={datasetBusyLabel(selectedDatasetJob)}
                  onRefreshNow={async () => {
                    // The job is kept, not discarded (J2-F11): the dataset stays READY and keeps
                    // serving its previous copy while the refresh runs, so nothing on the row
                    // would change for minutes. Its card appears above the tabs, with stages, and
                    // re-reads the row and the freshness when the job ends.
                    const refreshed = selectedDataset;
                    const { jobId } = await api.refreshDataset(
                      refreshed.projectId, refreshed.repositoryId, refreshed.id);
                    // Awaited: the button stays busy until the job is in the store, where
                    // datasetBusyLabel keeps it disabled ("Refreshing…") until the job ends.
                    await useJobsStore.getState().started(jobId, () => {
                      void refreshDataset(refreshed);
                      void loadRefreshPolicy(refreshed);
                    });
                    await loadRefreshPolicy(refreshed);
                  }}
                  onSetRefreshPolicy={async (policy, intervalSeconds) => {
                    const seq = ++refreshPolicySeqRef.current;
                    const saved = await api.setRefreshPolicy(
                      selectedDataset.projectId, selectedDataset.repositoryId,
                      selectedDataset.id, policy, intervalSeconds);
                    if (seq !== refreshPolicySeqRef.current) return;
                    setRefreshPolicyState({ datasetId: selectedDataset.id, policy: saved });
                  }}
                />
              )}

              {activeDetailTab === 'preview' && (
                <DatasetPreviewTab
                  datasetName={selectedDataset.name}
                  loading={detailPreviewLoading}
                  data={detailPreviewData}
                  error={detailPreviewError}
                  onRetry={() => loadDetailPreview(selectedDataset, true)}
                />
              )}

              {activeDetailTab === 'profile' && (
                <DatasetProfileTab
                  // Keyed by dataset: the tab's own selection (which generation, which column)
                  // belongs to one dataset and must not carry over to the next (J3-F8).
                  key={selectedDataset.id}
                  datasetId={selectedDataset.id}
                  load={profileLoad}
                  running={!!profilingRuns[selectedDataset.id] || (!!profileJob && isLive(profileJob))}
                  runErrors={profileRunErrors[selectedDataset.id] ?? null}
                  hasSnapshot={!!selectedDataset.snapshotId}
                  onRunProfiling={() => void runDetailProfiling(selectedDataset)}
                  onRetry={() => void loadProfiles(selectedDataset, true)}
                  activity={profileJob && (
                    <JobCard
                      jobId={profileJob.id}
                      onRetried={() => undefined}
                      onDismiss={() => useJobsStore.getState().dismiss(profileJob.id)}
                    />
                  )}
                />
              )}

              {activeDetailTab === 'constraints' && (
                <DatasetConstraintsTab
                  modelLoading={modelLoading}
                  modelError={modelError ?? catalogError}
                  onRetryModel={() => void session.getState().reload()}
                  datasetClasses={datasetClasses}
                  selectedConstraintClass={selectedConstraintClass}
                  setSelectedConstraintClass={(iri) => session.getState().selectConstraintClass(iri)}
                  modelConstraints={modelConstraints}
                  orphanedConstraints={orphanedConstraints}
                  constraintExpr={constraintExpr}
                  setConstraintExpr={setConstraintExpr}
                  savingConstraint={savingConstraint}
                  onAddConstraint={() => void handleAddConstraint()}
                  onToggleEnforced={handleToggleConstraintEnforced}
                  onDeleteConstraint={handleDeleteConstraint}
                  discoveringClasses={discoveringClasses}
                  discoverJobs={discoverJobs}
                  discoveryNotes={discoveryNotes}
                  onDiscoverConstraints={(classIri) => void handleDiscoverConstraints(classIri)}
                  onDiscoverJobDone={handleDiscoverJobDone}
                  isDark={isDark}
                />
              )}

              {activeDetailTab === 'wrapper' && (
                <DatasetWrapperTab
                  modelLoading={modelLoading}
                  modelError={modelError}
                  onRetryModel={() => void session.getState().reload()}
                  modelSources={modelSources}
                  onEnrollDataset={handleEnrollDataset}
                  enrolling={selectedDataset.id in enrollingDatasets}
                  enrolCheck={enrolChecks[selectedDataset.id] ?? null}
                  checkingEnrol={selectedDataset.id in checkingEnrols}
                  onCheckEnrol={() => void session.getState().recheckEnrol()}
                  savingWrapper={wrapperSelection !== null && wrapperSelection in savingWrappers}
                  saveCheck={wrapperSelection === null ? null : (saveChecks[wrapperSelection]?.check ?? null)}
                  checkingSave={wrapperSelection !== null && wrapperSelection in checkingSaves}
                  onCheckSave={() => void session.getState().recheckWrapperSave()}
                  selectedWrapperClass={selectedWrapperClass}
                  setSelectedWrapperClass={(iri) => session.getState().selectWrapperClass(iri)}
                  wrapperText={wrapperText}
                  // Keyed off the dataset the ROUTE has open, not the session's: for the one render
                  // between a route change and the effect that opens it, the editor stays read-only.
                  wrapperSeeded={seededSelection !== null && seededSelection === wrapperSelection}
                  setWrapperText={(sql) => session.getState().setWrapperText(sql)}
                  wrapperPreviewLoading={wrapperSelection !== null && previewingSelection === wrapperSelection}
                  wrapperPreviewData={wrapperPreview !== null && wrapperPreview.selection === wrapperSelection && wrapperPreview.sql === wrapperText ? wrapperPreview.data : null}
                  onPreviewWrapper={handlePreviewWrapper}
                  onSaveWrapper={handleSaveWrapper}
                  enforcedConstraintClasses={datasetClasses
                    .filter(c => c.enforcedConstraintCount > 0)
                    .map(c => c.name)}
                />
              )}
            </div>

            {/* Footer */}
            <div className="flex items-center justify-end border-t border-border px-5 py-3 bg-muted/5 shrink-0">
              <button
                type="button"
                onClick={closeDataset}
                className="rounded border border-border px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-muted-foreground hover:text-foreground hover:border-foreground transition-colors"
              >
                ← Back to datasets
              </button>
            </div>
          </div>
      )}

      {confirmDialog}

      {/* Main Register Dataset Dialog */}
      <RegisterDatasetDialog
        ref={registerDialogRef}
        projectId={activeProjectId}
        repositoryId={activeRepoId}
        repository={repositories.find((r) => r.id === activeRepoId)}
        registeredNames={datasets.filter((d) => d.repositoryId === activeRepoId).map((d) => d.name)}
        onSave={(created) => {
          // Remember the bootstrap job before the list refreshes: it is what turns the
          // new PENDING row into a live one instead of one that never moves.
          if (created?.bootstrapJobId) {
            useJobsStore.getState().started(created.bootstrapJobId);
            setBootstrapJobs((prev) => ({ ...prev, [created.id]: created.bootstrapJobId! }));
          }
          // An edit passes no `created`; "registered, bootstrapping" was false for a rename.
          showToast(created ? 'Dataset registered. Bootstrapping its schema…' : 'Dataset updated.');
          fetchAllDatasetsAndRepos();
        }}
      />

      {/* ─── Bootstrapping Overlay — the job's own stages, nothing invented ───── */}
      {bootstrapLoading && !graphData && overlayTarget && (
        <div className="fixed inset-0 z-[60] flex flex-col items-center justify-center bg-black/80 backdrop-blur-md text-foreground select-none">
          <div className="max-w-sm w-full p-6 border border-violet-500/30 bg-card rounded shadow-2xl space-y-5">
            <div className="flex justify-center">
              <Network size={36} className="text-violet-400 animate-pulse" />
            </div>
            {bootstrapJobs[bootstrapLoading] ? (
              <JobCard
                jobId={bootstrapJobs[bootstrapLoading]}
                title={`aran bootstrap ${overlayTarget.name}`}
                onDone={(job) => void handleBootstrapJobDone(overlayTarget, job)}
              />
            ) : (
              <p className="font-mono text-xs text-muted-foreground text-center">
                Submitting the bootstrap job…
              </p>
            )}
          </div>
        </div>
      )}

      {/* ─── Local Graph Modal ──────────────────────────────────────────────── */}
      {graphDataset && (
        <LocalGraphModal
          dataset={graphDataset}
          graphData={graphData}
          selectedNode={graphSelectedNode}
          onNodeSelect={setGraphSelectedNode}
          instanceRef={graphInstanceRef}
          graphLoading={graphLoading}
          bootstrapLoading={!!bootstrapLoading || driftChecking}
          onReBootstrap={reBootstrapGraph}
          onClose={() => { setGraphDataset(null); setGraphData(null); graphInstanceRef.current?.destroy(); graphInstanceRef.current = null; }}
          isDark={isDark}
        />
      )}

      {/* ─── Schema Drift Review ────────────────────────────────────────────
          Sits above the graph modal: the re-bootstrap that triggered it started there, and
          closing this must return to it rather than to the list. */}
      {driftReview && (
        <div
          className="fixed inset-0 z-[60] flex items-center justify-center bg-black/85 backdrop-blur-sm p-4"
          role="presentation"
        >
          <div
            className={`w-full max-w-3xl max-h-[85vh] rounded border shadow-2xl flex flex-col overflow-hidden ${
              isDark ? 'border-orange-500/30 bg-[#0F0F16]' : 'border-border bg-card'
            }`}
          >
            <div className={`flex items-center justify-between border-b px-5 py-3 shrink-0 ${
              isDark ? 'border-orange-500/20 bg-orange-500/5' : 'border-border bg-muted/25'
            }`}>
              <h2 className="font-mono text-xs uppercase tracking-widest font-semibold flex items-center gap-2">
                <ShieldAlert size={14} className="text-orange-400" />
                schema drift — {driftReview.dataset.name}
              </h2>
              <button
                type="button"
                data-testid="drift-close"
                onClick={() => setDriftReview(null)}
                className="text-muted-foreground hover:text-foreground transition-colors"
              >
                <X size={16} />
              </button>
            </div>
            <div className="p-5 overflow-y-auto min-h-0">
              <SchemaDriftPanel
                report={driftReview.report}
                selectedRenames={selectedRenames}
                onToggleRename={(fromIri) => setSelectedRenames((prev) =>
                  prev.includes(fromIri) ? prev.filter((i) => i !== fromIri) : [...prev, fromIri])}
                onApplyAnyway={() => void applyDriftAnyway()}
                onRepairAndApply={() => void repairDriftAndApply()}
                onCancel={() => setDriftReview(null)}
                busy={driftBusy}
              />
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default DatasetsListView;
