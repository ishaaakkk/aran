/**
 * The alignments pipeline — enroll → align → review → compile → promote — derived from what the
 * server holds, not from what this browser session happened to do (audit J4-F5). The day after a
 * promote the stepper used to say "Promote — after compile" because "promoted" was a React flag
 * set by the click that promoted; after a reload it knew nothing.
 */
export interface PipelineInputs {
  enrolledCount: number;
  acceptedCount: number;
  /** Suggestions waiting in the review queue. */
  pendingCount: number;
  compiling: boolean;
  /** A compiled draft awaiting promotion — this session's, or the model's latest DRAFT run. */
  draftVersionNumber: number | null;
  /** The model's last promote is the tenant's ACTIVE schema (its version number), else null. */
  liveVersionNumber: number | null;
  /** Something accepted is not in the live schema yet, or a full rebuild is required. */
  changesToCompile: boolean;
  /** Accepted alignments the compiler deferred — not a change to compile, but said. */
  deferredCount?: number;
}

export type StepState = 'done' | 'current' | 'todo';

export interface PipelineStep {
  label: string;
  sub: string;
  state: StepState;
}

export function pipelineSteps(s: PipelineInputs): PipelineStep[] {
  const enrolled = s.enrolledCount > 0;
  const aligned = s.acceptedCount > 0;
  const hasDraft = s.draftVersionNumber !== null;
  const live = s.liveVersionNumber !== null;
  const upToDate = live && !hasDraft && !s.compiling && !s.changesToCompile && s.pendingCount === 0;
  // The furthest stage with activity is "current"; everything before it is done.
  const currentIdx = upToDate
    ? 5
    : hasDraft
      ? 4
      : s.compiling
        ? 3
        : s.pendingCount > 0
          ? 2
          : aligned || live
            ? 3
            : enrolled
              ? 1
              : 0;

  const promoteSub = hasDraft
    ? `v${s.draftVersionNumber} awaits`
    : live
      ? s.changesToCompile ? `v${s.liveVersionNumber} active · changes to compile` : `v${s.liveVersionNumber} active`
      : 'after compile';

  const compileSub = hasDraft ? 'draft built' : s.changesToCompile ? 'changes to compile' : live ? 'up to date' : aligned ? 'ready' : 'after align';
  const deferred = s.deferredCount ?? 0;

  const defs: Array<{ label: string; sub: string }> = [
    { label: 'Enroll', sub: enrolled ? `${s.enrolledCount} source${s.enrolledCount === 1 ? '' : 's'}` : 'add sources' },
    { label: 'Align', sub: aligned ? `${s.acceptedCount} accepted` : 'browse schemas' },
    { label: 'Review', sub: s.pendingCount > 0 ? `${s.pendingCount} pending` : 'none pending' },
    {
      label: 'Compile',
      sub: s.compiling ? 'running…' : deferred > 0 ? `${compileSub} · ${deferred} deferred` : compileSub,
    },
    { label: 'Promote', sub: promoteSub },
  ];

  return defs.map((d, i) => ({
    ...d,
    state: i < currentIdx ? 'done' : i === currentIdx ? 'current' : 'todo',
  }));
}
