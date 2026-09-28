import React, { useEffect, useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '@/services/api';
import type { Project, Dataset, Job, MappingKind, CubeEvidenceEdge } from '@/services/types';
import { StatusDot } from '@/components/StatusDot';
import { useIntegrationModel } from '@/hooks/useIntegrationModel';
import { useProjectsStore } from '@/stores/projectsStore';
import { useAuthStore } from '@/stores/authStore';
import {
  RefreshCw,
  Layers,
  Database,
  AlertCircle,
  Check
} from 'lucide-react';
import { errorMessage } from '@/lib/errors';
import { emptyListClaim, loadProjectDatasets } from '@/lib/listLoads';
import { modelStatus } from '@/lib/modelStatus';
import { describePromoteFailure, promoteNeedsRecheck } from '@/lib/promoteOutcome';
import type { SourceFailure } from '@/lib/fanOut';
import { ErrorNotice } from '@/components/ErrorNotice';
import { LoadFailures } from '@/components/LoadFailures';
import { SchemaGraphCanvas } from '@/components/SchemaGraphCanvas';
import { SourceEnrollmentPanel } from '@/components/alignments/SourceEnrollmentPanel';
import { AlignmentCreator } from '@/components/alignments/AlignmentCreator';
import { MappingPanel } from '@/components/alignments/MappingPanel';
import { JoinDiscoveryModal } from '@/components/alignments/JoinDiscoveryModal';
import { CompilePanel } from '@/components/alignments/CompilePanel';
import { PipelineStepper } from '@/components/alignments/PipelineStepper';
import { useJobsStore } from '@/stores/jobsStore';
import { isLive, isModelJob, latestJob, shouldReattach } from '@/lib/jobs';
import { parseDiscoveryResult, type JoinDiscoveryRun } from '@/lib/joinDiscovery';
import { alignedJoinLabel, discoveryKey, packJoinLabel } from '@/lib/joinDiscovery';
import { alignmentKindsFor, mappingsNamedIn } from '@/lib/alignmentKinds';
import { PromoteConfirmDialog } from '@/components/PromoteConfirmDialog';
import { useConfirm } from '@/components/ui/useConfirm';
import { localName } from '@/lib/iri';
import { compileBacklog, standingContext } from '@/lib/alignmentStanding';
import type { SchemaNode, GraphData, JoinCandidate, IntegrationResult, CandidateOutcome } from '@/components/alignments/types';
import { isSendable, refusalOutcome } from '@/components/alignments/candidateAccept';
import { compileToReattach } from '@/components/alignments/compileReattach';
import { DatasetPickHint, type DatasetPickState } from '@/components/alignments/DatasetPickHint';

export const AlignmentsView: React.FC = () => {
  const navigate = useNavigate();
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null);

  // The project's durable integration model — the single source of truth for
  // enrolled sources, alignments (accepted + suggestion queue) and constraints.
  // Nothing below keeps a private copy of any of it in React state.
  const { bundle, loading: modelLoading, error: modelError, refresh: refreshModel, actions,
    hasBaseline, canIncrement, staleBaseline } = useIntegrationModel(selectedProjectId);
  const [confirm, confirmDialog] = useConfirm();
  const currentUserId = useAuthStore((s) => s.me?.userId ?? null);

  const acceptedMappings = bundle?.mappings.filter((m) => m.status === 'ACCEPTED') ?? [];
  const suggestedMappings = bundle?.mappings.filter((m) => m.status === 'SUGGESTED') ?? [];
  /** Distinct enrolled dataset ids (one dataset can seed several source classes). */
  const enrolledDatasetIds = Array.from(new Set((bundle?.sources ?? []).map((s) => s.datasetId)));
  const isEnrolled = (datasetId: string | null) =>
    !!datasetId && enrolledDatasetIds.includes(datasetId);

  const [datasets, setDatasets] = useState<Array<Dataset & { repoName: string }>>([]);
  // J4-F24: the project's dataset list has a load state of its own — a failure used to leave
  // "0 datasets found" on screen and the user thinking the uploads were gone.
  const [projectsError, setProjectsError] = useState<unknown>(null);
  const [datasetsStatus, setDatasetsStatus] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [datasetsError, setDatasetsError] = useState<unknown>(null);
  const [datasetFailures, setDatasetFailures] = useState<SourceFailure[]>([]);
  const [datasetRepositoryCount, setDatasetRepositoryCount] = useState(0);
  // The project a dataset response is for: switching projects mid-load must not paint the
  // previous project's datasets under the new one's name.
  const datasetsForProject = useRef<string | null>(null);
  // What the schema panes may say with no dataset picked: "no datasets yet" only for a complete,
  // empty load — never after some of the project's repositories failed to be read (review of UA2).
  const datasetPickState: DatasetPickState = datasets.length > 0 || !selectedProjectId
    ? 'pick'
    : emptyListClaim({
        loading: datasetsStatus === 'loading' || datasetsStatus === 'idle',
        error: datasetsStatus === 'error' ? (datasetsError ?? 'error') : null,
        failures: datasetFailures,
        loaded: 0,
      });
  const headerModelStatus = modelStatus({
    projectSelected: !!selectedProjectId, loading: modelLoading, error: modelError, loaded: bundle !== null,
  });

  const [leftDatasetId, setLeftDatasetId] = useState<string | null>(null);
  const [rightDatasetId, setRightDatasetId] = useState<string | null>(null);

  const [leftGraph, setLeftGraph] = useState<GraphData | null>(null);
  const [rightGraph, setRightGraph] = useState<GraphData | null>(null);

  const [leftGraphLoading, setLeftGraphLoading] = useState(false);
  const [rightGraphLoading, setRightGraphLoading] = useState(false);

  const [leftGraphError, setLeftGraphError] = useState<string | null>(null);
  const [rightGraphError, setRightGraphError] = useState<string | null>(null);

  const [leftSelectedNode, setLeftSelectedNode] = useState<SchemaNode | null>(null);
  const [rightSelectedNode, setRightSelectedNode] = useState<SchemaNode | null>(null);

  const [alignmentLabel, setAlignmentLabel] = useState('');
  const [alignmentType, setAlignmentType] = useState<MappingKind>('class');
  const [relationshipName, setRelationshipName] = useState('');
  const [relationshipDirection, setRelationshipDirection] = useState<'forward' | 'backward'>('forward');

  const [leftBootstrapping, setLeftBootstrapping] = useState(false);
  const [rightBootstrapping, setRightBootstrapping] = useState(false);
  /** The live job stream per side, so leaving the view does not leave one open. */
  const bootstrapWatchers = useRef<{ left: (() => void) | null; right: (() => void) | null }>({
    left: null,
    right: null,
  });
  useEffect(() => () => {
    bootstrapWatchers.current.left?.();
    bootstrapWatchers.current.right?.();
  }, []);

  const [integrating, setIntegrating] = useState(false);
  /** The compile job to watch; drives the <JobCard> in the integrate panel. */
  const [compileJobId, setCompileJobId] = useState<string | null>(null);
  const [integrationResult, setIntegrationResult] = useState<IntegrationResult | null>(null);
  /** Which enrolled dataset the incremental add should append. */
  const [incrementalDatasetId, setIncrementalDatasetId] = useState<string>('');
  /** Which flavour of job is in flight, so the result panel can label itself correctly. */
  const [pendingMode, setPendingMode] = useState<'rebuild' | 'incremental'>('rebuild');
  /** Compile jobs this page has seen end, so a bundle read in the meantime cannot re-attach one. */
  const settledCompileJobs = useRef<Set<string>>(new Set());

  // A reload mid-compile re-attaches to the compile still running (review UA5, J4-F5) instead of
  // re-enabling Rebuild over it. The bundle names it; the job card then watches it to the end.
  const bundleCompileJobId = bundle?.compileJob?.jobId ?? null;
  useEffect(() => {
    const next = compileToReattach(bundle, {
      projectId: selectedProjectId,
      compileJobId,
      submitting: integrating,
      settledJobIds: settledCompileJobs.current,
    });
    if (!next) return;
    // Adopting a job the server reports is synchronising with an external system — an effect's job.
    setPendingMode(next.mode);
    setCompileJobId(next.jobId);
    // `bundle` is read through bundleCompileJobId: only a different running job should re-run this.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bundleCompileJobId, selectedProjectId, compileJobId, integrating]);

  /**
   * Enrolled sources NOT yet in the active integrated graph — the only legal incremental adds.
   * `integratedDatasetIds` is computed server-side from the graph, mirroring the operator's
   * SOURCE_ALREADY_INTEGRATED guard. (An earlier attempt derived this from the query catalog;
   * that is wrong — the catalog omits sources integrated without any accepted alignment, so
   * already-integrated sources stayed in the picker and produced doomed jobs.)
   */
  const addableDatasetIds = enrolledDatasetIds.filter(
    (id) => !(bundle?.integratedDatasetIds ?? []).includes(id));
  const [integrationError, setIntegrationError] = useState<string | null>(null);

  // A rebuild or incremental add still running when this page was left or reloaded is picked up
  // again from the jobs store (UI audit J8-F7): its card and stages come back, and Rebuild stays
  // disabled, instead of the page offering to start a second one over the first.
  const liveModelJob = useJobsStore((s) => (selectedProjectId
    ? latestJob(Object.values(s.jobs), (j) => isModelJob(j, selectedProjectId) && isLive(j))
    : null));
  // The job the panel holds, as the store last saw it — ended, it yields to a newer live one.
  const heldModelJob = useJobsStore((s) => (compileJobId ? s.jobs[compileJobId] : undefined));
  useEffect(() => {
    if (!liveModelJob || !shouldReattach(heldModelJob, compileJobId, liveModelJob)) return;
    // The newer run supersedes whatever the panel said about the last one. Set here, in an
    // effect, because the store that announces the run is external to React.
    /* eslint-disable react-hooks/set-state-in-effect */
    setIntegrationError(null);
    setIntegrationResult(null);
    setPendingMode(liveModelJob.jobType === 'model:incremental-integrate' ? 'incremental' : 'rebuild');
    setCompileJobId(liveModelJob.id);
    /* eslint-enable react-hooks/set-state-in-effect */
    // Keyed on the live job's id and the held job's state: "is a different one running now?".
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [liveModelJob?.id, compileJobId, heldModelJob?.status]);


  const [integratedGraph, setIntegratedGraph] = useState<GraphData | null>(null);
  const [integratedGraphLoading, setIntegratedGraphLoading] = useState(false);
  const [integratedGraphError, setIntegratedGraphError] = useState<string | null>(null);
  const [integratedSelectedNode, setIntegratedSelectedNode] = useState<SchemaNode | null>(null);
  const [promoting, setPromoting] = useState(false);
  const [promoted, setPromoted] = useState(false);
  /** The promote confirmation (J4-F1): open while the user reads the diff; the refusal lands in it. */
  const [promoteOpen, setPromoteOpen] = useState(false);
  const [promoteError, setPromoteError] = useState<string | null>(null);
  /** A failed un-enrol, said in the enrolment strip (J4-F14). */
  const [removeSourceError, setRemoveSourceError] = useState<string | null>(null);

  // The ACTIVE catalog's join edges with their trust tiers (J4-F17). Re-read whenever the active
  // version changes — a promote can raise or retire a join's tier.
  const [joinEdges, setJoinEdges] = useState<CubeEvidenceEdge[] | null>(null);
  const [joinEdgesError, setJoinEdgesError] = useState<string | null>(null);
  const activeVersionId = bundle?.activeVersion?.id ?? null;
  useEffect(() => {
    if (!selectedProjectId) return;
    let cancelled = false;
    api.queryEngineEvidence()
      .then((ev) => {
        if (cancelled) return;
        setJoinEdges(ev.edges);
        setJoinEdgesError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setJoinEdges(null);
        setJoinEdgesError(errorMessage(err, 'unavailable'));
      });
    return () => { cancelled = true; };
  }, [selectedProjectId, activeVersionId]);
  // A promote that is neither confirmed nor failed (J4-F11), with what "Check again" re-decides from.
  const [promoteCheck, setPromoteCheck] = useState<
    { err: unknown; runId: string; message: string; checking: boolean } | null
  >(null);

  // Property-alignment suggestion is now server-persisted: running it seeds
  // SUGGESTED rows straight into the model's review queue below. No transient
  // candidate list is held here — the queue is whatever the model returns.
  const [suggesting, setSuggesting] = useState(false);
  const [suggestionError, setSuggestionError] = useState<string | null>(null);
  // What the last run did, kept on screen (J3-F17): an unchanged queue is not an answer.
  const [suggestionNote, setSuggestionNote] = useState<string | null>(null);

  const handleSuggest = async () => {
    if (!leftDatasetId || !rightDatasetId) return;
    setSuggesting(true);
    setSuggestionError(null);
    setSuggestionNote(null);
    try {
      const outcome = await actions.suggest(leftDatasetId, rightDatasetId);
      setSuggestionNote(outcome?.sentence ?? null);
    } catch (err: unknown) {
      setSuggestionError(errorMessage(err, 'Suggestion failed'));
    } finally {
      setSuggesting(false);
    }
  };

  /** Accept a suggestion from the review queue → the mapping becomes ACCEPTED. */
  const handleAcceptMapping = async (mappingId: string) => {
    try {
      await actions.accept(mappingId);
    } catch (err: unknown) {
      setSuggestionError(errorMessage(err, 'Failed to accept mapping'));
    }
  };

  /** Reject a suggestion from the review queue → the mapping becomes REJECTED. */
  const handleRejectMapping = async (mappingId: string) => {
    try {
      await actions.reject(mappingId);
    } catch (err: unknown) {
      setSuggestionError(errorMessage(err, 'Failed to reject mapping'));
    }
  };

  const [showDiscovery, setShowDiscovery] = useState(false);
  const [discoveryThreshold, setDiscoveryThreshold] = useState(0.0);
  const [discoveryMethod, setDiscoveryMethod] = useState<'ml' | 'exact'>('exact');
  const [discoveryStrictness, setDiscoveryStrictness] = useState(0.25);
  const [discovering, setDiscovering] = useState(false);
  const [discoveryError, setDiscoveryError] = useState<string | null>(null);
  // The whole settled result, not just its candidates: an empty list means three different
  // things (compared and found nothing, could not score, nothing profiled) and only the
  // counts beside it tell them apart (J3-F5).
  const [discoveryRun, setDiscoveryRun] = useState<JoinDiscoveryRun | null>(null);
  const [settledDiscoveryRunId, setSettledDiscoveryRunId] = useState<number | null>(null);
  /**
   * The run is shown only under the key of the pair and settings it was computed for (J3-F3):
   * switching a side used to leave the old pair's candidates listed for the new one, and
   * accepting one joined the old pair.
   */
  const [discoveryRunKey, setDiscoveryRunKey] = useState<string | null>(null);
  const currentDiscoveryKey = discoveryKey({
    projectId: selectedProjectId, left: leftDatasetId, right: rightDatasetId,
    method: discoveryMethod, strictness: discoveryStrictness, threshold: discoveryThreshold,
  });
  const discoveryRanForCurrent = discoveryRun !== null && discoveryRunKey === currentDiscoveryKey;
  const shownDiscoveryRun = discoveryRanForCurrent ? discoveryRun : null;
  const discoveryCandidates: JoinCandidate[] = shownDiscoveryRun?.candidates ?? [];
  const [selectedCandidates, setSelectedCandidates] = useState<Record<number, boolean>>({});
  const [acceptingCandidates, setAcceptingCandidates] = useState(false);
  const [candidateOutcomes, setCandidateOutcomes] = useState<Record<number, CandidateOutcome>>({});
  /** A refused candidate's edited label, by index (review UA5): a 4xx is re-sent only relabelled. */
  const [candidateLabels, setCandidateLabels] = useState<Record<number, string>>({});

  /** Which discovery run may still land: a new pair or a new run makes the others stale. */
  const discoveryRunIdRef = useRef(0);
  const dismissedDiscoveryRunIdRef = useRef<number | null>(null);

  /** A new pair invalidates the last pair's candidates: they name the other datasets' columns. */
  const resetDiscovery = () => {
    // A run still in flight names the old pair; it must not land here, nor keep the page busy.
    discoveryRunIdRef.current++;
    setDiscovering(false);
    setDiscoveryRun(null);
    setDiscoveryRunKey(null);
    setSelectedCandidates({});
    setCandidateOutcomes({});
    setCandidateLabels({});
    setDiscoveryError(null);
    setShowDiscovery(false);
  };

  // Join discovery runs only on an explicit action — "Auto-Infer Join Alignments" or the modal's
  // own Run (UI-OD7, audit J3-F12). It used to start on pair selection and cover the controls with
  // its modal: opening Alignments only to look cost two sidecar calls per column.
  const handleRunDiscovery = async () => {
    if (!leftDatasetId || !rightDatasetId) return;

    const runId = ++discoveryRunIdRef.current;
    const settings = { runId, threshold: discoveryThreshold, method: discoveryMethod };
    setDiscovering(true);
    setDiscoveryError(null);
    const runKey = currentDiscoveryKey;
    setDiscoveryRun(null);
    setDiscoveryRunKey(null);
    setSelectedCandidates({});
    setCandidateOutcomes({});
    setCandidateLabels({});

    try {
      const res = await api.runOperatorJob('join-discovery', {
        operation: 'discover',
        parameters: {
          datasetIdA: leftDatasetId,
          datasetIdB: rightDatasetId,
          threshold: String(discoveryThreshold),
          method: discoveryMethod,
          strictness: String(discoveryStrictness)
        }
      });
      // A newer run owns the state; a superseded response (the pair-selection auto-run
      // finishing after an explicit Run) must not overwrite it or end its spinner.
      if (runId !== discoveryRunIdRef.current) return;

      if (res.success && res.data && res.data.candidates) {
        // Stored under the key it was run for: if the pair changed while it ran, it is never shown for the new one.
        setDiscoveryRun(parseDiscoveryResult(res.data, settings));
        setDiscoveryRunKey(runKey);
        setSelectedCandidates({});
      } else {
        setDiscoveryError(res.errorMessage || 'Discovery failed to return candidates');
      }
    } catch (err: unknown) {
      if (runId !== discoveryRunIdRef.current) return;
      console.error('Join discovery error', err);
      setDiscoveryError(errorMessage(err, 'An unexpected error occurred during discovery.'));
    } finally {
      if (runId === discoveryRunIdRef.current) {
        setDiscovering(false);
        setSettledDiscoveryRunId(runId);
        // Skip the auto-open if the user has already dismissed this specific run —
        // otherwise the modal slams back open over whatever they've since started doing.
        if (dismissedDiscoveryRunIdRef.current !== runId) {
          setShowDiscovery(true);
        }
      }
    }
  };

  /**
   * Why a candidate cannot be added: the model already holds a join between the same two
   * properties (an add is idempotent on them, so it would silently return that row instead).
   */
  const candidateUnavailable: Record<number, string> = {};
  discoveryCandidates.forEach((cand, idx) => {
    // Either direction (J3-F16): a reversed duplicate used to create a second, reversed join.
    const existing = (bundle?.mappings ?? []).find(
      (m) => m.kind === 'join'
        && ((m.sourceIri === cand.iriA && m.targetIri === cand.iriB) || (m.sourceIri === cand.iriB && m.targetIri === cand.iriA)));
    if (existing && candidateOutcomes[idx]?.state !== 'added') {
      candidateUnavailable[idx] = existing.status === 'ACCEPTED'
        ? `already aligned as ${alignedJoinLabel(cand, bundle?.mappings ?? []) ?? 'an existing join'}`
        : existing.status === 'SUGGESTED' ? 'already in the review queue' : 'rejected earlier';
    }
  });

  /**
   * Add the selected candidates, one request each, and say what happened to each (J3-F4 / J4-F9).
   * The modal used to close as if every add had succeeded and surface a refusal later as
   * "Discovery failed". It now closes only when everything sent was added; otherwise it stays open
   * with the reason on each refused row, and Accept retries only those.
   */
  /** `labels[idx]` carries the relationship name and direction the modal packed for row idx (J3-F14). */
  const handleAcceptCandidates = async (labels: Record<number, string> = {}) => {
    if (!selectedProjectId || acceptingCandidates) return;
    setAcceptingCandidates(true);
    const outcomes: Record<number, CandidateOutcome> = { ...candidateOutcomes };
    let failed = 0;
    for (let idx = 0; idx < discoveryCandidates.length; idx++) {
      const cand = discoveryCandidates[idx];
      const label = (labels[idx] ?? candidateLabels[idx] ?? cand.label).trim();
      if (!isSendable({
        selected: !!selectedCandidates[idx], unavailable: candidateUnavailable[idx], outcome: outcomes[idx], label,
      })) {
        if (outcomes[idx]?.state === 'failed') failed++;
        continue;
      }
      try {
        await api.addMapping(selectedProjectId, {
          kind: 'join',
          sourceIri: cand.iriA,
          targetIri: cand.iriB,
          label,
        });
        outcomes[idx] = { state: 'added' };
      } catch (err: unknown) {
        outcomes[idx] = refusalOutcome(err, label);
        failed++;
      }
    }
    setCandidateOutcomes(outcomes);
    await refreshModel();
    setAcceptingCandidates(false);
    if (failed === 0) {
      setShowDiscovery(false);
    }
  };

  const handleProjectChange = async (projectId: string) => {
    setSelectedProjectId(projectId);
    // Keep the global context in step so Products/breadcrumbs follow this choice.
    useProjectsStore.getState().setActiveProject(projectId || null);
    setLeftDatasetId(null);
    setRightDatasetId(null);
    setLeftGraph(null);
    setRightGraph(null);
    setLeftSelectedNode(null);
    setRightSelectedNode(null);
    setIntegrationResult(null);
    setIntegrationError(null);
    setPromoteCheck(null);
    setCompileJobId(null);
    setRemoveSourceError(null);
    resetDiscovery();

    datasetsForProject.current = projectId || null;
    setDatasetFailures([]);
    setDatasetsError(null);

    if (!projectId) {
      setDatasets([]);
      setDatasetsStatus('idle');
      return;
    }

    setDatasets([]);
    setDatasetsStatus('loading');
    const result = await loadProjectDatasets(
      projectId,
      (id) => api.listRepositories(id),
      (id, repositoryId) => api.listDatasets(id, repositoryId),
    );
    if (datasetsForProject.current !== projectId) return; // the user has moved on
    setDatasets(result.datasets);
    setDatasetFailures(result.failures);
    setDatasetRepositoryCount(result.repositoryCount);
    setDatasetsError(result.error);
    setDatasetsStatus(result.error ? 'error' : 'ready');
  };

  const loadProjects = () => {
    setProjectsError(null);
    api.listProjects()
      .then((data) => {
        setProjects(data);
        if (data.length > 0) {
          // Prefer the workspace-wide selection (store, persisted) over "first project":
          // the same context the user last worked in on any page follows them here.
          const active = useProjectsStore.getState().activeProjectId;
          const initial = active && data.some((p) => p.id === active) ? active : data[0].id;
          handleProjectChange(initial);
        }
      })
      .catch((err: unknown) => setProjectsError(err));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadProjects();
    // Runs once on mount; loadProjects is re-created every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadLeftGraph = async (datasetId: string) => {
    setLeftGraphLoading(true);
    setLeftGraphError(null);
    setLeftSelectedNode(null);
    try {
      const graph = await api.fetchSourceGraph(datasetId);
      if (graph.empty || !graph.nodes || graph.nodes.length === 0) {
        setLeftGraph(null);
      } else {
        setLeftGraph(graph);
      }
    } catch (err: unknown) {
      console.error('Failed to load left graph', err);
      setLeftGraphError(errorMessage(err, 'Failed to load graph'));
      setLeftGraph(null);
    } finally {
      setLeftGraphLoading(false);
    }
  };

  const loadRightGraph = async (datasetId: string) => {
    setRightGraphLoading(true);
    setRightGraphError(null);
    setRightSelectedNode(null);
    try {
      const graph = await api.fetchSourceGraph(datasetId);
      if (graph.empty || !graph.nodes || graph.nodes.length === 0) {
        setRightGraph(null);
      } else {
        setRightGraph(graph);
      }
    } catch (err: unknown) {
      console.error('Failed to load right graph', err);
      setRightGraphError(errorMessage(err, 'Failed to load graph'));
      setRightGraph(null);
    } finally {
      setRightGraphLoading(false);
    }
  };

  /**
   * Selecting a dataset enrols it in the project's model (if not already in).
   * Best-effort: a dataset that has not been bootstrapped yet cannot be enrolled
   * (the server returns 404), so we swallow that — enrolment is retried once the
   * bootstrap job succeeds. Never touches mappings: they belong to the project,
   * not the selected pair.
   */
  const maybeEnroll = async (datasetId: string) => {
    if (!datasetId || !selectedProjectId) return;
    if (isEnrolled(datasetId)) return;
    try {
      await actions.addSource(datasetId);
    } catch {
      // Not bootstrapped yet — enrolment happens after the bootstrap job succeeds.
    }
  };

  const datasetName = (datasetId: string) =>
    datasets.find((d) => d.id === datasetId)?.name ?? datasetId.substring(0, 8);

  /**
   * Un-enrol a dataset (J4-F14): the small × used to act at once, with no confirmation, no undo
   * and its error shown in another panel. It now names what goes with it — the edited wrappers
   * are deleted with the enrolment rows, and the next promoted rebuild drops the classes only it
   * feeds — and a failure is said in the strip itself.
   */
  const handleRemoveSource = async (datasetId: string) => {
    const name = datasetName(datasetId);
    const rows = (bundle?.sources ?? []).filter((s) => s.datasetId === datasetId);
    const editedWrappers = rows.filter((s) => s.wrapperEdited).length;
    const prefix = `http://www.essi.upc.edu/DTIM/NextiaDI/source/${datasetId}/`;
    const alignments = (bundle?.mappings ?? []).filter((m) => m.status === 'ACCEPTED'
      && (m.sourceIri.startsWith(prefix) || m.targetIri.startsWith(prefix))).length;
    const ok = await confirm({
      title: `Remove ${name} from this model?`,
      confirmLabel: 'Remove source',
      testId: 'align-unenrol',
      consequences: [
        `Its ${rows.length} source class${rows.length === 1 ? '' : 'es'} leave this model\u2019s next compile; the dataset itself is kept.`,
        ...(editedWrappers > 0
          ? [`${editedWrappers} edited wrapper${editedWrappers === 1 ? ' is' : 's are'} discarded — re-enrolling seeds the generated one again.`]
          : []),
        ...(alignments > 0
          ? [`${alignments} accepted alignment${alignments === 1 ? '' : 's'} that use${alignments === 1 ? 's' : ''} it ${alignments === 1 ? 'stays' : 'stay'}, but the next compile reports ${alignments === 1 ? 'it' : 'them'} as unused.`]
          : []),
        'The active schema is unchanged until the next rebuild is promoted, which drops the classes only this dataset feeds; after a promote, that full rebuild is required before any incremental add.',
      ],
    });
    if (!ok) return;
    setRemoveSourceError(null);
    try {
      await actions.removeSource(datasetId);
    } catch (err: unknown) {
      setRemoveSourceError(`Could not remove ${name}: ${errorMessage(err, 'unknown error')}`);
    }
  };

  const handleLeftDatasetChange = (datasetId: string) => {
    resetDiscovery();
    setLeftDatasetId(datasetId || null);
    setLeftSelectedNode(null);
    setLeftGraph(null);
    setLeftGraphError(null);
    if (datasetId) {
      loadLeftGraph(datasetId);
      void maybeEnroll(datasetId);
    }
  };

  const handleRightDatasetChange = (datasetId: string) => {
    resetDiscovery();
    setRightDatasetId(datasetId || null);
    setRightSelectedNode(null);
    setRightGraph(null);
    setRightGraphError(null);
    if (datasetId) {
      loadRightGraph(datasetId);
      void maybeEnroll(datasetId);
    }
  };

  /**
   * Self-healing bootstrap.
   *
   * The graph is loaded when the job says it was written — not 1.2 seconds after
   * submission, which was a guess that reported success on a bootstrap still
   * running (or already dead) just as readily as on one that finished.
   */
  const handleBootstrap = async (datasetId: string, side: 'left' | 'right') => {
    const setBootstrapping = side === 'left' ? setLeftBootstrapping : setRightBootstrapping;
    const setGraphError = side === 'left' ? setLeftGraphError : setRightGraphError;
    const loadGraph = side === 'left' ? loadLeftGraph : loadRightGraph;

    setBootstrapping(true);
    setGraphError(null);
    // A second bootstrap on this side replaces the first; the old stream is closed
    // rather than left writing into a component that has moved on.
    bootstrapWatchers.current[side]?.();
    bootstrapWatchers.current[side] = null;

    try {
      const { jobId } = await api.bootstrapDataset(datasetId);
      bootstrapWatchers.current[side] = api.jobEvents(jobId, (job) => {
        if (job.status === 'SUCCEEDED') {
          setBootstrapping(false);
          void loadGraph(datasetId);
          // Now that a source graph exists, enrol the dataset in the model.
          void maybeEnroll(datasetId);
        } else if (job.status === 'FAILED' || job.status === 'CANCELLED') {
          setBootstrapping(false);
          setGraphError(job.errorMessage ?? 'Bootstrapping failed without a message.');
        }
      });
    } catch (err: unknown) {
      console.error('Bootstrapping failed', err);
      setGraphError(errorMessage(err, 'Bootstrapping process encountered an error'));
      setBootstrapping(false);
    }
  };

  // Auto-calculation of alignment configurations based on left and right selected nodes.
  // Seeds alignmentLabel/alignmentType as an editable default, not a derived value — the
  // label input stays user-editable afterward, so this can't be computed during render.
  useEffect(() => {
    if (leftSelectedNode && rightSelectedNode) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setAlignmentLabel(`${leftSelectedNode.label || leftSelectedNode.id}_${rightSelectedNode.label || rightSelectedNode.id}`);

      // The first kind the pair admits (J4-F6). A pair that admits none keeps whatever was
      // chosen; the creator disables Add and says why.
      const first = alignmentKindsFor(leftSelectedNode.type, rightSelectedNode.type).kinds[0];
      if (first) setAlignmentType(first);
    } else {
      setAlignmentLabel('');
    }
  }, [leftSelectedNode, rightSelectedNode]);

  const [addMappingError, setAddMappingError] = useState<string | null>(null);

  const handleAddAlignment = async () => {
    if (!leftSelectedNode || !rightSelectedNode || !alignmentLabel.trim() || !selectedProjectId) return;

    // Check duplication against the project's mappings (any status).
    const duplicate = (bundle?.mappings ?? []).some(
      (m) => m.kind === alignmentType
        && m.sourceIri === leftSelectedNode.iri
        && m.targetIri === rightSelectedNode.iri
    );
    if (duplicate) {
      setAddMappingError('An alignment between these exact elements already exists.');
      return;
    }

    // Same packed-label construction the view has always used; only the sink
    // changed — it is POSTed to the model as an ACCEPTED mapping, not held locally.
    const formattedLabel = alignmentType === 'join'
      ? packJoinLabel(alignmentLabel.trim(), relationshipName.trim(), relationshipDirection)
      : alignmentLabel.trim();

    setAddMappingError(null);
    try {
      await actions.addMapping({
        kind: alignmentType,
        sourceIri: leftSelectedNode.iri,
        targetIri: rightSelectedNode.iri,
        label: formattedLabel,
      });
      setLeftSelectedNode(null);
      setRightSelectedNode(null);
      setRelationshipName('');
      setRelationshipDirection('forward');
    } catch (err: unknown) {
      setAddMappingError(errorMessage(err, 'Failed to add alignment'));
    }
  };

  const handleRemoveMapping = async (mappingId: string) => {
    const mapping = (bundle?.mappings ?? []).find((m) => m.id === mappingId);
    if (mapping) {
      // deleteMapping flags a rebuild exactly when the mapping is APPLIED (isApplied), in any version.
      const ok = await confirm({
        title: `Delete the alignment ${localName(mapping.sourceIri)} \u2194 ${localName(mapping.targetIri)}?`,
        confirmLabel: 'Delete alignment',
        testId: 'align-delete',
        consequences: [
          'It is removed from this model; re-adding it is a new alignment.',
          mapping.application?.state === 'APPLIED'
            ? 'It is compiled: the next add must be a full rebuild, and the active schema keeps what it merged until that rebuild is promoted.'
            : 'It is not in the active schema, so nothing live changes.',
        ],
      });
      if (!ok) return;
    }
    try {
      await actions.remove(mappingId);
    } catch (err: unknown) {
      setAddMappingError(errorMessage(err, 'Failed to delete alignment'));
    }
  };

  /**
   * Integration is now `compile(model)`: it compiles ALL enrolled sources (N-ary),
   * not just the visible pair, and runs on the job substrate. We submit and hand the
   * job id to a <JobCard>; the result lands via {@link handleCompileDone}.
   */
  const handleIntegrate = async () => {
    if (!selectedProjectId) return;

    setIntegrating(true);
    setIntegrationError(null);
    setPromoteCheck(null);
    setIntegrationResult(null);
    setCompileJobId(null);

    try {
      setPendingMode('rebuild');
      const jobId = await actions.compile();
      setCompileJobId(jobId);
    } catch (err: unknown) {
      console.error('Rebuild submission error', err);
      setIntegrationError(errorMessage(err, 'Failed to start integration.'));
    } finally {
      setIntegrating(false);
    }
  };

  /**
   * Add ONE enrolled source on top of the promoted baseline, without re-reading the others.
   * Only legal once a baseline exists and nothing has invalidated it; the button is disabled
   * otherwise so the server's NO_BASELINE / REBUILD_REQUIRED rejections stay unreachable.
   */
  const handleIncrementalAdd = async () => {
    if (!selectedProjectId || !incrementalDatasetId) return;

    setIntegrating(true);
    setIntegrationError(null);
    setPromoteCheck(null);
    setIntegrationResult(null);
    setCompileJobId(null);

    try {
      setPendingMode('incremental');
      const jobId = await actions.incrementalIntegrate(incrementalDatasetId);
      setCompileJobId(jobId);
    } catch (err: unknown) {
      console.error('Incremental submission error', err);
      setIntegrationError(errorMessage(err, 'Failed to start incremental integration.'));
    } finally {
      setIntegrating(false);
    }
  };

  /** When the integration job settles, lift its result map into the persistent panel. */
  const handleCompileDone = (job: Job) => {
    // Whatever the outcome, the job is over: leaving compileJobId set kept Rebuild and
    // incremental-add disabled forever after one compile, so every accept-then-recompile
    // iteration required leaving the page (redesign brief, alignments HIGH #2).
    settledCompileJobs.current.add(job.id);
    setCompileJobId(null);
    // The run's application rows and the model's pending draft moved server-side.
    void refreshModel();
    if (job.status === 'SUCCEEDED' && job.result) {
      const d = job.result;
      setIntegrationResult({
        draftVersionId: d.draftVersionId ?? '',
        draftVersionNumber: d.draftVersionNumber ?? '',
        tripleCount: d.tripleCount ?? '',
        unusedAlignments: d.unusedAlignments ?? '',
        turtle: d.turtle ?? '',
        integrationRunId: d.integrationRunId ?? '',
        mode: pendingMode,
      });
    } else if (job.status === 'FAILED' || job.status === 'CANCELLED') {
      setIntegrationError(job.errorMessage ?? 'Integration job failed.');
    }
  };

  // Fetch the draft's graph — this session's, or the one the model restored after a reload.
  const previewDraftId = integrationResult?.draftVersionId
    ?? (!integrating && !compileJobId ? bundle?.latestDraftRun?.draftVersionId : undefined);
  useEffect(() => {
    if (previewDraftId) {
      // Resets stale UI state ahead of the fetchSchemaGraph call below — no
      // render-time equivalent for either.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setIntegratedGraphLoading(true);
      setIntegratedGraphError(null);
      setIntegratedGraph(null);
      setPromoted(false);
      setIntegratedSelectedNode(null);

      api.fetchSchemaGraph(previewDraftId)
        .then((g) => {
          if (g.empty || !g.nodes || g.nodes.length === 0) {
            setIntegratedGraph(null);
          } else {
            setIntegratedGraph({
              datasetId: previewDraftId,
              nodes: g.nodes.map(n => ({
                id: n.id,
                label: n.label,
                iri: n.iri,
                type: n.type
              })),
              edges: g.edges.map(e => ({
                source: e.source,
                target: e.target,
                label: e.label
              }))
            });
          }
        })
        .catch((err: unknown) => {
          console.error('Failed to load integrated schema graph', err);
          setIntegratedGraphError(errorMessage(err, 'Failed to load integrated graph'));
        })
        .finally(() => {
          setIntegratedGraphLoading(false);
        });
    } else {
      setIntegratedGraph(null);
    }
  }, [previewDraftId]);

  /**
   * Promote the draft this model run produced — through the MODEL-AWARE endpoint.
   *
   * The generic `api.promoteDraft` moves the graph pointer but leaves the run in DRAFT, never
   * sets `active_integration_run_id` and never clears `rebuildRequired`. Using it here meant the
   * model never acquired a baseline, so every incremental add would fail with NO_BASELINE. If a
   * result somehow carries no run id we surface that rather than silently falling back, because
   * the fallback is exactly the bug.
   */
  const handlePromoteDraft = () => {
    if (!displayedDraft?.draftVersionId) return;
    if (!displayedDraft.integrationRunId) {
      setIntegrationError(
        'This draft carries no integration run id, so it cannot be promoted as a model run. '
        + 'Re-run the integration and promote the new draft.');
      return;
    }
    // Never one click (J4-F1): the confirmation shows the diff against ACTIVE and says tenant-wide.
    setPromoteError(null);
    setPromoteOpen(true);
  };

  const confirmPromote = async () => {
    if (!displayedDraft?.integrationRunId) return;
    setPromoting(true);
    setPromoteError(null);
    setPromoteCheck(null);
    try {
      await actions.promoteRun(displayedDraft.integrationRunId);
      setPromoted(true);
      setPromoteOpen(false);
      // Single promotion UX: hand off to the versions view rather than duplicating
      // the promote/version affordances here (M7).
      navigate('/versions');
    } catch (err: unknown) {
      console.error('Failed to promote model run', err);
      // A promote the page stopped waiting for may still land, and a NOT_A_DRAFT usually means an
      // earlier attempt DID land (J4-F11): re-read the model and describe what it now holds rather
      // than calling a promote that succeeded "failed". Anything else lands inside the dialog the
      // user is reading: a 409 names the classes another project's model promoted (UI-OD2).
      if (promoteNeedsRecheck(err)) {
        setPromoteOpen(false);
        applyPromoteOutcome(err, displayedDraft.integrationRunId, await readActiveRunId());
      } else {
        setPromoteError('Promotion failed: ' + errorMessage(err, 'unknown error'));
      }
    } finally {
      setPromoting(false);
    }
  };

  // The model's pending draft survives a reload (J4-F2): with no draft from this session and no
  // compile in flight, show the one the server says is still waiting. Hidden while a compile runs,
  // because that compile supersedes it.
  const restoredDraft: IntegrationResult | null = !integrationResult && !integrating && !compileJobId
    && bundle?.latestDraftRun
    ? {
      draftVersionId: bundle.latestDraftRun.draftVersionId,
      draftVersionNumber: String(bundle.latestDraftRun.draftVersionNumber),
      tripleCount: '',
      unusedAlignments: '[]',
      turtle: '',
      integrationRunId: bundle.latestDraftRun.runId,
      mode: bundle.latestDraftRun.mode === 'INCREMENTAL' ? 'incremental' : 'rebuild',
      restored: true,
    }
    : null;
  const displayedDraft = integrationResult ?? restoredDraft;

  // Where the model stands, from the server (J4-F5): live = its last promote is the ACTIVE schema.
  const liveVersionNumber = hasBaseline && !staleBaseline && bundle?.activeVersion
    && bundle.baselineVersionId === bundle.activeVersion.id
    ? bundle.activeVersion.versionNumber
    : null;
  const standing = standingContext(bundle);
  const { changesToCompile, deferredCount } =
    compileBacklog(acceptedMappings, standing, !!bundle?.model.rebuildRequired);
  /** The model's active run now, null for none, or undefined when the model could not be read. */
  const readActiveRunId = async (): Promise<string | null | undefined> => {
    if (!selectedProjectId) return undefined;
    const bundleNow = await api.getModel(selectedProjectId).catch(() => null);
    void refreshModel();
    return bundleNow ? (bundleNow.model.activeIntegrationRunId ?? null) : undefined;
  };

  // A landed promote hands off as a successful one does; a failed one is an error; a promote that
  // is neither confirmed nor failed stays on the panel as a status with "Check again" — it is not
  // shown under the "failed" heading, which is where "Promotion failed: … it was already
  // promoted" came from (second review of UA2).
  const applyPromoteOutcome = (err: unknown, runId: string, activeRunIdNow: string | null | undefined) => {
    const outcome = describePromoteFailure(err, runId, activeRunIdNow);
    if (outcome.kind === 'landed') {
      setPromoteCheck(null);
      setPromoted(true);
      navigate('/versions');
    } else if (outcome.kind === 'failed') {
      setPromoteCheck(null);
      setIntegrationError(outcome.message);
    } else {
      setPromoteCheck({ err, runId, message: outcome.message, checking: false });
    }
  };

  const handleRecheckPromote = async () => {
    const pending = promoteCheck;
    if (!pending || pending.checking) return;
    setPromoteCheck({ ...pending, checking: true });
    applyPromoteOutcome(pending.err, pending.runId, await readActiveRunId());
  };

  return (
    <div className="flex-grow flex flex-col p-6 gap-4 min-h-0 select-none overflow-y-auto">
      {confirmDialog}
      {displayedDraft && (
        <PromoteConfirmDialog
          open={promoteOpen}
          draft={{ id: displayedDraft.draftVersionId, versionNumber: Number(displayedDraft.draftVersionNumber) }}
          busy={promoting}
          error={promoteError}
          onConfirm={() => void confirmPromote()}
          onCancel={() => setPromoteOpen(false)}
        />
      )}
      {/* Shell CLI Header */}
      <div className="flex flex-col md:flex-row items-stretch md:items-center justify-between gap-4 shrink-0">
        <div className="text-[15px] font-semibold text-foreground" style={{ fontFamily: 'var(--font-display)' }}>
          Alignments
          <span className="text-muted-foreground font-sans font-normal text-[13px] ml-3">Build one schema from your sources — review, compile, promote.</span>
        </div>
        <div className="flex items-center gap-2 text-[10px] font-mono text-muted-foreground/80">
          <span>{acceptedMappings.length} accepted</span>
          <span>·</span>
          <span>{suggestedMappings.length} pending</span>
          {/* "Ready" beside an empty model read as "ready to promote" (J1-F15), and "Saved" after
              a failed load was worse: this describes the model's READ, which is all it tracks. */}
          {headerModelStatus && (
            <>
              <span>·</span>
              <StatusDot variant={headerModelStatus.variant} label={headerModelStatus.label} />
            </>
          )}
        </div>
      </div>

      {/* Control bar: Project Selector */}
      <div className="flex flex-col md:flex-row gap-4 items-start md:items-center bg-card border border-border p-4 rounded-lg shrink-0 justify-between">
        <div className="flex items-center gap-3">
          <span className="font-mono text-xs font-semibold text-muted-foreground uppercase tracking-wider">Project:</span>
          <select
            data-testid="align-project-select"
            value={selectedProjectId || ''}
            onChange={(e) => handleProjectChange(e.target.value)}
            className="h-9 px-3 rounded-md border border-border bg-muted/40 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-primary min-w-[200px]"
          >
            <option value="">-- select a project --</option>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
          {selectedProjectId && datasetsStatus === 'loading' && (
            <span className="text-[10px] font-mono text-muted-foreground/60">loading datasets…</span>
          )}
          {selectedProjectId && datasetsStatus === 'ready' && (
            <span className="text-[10px] font-mono text-muted-foreground/60">
              {datasets.length} datasets{datasetFailures.length > 0 ? ' loaded' : ''}
            </span>
          )}
        </div>
      </div>

      {projectsError != null && (
        <ErrorNotice compact testId="align-projects-load-error" title="Couldn't load your projects"
          error={projectsError} onRetry={loadProjects} />
      )}
      {selectedProjectId && datasetsStatus === 'error' && (
        <ErrorNotice compact testId="align-datasets-load-error" title="Couldn't load this project's datasets"
          error={datasetsError} onRetry={() => void handleProjectChange(selectedProjectId)} />
      )}
      {selectedProjectId && datasetsStatus === 'ready' && (
        <LoadFailures failures={datasetFailures} total={datasetRepositoryCount} sourceNoun="repository"
          onRetry={() => void handleProjectChange(selectedProjectId)} testId="align-datasets-partial-error" />
      )}

      {/* The pipeline as the page's spine: where the user stands, computed from the
          same state the panels below already own. */}
      {selectedProjectId && (
        <PipelineStepper
          enrolledCount={enrolledDatasetIds.length}
          acceptedCount={acceptedMappings.length}
          pendingCount={suggestedMappings.length}
          compiling={integrating || !!compileJobId}
          draftVersionNumber={displayedDraft ? Number(displayedDraft.draftVersionNumber) : null}
          liveVersionNumber={liveVersionNumber}
          changesToCompile={changesToCompile}
          deferredCount={deferredCount}
          onStepClick={(i) => {
            const id = ['stage-enroll', 'stage-align', 'stage-review', 'stage-compile', 'stage-compile'][i];
            document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }}
        />
      )}

      {/* Enrolled sources strip — the model is N-ary; the two canvases below show a
          pair at a time, but every enrolled source is compiled on Integrate. */}
      {selectedProjectId && (
        <section id="stage-enroll" className="flex flex-col gap-2 shrink-0 scroll-mt-4">
          <div className="text-[12.5px] font-semibold text-muted-foreground/80 select-none">
            1 · Enroll — the sources this model compiles
          </div>
          <SourceEnrollmentPanel
            enrolledDatasetIds={enrolledDatasetIds}
            datasetName={datasetName}
            onRemoveSource={(id) => void handleRemoveSource(id)}
            modelError={modelError}
            removeError={removeSourceError}
          />
        </section>
      )}

      {/* Stage 2 — browse a pair of schemas and create alignments */}
      <section id="stage-align" className="flex flex-col gap-2 shrink-0 scroll-mt-4">
      <div className="text-[12.5px] font-semibold text-muted-foreground/80 select-none">
        2 · Align — browse two schemas, pick nodes, create or discover alignments
      </div>
      {/* Side-by-Side Panels Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 shrink-0">
        
        {/* Left Dataset Panel */}
        <div className="flex flex-col rounded-md border border-border bg-card overflow-hidden h-[380px] min-h-0">
          
          {/* TOP SELECT BAR */}
          <div className="p-3 border-b border-border/60 bg-muted/30 flex items-center justify-between gap-3 shrink-0">
            <div className="flex items-center gap-2 flex-grow min-w-0">
              <span className="font-mono text-[10px] font-bold text-primary uppercase shrink-0">Dataset A:</span>
              <select
                id="tour-align-left"
                data-testid="align-left-select"
                value={leftDatasetId || ''}
                onChange={(e) => handleLeftDatasetChange(e.target.value)}
                disabled={!selectedProjectId}
                className="h-8 px-2 flex-grow rounded-md border border-border bg-muted/20 font-mono text-[11.5px] text-foreground focus:outline-none focus:ring-1 focus:ring-primary truncate cursor-pointer disabled:cursor-not-allowed"
              >
                <option value="">-- select left dataset --</option>
                {datasets.map((d) => (
                  <option key={d.id} value={d.id} disabled={d.id === rightDatasetId}>
                    {d.name} ({d.repoName})
                  </option>
                ))}
              </select>
            </div>
            {isEnrolled(leftDatasetId) && (
              <span className="font-mono text-[9px] text-success shrink-0 bg-success/10 border border-success/30 px-2 py-0.5 rounded flex items-center gap-1" title="Enrolled in the integration model">
                <Check size={9} /> enrolled
              </span>
            )}
            {leftGraph && (
              <div className="font-mono text-[9px] text-muted-foreground shrink-0 bg-muted/50 border border-border/60 px-2 py-0.5 rounded">
                {leftGraph.nodes.length} nodes
              </div>
            )}
          </div>

          {/* GRAPH CANVAS AREA */}
          <div data-testid="align-left-graph" className="flex-grow relative bg-[radial-gradient(circle_at_1px_1px,var(--border)_1px,transparent_0)] bg-[length:20px_20px] overflow-hidden flex flex-col items-center justify-center min-h-0">
            {!leftDatasetId ? (
              <div className="p-6 text-center max-w-sm flex flex-col items-center gap-2">
                <Database size={24} className="text-muted-foreground/30 animate-pulse" />
                <div className="font-mono text-xs font-semibold text-muted-foreground">No left dataset selected</div>
                <DatasetPickHint state={datasetPickState} />
              </div>
            ) : leftGraphLoading ? (
              <div className="flex flex-col items-center gap-2 font-mono text-xs text-muted-foreground">
                <RefreshCw size={18} className="animate-spin text-primary" />
                <span>Loading source schema...</span>
              </div>
            ) : leftBootstrapping ? (
              <div className="flex flex-col items-center gap-2 font-mono text-xs text-muted-foreground">
                <RefreshCw size={18} className="animate-spin text-success" />
                <span>Bootstrapping source schema...</span>
              </div>
            ) : leftGraphError ? (
              <div className="p-6 text-center max-w-sm flex flex-col items-center gap-3">
                <AlertCircle size={24} className="text-error" />
                <div className="font-mono text-xs text-foreground font-semibold">Graph loading error</div>
                <div className="text-[10px] text-muted-foreground/75 font-mono bg-error/5 border border-error/20 p-2 rounded max-h-[80px] overflow-y-auto">
                  {leftGraphError}
                </div>
                {/* J4-F23: a load that failed (a wide graph timing out) is retried as a load.
                    Re-bootstrapping a healthy dataset was the only way on, and the wrong one;
                    reading the schema from the source is offered where the graph is truly
                    empty, below. */}
                <button
                  onClick={() => void loadLeftGraph(leftDatasetId)}
                  className="h-8 px-4 rounded border border-border hover:border-primary hover:text-primary font-mono text-[10px] uppercase font-bold tracking-wider transition-colors cursor-pointer"
                >
                  Retry reading the schema
                </button>
              </div>
            ) : !leftGraph ? (
              <div className="p-6 text-center max-w-sm flex flex-col items-center gap-3">
                <Layers size={24} className="text-warning/60" />
                <div className="font-mono text-xs text-foreground font-semibold">Schema not read yet</div>
                <div className="text-[10.5px] text-muted-foreground/75 font-mono mb-1">
                  This dataset’s schema has not been read yet. Read it from the source to align it.
                </div>
                <button
                  onClick={() => handleBootstrap(leftDatasetId, 'left')}
                  className="h-8 px-4 rounded border border-success/40 bg-success/10 hover:bg-success/20 text-success font-mono text-[10px] uppercase font-bold tracking-widest transition-all cursor-pointer shadow-md"
                >
                  Read schema from source
                </button>
              </div>
            ) : (
              <SchemaGraphCanvas
                graphData={leftGraph}
                selectedNode={leftSelectedNode}
                onNodeSelect={setLeftSelectedNode}
                ariaLabel={`Schema of dataset A, ${leftDatasetId ? datasetName(leftDatasetId) : ''}`}
                nodeSublabel={() => (leftDatasetId ? datasetName(leftDatasetId) : null)}
              />
            )}
          </div>

          {/* ACTIVE SELECTION FOOTER */}
          <div className="h-10 px-3.5 bg-muted/20 border-t border-border/60 flex items-center justify-between shrink-0 font-mono text-[11px]">
            <span className="text-muted-foreground/70">Selected element:</span>
            {leftSelectedNode ? (
              <div className="flex items-center gap-2 max-w-[80%] min-w-0">
                <span
                  className="w-2 h-2 rounded-full shrink-0"
                  style={{
                    backgroundColor:
                      leftSelectedNode.type === 'class'
                        ? 'var(--primary)'
                        : leftSelectedNode.type === 'datatype'
                        ? 'var(--accent)'
                        : 'var(--success)',
                  }}
                />
                <span className="text-foreground font-semibold truncate" title={leftSelectedNode.iri}>
                  {leftSelectedNode.label || leftSelectedNode.id}
                </span>
                <span className="text-[9px] font-bold bg-muted px-1.5 py-0.5 rounded text-muted-foreground uppercase">
                  {leftSelectedNode.type}
                </span>
              </div>
            ) : (
              <span className="text-muted-foreground/60 italic">Click a node, or Tab to it and press Enter</span>
            )}
          </div>
        </div>

        {/* Right Dataset Panel */}
        <div className="flex flex-col rounded-md border border-border bg-card overflow-hidden h-[380px] min-h-0">
          
          {/* TOP SELECT BAR */}
          <div className="p-3 border-b border-border/60 bg-muted/30 flex items-center justify-between gap-3 shrink-0">
            <div className="flex items-center gap-2 flex-grow min-w-0">
              <span className="font-mono text-[10px] font-bold text-accent uppercase shrink-0">Dataset B:</span>
              <select
                id="tour-align-right"
                data-testid="align-right-select"
                value={rightDatasetId || ''}
                onChange={(e) => handleRightDatasetChange(e.target.value)}
                disabled={!selectedProjectId}
                className="h-8 px-2 flex-grow rounded-md border border-border bg-muted/20 font-mono text-[11.5px] text-foreground focus:outline-none focus:ring-1 focus:ring-primary truncate cursor-pointer disabled:cursor-not-allowed"
              >
                <option value="">-- select right dataset --</option>
                {datasets.map((d) => (
                  <option key={d.id} value={d.id} disabled={d.id === leftDatasetId}>
                    {d.name} ({d.repoName})
                  </option>
                ))}
              </select>
            </div>
            {isEnrolled(rightDatasetId) && (
              <span className="font-mono text-[9px] text-success shrink-0 bg-success/10 border border-success/30 px-2 py-0.5 rounded flex items-center gap-1" title="Enrolled in the integration model">
                <Check size={9} /> enrolled
              </span>
            )}
            {rightGraph && (
              <div className="font-mono text-[9px] text-muted-foreground shrink-0 bg-muted/50 border border-border/60 px-2 py-0.5 rounded">
                {rightGraph.nodes.length} nodes
              </div>
            )}
          </div>

          {/* GRAPH CANVAS AREA */}
          <div data-testid="align-right-graph" className="flex-grow relative bg-[radial-gradient(circle_at_1px_1px,var(--border)_1px,transparent_0)] bg-[length:20px_20px] overflow-hidden flex flex-col items-center justify-center min-h-0">
            {!rightDatasetId ? (
              <div className="p-6 text-center max-w-sm flex flex-col items-center gap-2">
                <Database size={24} className="text-muted-foreground/30 animate-pulse" />
                <div className="font-mono text-xs font-semibold text-muted-foreground">No right dataset selected</div>
                <DatasetPickHint state={datasetPickState} />
              </div>
            ) : rightGraphLoading ? (
              <div className="flex flex-col items-center gap-2 font-mono text-xs text-muted-foreground">
                <RefreshCw size={18} className="animate-spin text-primary" />
                <span>Loading source schema...</span>
              </div>
            ) : rightBootstrapping ? (
              <div className="flex flex-col items-center gap-2 font-mono text-xs text-muted-foreground">
                <RefreshCw size={18} className="animate-spin text-success" />
                <span>Bootstrapping source schema...</span>
              </div>
            ) : rightGraphError ? (
              <div className="p-6 text-center max-w-sm flex flex-col items-center gap-3">
                <AlertCircle size={24} className="text-error" />
                <div className="font-mono text-xs text-foreground font-semibold">Graph loading error</div>
                <div className="text-[10px] text-muted-foreground/75 font-mono bg-error/5 border border-error/20 p-2 rounded max-h-[80px] overflow-y-auto">
                  {rightGraphError}
                </div>
                {/* J4-F23: a load that failed (a wide graph timing out) is retried as a load.
                    Re-bootstrapping a healthy dataset was the only way on, and the wrong one;
                    reading the schema from the source is offered where the graph is truly
                    empty, below. */}
                <button
                  onClick={() => void loadRightGraph(rightDatasetId)}
                  className="h-8 px-4 rounded border border-border hover:border-primary hover:text-primary font-mono text-[10px] uppercase font-bold tracking-wider transition-colors cursor-pointer"
                >
                  Retry reading the schema
                </button>
              </div>
            ) : !rightGraph ? (
              <div className="p-6 text-center max-w-sm flex flex-col items-center gap-3">
                <Layers size={24} className="text-warning/60" />
                <div className="font-mono text-xs text-foreground font-semibold">Schema not read yet</div>
                <div className="text-[10.5px] text-muted-foreground/75 font-mono mb-1">
                  This dataset’s schema has not been read yet. Read it from the source to align it.
                </div>
                <button
                  onClick={() => handleBootstrap(rightDatasetId, 'right')}
                  className="h-8 px-4 rounded border border-success/40 bg-success/10 hover:bg-success/20 text-success font-mono text-[10px] uppercase font-bold tracking-widest transition-all cursor-pointer shadow-md"
                >
                  Read schema from source
                </button>
              </div>
            ) : (
              <SchemaGraphCanvas
                graphData={rightGraph}
                selectedNode={rightSelectedNode}
                onNodeSelect={setRightSelectedNode}
                ariaLabel={`Schema of dataset B, ${rightDatasetId ? datasetName(rightDatasetId) : ''}`}
                nodeSublabel={() => (rightDatasetId ? datasetName(rightDatasetId) : null)}
              />
            )}
          </div>

          {/* ACTIVE SELECTION FOOTER */}
          <div className="h-10 px-3.5 bg-muted/20 border-t border-border/60 flex items-center justify-between shrink-0 font-mono text-[11px]">
            <span className="text-muted-foreground/70">Selected element:</span>
            {rightSelectedNode ? (
              <div className="flex items-center gap-2 max-w-[80%] min-w-0">
                <span
                  className="w-2 h-2 rounded-full shrink-0"
                  style={{
                    backgroundColor:
                      rightSelectedNode.type === 'class'
                        ? 'var(--primary)'
                        : rightSelectedNode.type === 'datatype'
                        ? 'var(--accent)'
                        : 'var(--success)',
                  }}
                />
                <span className="text-foreground font-semibold truncate" title={rightSelectedNode.iri}>
                  {rightSelectedNode.label || rightSelectedNode.id}
                </span>
                <span className="text-[9px] font-bold bg-muted px-1.5 py-0.5 rounded text-muted-foreground uppercase">
                  {rightSelectedNode.type}
                </span>
              </div>
            ) : (
              <span className="text-muted-foreground/60 italic">Click a node, or Tab to it and press Enter</span>
            )}
          </div>
        </div>

      </div>

      {/* Alignment Creator Panel */}
      <AlignmentCreator
        leftSelectedNode={leftSelectedNode}
        rightSelectedNode={rightSelectedNode}
        alignmentLabel={alignmentLabel}
        setAlignmentLabel={setAlignmentLabel}
        alignmentType={alignmentType}
        setAlignmentType={setAlignmentType}
        relationshipName={relationshipName}
        setRelationshipName={setRelationshipName}
        relationshipDirection={relationshipDirection}
        setRelationshipDirection={setRelationshipDirection}
        leftDatasetId={leftDatasetId}
        rightDatasetId={rightDatasetId}
        leftGraphLoading={leftGraphLoading}
        rightGraphLoading={rightGraphLoading}
        suggesting={suggesting}
        suggestionError={suggestionError}
        suggestionNote={suggestionNote}
        addMappingError={addMappingError}
        discoveryCandidates={discoveryCandidates}
        discoveryError={discoveryError}
        discovering={discovering}
        discoveryOpen={showDiscovery}
        onAddAlignment={handleAddAlignment}
        onSuggest={handleSuggest}
        onOpenDiscovery={() => setShowDiscovery(true)}
        onRunDiscovery={handleRunDiscovery}
      />
      </section>

      {/* Active alignments list & Integrate button */}
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-4 shrink-0 items-start">

        {/* Accepted alignments + suggestion review queue, both from the model */}
        <section id="stage-review" className="flex flex-col gap-2 min-w-0 scroll-mt-4">
          <div className="text-[12.5px] font-semibold text-muted-foreground/80 select-none">
            3 · Review — accept or dismiss what discovery suggested
          </div>
          <MappingPanel
            acceptedMappings={acceptedMappings}
            suggestedMappings={suggestedMappings}
            onDelete={(id) => void handleRemoveMapping(id)}
            onAccept={(id) => void handleAcceptMapping(id)}
            onReject={(id) => void handleRejectMapping(id)}
            flaggedIds={mappingsNamedIn(integrationError, acceptedMappings)}
            standing={standing}
            joinEdges={joinEdges}
            joinEdgesError={joinEdgesError}
            currentUserId={currentUserId}
          />
        </section>

        <section id="stage-compile" className="flex flex-col gap-2 min-w-0 scroll-mt-4">
        <div className="text-[12.5px] font-semibold text-muted-foreground/80 select-none">
          4 · Compile &amp; 5 · Promote — build the draft, then make it active
        </div>
        <CompilePanel
          rebuildRequired={!!bundle?.model.rebuildRequired}
          onIntegrate={handleIntegrate}
          integrating={integrating}
          compileJobId={compileJobId}
          selectedProjectId={selectedProjectId}
          enrolledDatasetIds={enrolledDatasetIds}
          incrementalDatasetId={incrementalDatasetId}
          setIncrementalDatasetId={setIncrementalDatasetId}
          canIncrement={canIncrement}
          staleBaseline={staleBaseline}
          addableDatasetIds={addableDatasetIds}
          datasetName={datasetName}
          onIncrementalAdd={handleIncrementalAdd}
          hasBaseline={hasBaseline}
          integrationResult={displayedDraft}
          onCompileDone={handleCompileDone}
          integrationError={integrationError}
          promoteCheck={promoteCheck}
          onRecheckPromote={() => void handleRecheckPromote()}
          promoted={promoted}
          onPromoteDraft={handlePromoteDraft}
          promoting={promoting}
          integratedGraph={integratedGraph}
          integratedGraphLoading={integratedGraphLoading}
          integratedGraphError={integratedGraphError}
          integratedSelectedNode={integratedSelectedNode}
          setIntegratedSelectedNode={setIntegratedSelectedNode}
        />
        </section>

      </div>

      <JoinDiscoveryModal
        show={showDiscovery}
        onClose={() => setShowDiscovery(false)}
        accepting={acceptingCandidates}
        outcomes={candidateOutcomes}
        labels={candidateLabels}
        onLabelChange={(idx, label) => setCandidateLabels((prev) => ({ ...prev, [idx]: label }))}
        unavailable={candidateUnavailable}
        threshold={discoveryThreshold}
        setThreshold={setDiscoveryThreshold}
        method={discoveryMethod}
        setMethod={setDiscoveryMethod}
        strictness={discoveryStrictness}
        setStrictness={setDiscoveryStrictness}
        discovering={discovering}
        discoveryError={discoveryError}
        result={shownDiscoveryRun}
        settledRunId={discoveryRanForCurrent ? settledDiscoveryRunId : null}
        selectedCandidates={selectedCandidates}
        setSelectedCandidates={setSelectedCandidates}
        onRunDiscovery={handleRunDiscovery}
        onAcceptCandidates={(labels) => void handleAcceptCandidates(labels)}
      />

    </div>
  );
};

export default AlignmentsView;
