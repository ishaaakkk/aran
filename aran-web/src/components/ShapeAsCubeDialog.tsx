import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '@/services/api';
import { errorMessage } from '@/lib/errors';
import { localName } from '@/lib/iri';
import { Dialog } from '@/components/ui/Dialog';
import {
  ADDITIVITIES,
  AGGREGATORS,
  buildCubeDefinition,
  isValidCubeName,
  rankFactCandidates,
  suggestCalendar,
  CALENDAR_PRESELECTED,
  suggestDimensions,
  suggestMeasures,
  toCubeName,
} from '@/lib/cubeSuggest';
import type { DimensionDraft, FactCandidate, MeasureDraft } from '@/lib/cubeSuggest';
import type {
  Additivity,
  CubeCalendarDto,
  CubeDto,
  CubeEdgeTier,
  CubeEvidence,
  CubeEvidenceClass,
} from '@/services/types';

/**
 * "Shape as cube" — the four-step dimensional-modelling wizard.
 *
 * Every step is pre-filled from evidence the platform already holds, so the happy path is
 * four confirmations rather than a form: ThoughtSpot's "the system proposes, you confirm"
 * stance, with Looker's lesson inverted — where Looker makes the modeller write the
 * definitions, we make the machine write them and show its work. The suggestion rules
 * themselves live in `lib/cubeSuggest.ts` and are unit-tested there; this file is the shell.
 *
 * The step bodies are exported as their own presentational components for the same reason
 * `SaveAsProductDialog` was extracted from `OqlPlanView`: the container fetches on open, and
 * this project has no jsdom to click through four steps — `renderToStaticMarkup` can only
 * reach a step's output if the step takes its state as props.
 *
 * Nothing persists until step 4's single act, and the footer says so on every step.
 */

const STEPS = ['fact', 'measures & dimensions', 'hierarchies', 'review & create'];

const TIER_STYLES: Record<CubeEdgeTier, string> = {
  VERIFIED: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
  DECLARED: 'bg-sky-500/15 text-sky-400 border-sky-500/30',
  HYPOTHESIS: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
};

const ADDITIVITY_STYLES: Record<Additivity, string> = {
  ADDITIVE: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
  SEMI_ADDITIVE: 'bg-sky-500/15 text-sky-400 border-sky-500/30',
  NON_ADDITIVE: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
  RATIO: 'bg-violet-500/15 text-violet-400 border-violet-500/30',
};

/** Tier order for the dimension groups: the trusted ones first, the caveated one last. */
const TIER_ORDER: CubeEdgeTier[] = ['VERIFIED', 'DECLARED', 'HYPOTHESIS'];

const chip = 'inline-flex items-center font-semibold uppercase tracking-wider border rounded-sm text-[9px] px-1.5 py-[2px] leading-tight select-none';

// Pinned to 'en-US' for the same reason resultPaging.ts pins it: the UI is English with comma
// thousands separators wherever it runs, and an unpinned toLocaleString() renders '2.100.000'
// instead of '2,100,000' on a non-English OS locale.
const num = (n: number | null) => (n == null ? '—' : n.toLocaleString('en-US'));

/** A percentage with one decimal, for a null fraction that is usually a fraction of a percent. */
const pct = (fraction: number) => `${(fraction * 100).toFixed(fraction < 0.01 ? 2 : 1)}%`;

export const TierChip: React.FC<{ tier: CubeEdgeTier }> = ({ tier }) => (
  <span className={`${chip} ${TIER_STYLES[tier]}`}>{tier}</span>
);

export const AdditivityChip: React.FC<{ additivity: Additivity }> = ({ additivity }) => (
  <span className={`${chip} ${ADDITIVITY_STYLES[additivity]}`}>
    {additivity.toLowerCase().replace('_', '-')}
  </span>
);

/** The vertical stepper rail: done / current / todo, each with its one-line running summary
 *  so the wizard always says where it is and what it decided. */
export const StepRail: React.FC<{ step: number; summaries: (string | null)[] }> = ({
  step,
  summaries,
}) => (
  <ol className="w-44 shrink-0 border-r border-border p-3 space-y-3 text-[10px]">
    {STEPS.map((label, i) => (
      <li key={label} className="flex gap-2">
        <span
          className={`mt-[1px] w-4 h-4 shrink-0 rounded-full border flex items-center justify-center text-[9px] ${
            i < step
              ? 'border-emerald-500/40 bg-emerald-500/15 text-emerald-400'
              : i === step
                ? 'border-primary text-primary'
                : 'border-border text-muted-foreground'
          }`}
        >
          {i < step ? '✓' : i + 1}
        </span>
        <span className="min-w-0">
          <span className={i === step ? 'text-foreground' : 'text-muted-foreground'}>{label}</span>
          {summaries[i] && (
            <span className="block text-muted-foreground/70 truncate">{summaries[i]}</span>
          )}
        </span>
      </li>
    ))}
  </ol>
);

// ── Step 1 ───────────────────────────────────────────────────────────────────

interface FactStepProps {
  candidates: FactCandidate[];
  selectedIri: string | null;
  onSelect: (iri: string) => void;
}

/** Ranked candidate list with evidence chips — never an empty form. A dimension-shaped class
 *  is demoted *with its reason*, not hidden: the heuristic is a hint, not a verdict. */
export const FactStep: React.FC<FactStepProps> = ({ candidates, selectedIri, onSelect }) => {
  const top = candidates.length > 0 ? Math.max(candidates[0].score, 1) : 1;
  if (candidates.length === 0) {
    return (
      <div className="text-[11px] text-muted-foreground">
        No integrated classes to shape. Compile and promote a schema version first — a cube is
        made of the integrated model, it is not a way to build one.
      </div>
    );
  }
  return (
    <div className="space-y-2" data-testid="cube-fact-candidates">
      <div className="text-[10px] text-muted-foreground">
        Ranked by rows, measurable columns and joins out. Pick the thing you measure.
      </div>
      {candidates.map((c) => (
        <label
          key={c.cls.iri}
          data-testid={`cube-fact-${c.cls.name}`}
          className={`flex items-start gap-2 p-2 border rounded cursor-pointer ${
            selectedIri === c.cls.iri ? 'border-primary bg-primary/5' : 'border-border hover:bg-muted/20'
          }`}
        >
          <input
            type="radio"
            name="cube-fact"
            className="mt-1"
            value={c.cls.iri}
            checked={selectedIri === c.cls.iri}
            onChange={() => onSelect(c.cls.iri)}
          />
          <span className="min-w-0 flex-1">
            <span className="flex items-center gap-2">
              <span className="text-foreground">{c.cls.name}</span>
              <span className="h-1 w-16 rounded-full bg-muted overflow-hidden" aria-hidden="true">
                <span
                  className="block h-full bg-primary"
                  style={{ width: `${Math.round((Math.max(c.score, 0) / top) * 100)}%` }}
                />
              </span>
            </span>
            <span className="block text-[10px] text-muted-foreground">
              {num(c.rowCount)} rows · {c.numericCount} numeric column
              {c.numericCount === 1 ? '' : 's'} · {c.edgesOut} join
              {c.edgesOut === 1 ? '' : 's'} out
            </span>
            {c.demotedReason && (
              <span className="block text-[10px] text-amber-400">{c.demotedReason}</span>
            )}
          </span>
        </label>
      ))}
    </div>
  );
};

// ── Step 2 ───────────────────────────────────────────────────────────────────

interface MeasuresStepProps {
  measures: MeasureDraft[];
  dimensions: DimensionDraft[];
  onToggleMeasure: (columnIri: string) => void;
  onAggregator: (columnIri: string, aggregator: string) => void;
  onAdditivity: (columnIri: string, additivity: Additivity) => void;
  onToggleDimension: (key: string) => void;
  /** The generated-calendar offer, or null when the fact has no date column. */
  calendar: CubeCalendarDto | null;
  calendarOn: boolean;
  onToggleCalendar: () => void;
}

/** Measures on the left, dimensions grouped by trust tier on the right — the two halves of a
 *  star, decided together because a column is one or the other. */
export const MeasuresStep: React.FC<MeasuresStepProps> = ({
  measures,
  dimensions,
  onToggleMeasure,
  onAggregator,
  onAdditivity,
  onToggleDimension,
  calendar,
  calendarOn,
  onToggleCalendar,
}) => (
  <div className="grid grid-cols-2 gap-4">
    <div className="space-y-2" data-testid="cube-measures">
      <div className="text-[10px] text-muted-foreground uppercase tracking-wider">measures</div>
      {measures.length === 0 && (
        <div className="text-[11px] text-muted-foreground">
          This class has no columns to measure. Go back and pick another fact.
        </div>
      )}
      {measures.map((m) => (
        <div
          key={m.columnIri}
          data-testid={`cube-measure-${m.name}`}
          className="p-2 border border-border rounded space-y-1"
        >
          <label className="flex items-start gap-2 cursor-pointer">
            <input
              type="checkbox"
              className="mt-0.5"
              checked={m.selected}
              disabled={m.excluded}
              onChange={() => onToggleMeasure(m.columnIri)}
            />
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-2">
                <span className={m.excluded ? 'text-muted-foreground line-through' : 'text-foreground'}>
                  {m.name}
                </span>
                <AdditivityChip additivity={m.additivity} />
              </span>
              {m.reason && (
                <span className="block text-[10px] text-muted-foreground leading-relaxed">
                  {m.reason}
                </span>
              )}
            </span>
          </label>
          {!m.excluded && (
            <div className="flex gap-2 pl-6">
              <select
                aria-label={`aggregator for ${m.name}`}
                className="bg-muted border border-border rounded px-1 py-0.5 text-[10px] text-foreground"
                value={m.aggregator}
                onChange={(e) => onAggregator(m.columnIri, e.target.value)}
              >
                {AGGREGATORS.map((a) => (
                  <option key={a} value={a}>{a}</option>
                ))}
              </select>
              <select
                aria-label={`additivity for ${m.name}`}
                className="bg-muted border border-border rounded px-1 py-0.5 text-[10px] text-foreground"
                value={m.additivity}
                onChange={(e) => onAdditivity(m.columnIri, e.target.value as Additivity)}
              >
                {ADDITIVITIES.map((a) => (
                  <option key={a} value={a}>{a}</option>
                ))}
              </select>
            </div>
          )}
        </div>
      ))}
    </div>

    <div className="space-y-2" data-testid="cube-dimensions">
      <div className="text-[10px] text-muted-foreground uppercase tracking-wider">dimensions</div>
      {dimensions.length === 0 && (
        <div className="text-[11px] text-muted-foreground">
          No accepted joins reach this class. Accept a join alignment first, or create the cube
          with measures only and add dimensions later.
        </div>
      )}
      {TIER_ORDER.map((tier) => {
        const group = dimensions.filter((d) => d.tier === tier);
        if (group.length === 0) return null;
        return (
          <div key={tier} className="space-y-1">
            <div className="flex items-center gap-2 pt-1">
              <TierChip tier={tier} />
              <span className="text-[10px] text-muted-foreground">
                {group.length} candidate{group.length === 1 ? '' : 's'}
              </span>
            </div>
            {group.map((d) => (
              <label
                key={d.key}
                data-testid={`cube-dimension-${d.name}`}
                className="flex items-start gap-2 p-2 border border-border rounded cursor-pointer"
              >
                <input
                  type="checkbox"
                  className="mt-0.5"
                  checked={d.selected}
                  onChange={() => onToggleDimension(d.key)}
                />
                <span className="min-w-0 flex-1">
                  <span className="text-foreground">{d.name}</span>
                  <span className="block text-[10px] text-muted-foreground">
                    {d.className} · via {d.joinPropertyName}
                  </span>
                  <span className="block text-[10px] text-muted-foreground leading-relaxed">
                    {d.reason}
                  </span>
                  {d.unknownMember && d.nullFraction != null && (
                    <span className="block text-[10px] text-sky-400">
                      + &apos;unknown&apos; member ({pct(d.nullFraction)} of fact rows have no
                      match) — recorded in the definition; v1 does not materialize the member yet
                    </span>
                  )}
                </span>
              </label>
            ))}
          </div>
        );
      })}

      {calendar && (
        <label
          data-testid="cube-calendar-offer"
          // The visible text sits two spans deep, which the a11y rule cannot see through —
          // named here as SaveAsProductDialog's access-mode labels are, for the same reason.
          aria-label="Add a generated calendar dimension: year, quarter, month, day"
          className="flex items-start gap-2 p-2 border border-border rounded cursor-pointer mt-2"
        >
          <input type="checkbox" className="mt-0.5" checked={calendarOn} onChange={onToggleCalendar} />
          <span className="min-w-0 flex-1">
            <span className="flex items-center gap-2">
              <span className="text-foreground">calendar dimension</span>
              <span className="text-[9px] uppercase tracking-wider border border-border rounded px-1 text-muted-foreground">definition only</span>
            </span>
            <span className="block text-[10px] text-muted-foreground leading-relaxed">
              from {localName(calendar.fromColumnIri)} — year → quarter → month → day. Recorded
              in the definition and its exports; v1 generates no calendar table.
            </span>
          </span>
        </label>
      )}
    </div>
  </div>
);

// ── Step 3 ───────────────────────────────────────────────────────────────────

interface HierarchiesStepProps {
  /** Accepted dimensions only — a hierarchy for an unticked dimension is not a question. */
  dimensions: DimensionDraft[];
  onSetHierarchy: (key: string, levels: string[]) => void;
}

/** One card per accepted dimension. Entirely skippable, and the step says so: a flat
 *  dimension is a legitimate model, not an unfinished one. */
export const HierarchiesStep: React.FC<HierarchiesStepProps> = ({ dimensions, onSetHierarchy }) => (
  <div className="space-y-3" data-testid="cube-hierarchies">
    <div className="text-[10px] text-muted-foreground">
      Levels are ordered coarsest first, by distinct counts from the profile — sample evidence,
      not a proven functional dependency. Skip the whole step if you want flat dimensions.
    </div>
    {dimensions.length === 0 && (
      <div className="text-[11px] text-muted-foreground">
        No dimensions accepted, so there is nothing to give levels to.
      </div>
    )}
    {dimensions.map((d) => (
      <div key={d.key} data-testid={`cube-hierarchy-${d.name}`} className="p-2 border border-border rounded space-y-1">
        <div className="flex items-center justify-between gap-2">
          <span className="text-foreground text-[11px]">{d.name}</span>
          <button
            type="button"
            className="text-[10px] px-2 py-0.5 border border-border rounded hover:border-primary hover:text-primary"
            onClick={() => onSetHierarchy(d.key, [])}
            disabled={d.hierarchy.length === 0}
          >
            keep flat
          </button>
        </div>
        {d.levelOptions.length === 0 ? (
          <div className="text-[10px] text-muted-foreground">
            No level chain stands out — its columns have similar distinct counts, which is what a
            set of unrelated attributes looks like. Flat.
          </div>
        ) : (
          <ol className="space-y-0.5">
            {d.levelOptions.map((level, i) => {
              const on = d.hierarchy.includes(level.iri);
              return (
                <li key={level.iri} className="flex items-center gap-2 text-[10px]">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={on}
                      onChange={() =>
                        onSetHierarchy(
                          d.key,
                          // Kept in the suggested (coarsest-first) order however it is toggled —
                          // a hierarchy whose levels are out of order rolls up to nonsense.
                          d.levelOptions
                            .filter((l) => (l.iri === level.iri ? !on : d.hierarchy.includes(l.iri)))
                            .map((l) => l.iri),
                        )
                      }
                    />
                    <span className={on ? 'text-foreground' : 'text-muted-foreground'}>
                      {i + 1}. {level.name}
                    </span>
                  </label>
                  <span className="text-muted-foreground/70">{num(level.ndv)} distinct</span>
                </li>
              );
            })}
          </ol>
        )}
      </div>
    ))}
  </div>
);

// ── Step 4 ───────────────────────────────────────────────────────────────────

interface ReviewStepProps {
  factName: string;
  measureCount: number;
  dimensionCount: number;
  hierarchyCount: number;
  calendarOn: boolean;
  name: string;
  onNameChange: (name: string) => void;
  description: string;
  onDescriptionChange: (description: string) => void;
  error: string | null;
  /** The created cube, once the single act succeeds — swaps the form for a confirmation. */
  created: CubeDto | null;
  onClose: () => void;
}

export const ReviewStep: React.FC<ReviewStepProps> = ({
  factName,
  measureCount,
  dimensionCount,
  hierarchyCount,
  calendarOn,
  name,
  onNameChange,
  description,
  onDescriptionChange,
  error,
  created,
  onClose,
}) => {
  if (created) {
    return (
      <div className="space-y-3 text-[12px]" data-testid="cube-created">
        <div className="text-emerald-400">✓ cube created</div>
        <div className="text-[10px] text-muted-foreground">
          {created.products.length} product{created.products.length === 1 ? '' : 's'} written
          {created.jobIds.length > 0 && ` · ${created.jobIds.length} job(s) running`}
        </div>
        <Link to="/products" className="text-primary underline" onClick={onClose}>
          open data products →
        </Link>
      </div>
    );
  }
  const nameOk = name === '' || isValidCubeName(name);
  return (
    <div className="space-y-3 text-[12px]">
      <div className="p-2 border border-border rounded text-[10px] text-muted-foreground space-y-0.5">
        <div><span className="text-foreground">{factName}</span> as the fact</div>
        <div>{measureCount} measure{measureCount === 1 ? '' : 's'}</div>
        <div>
          {dimensionCount} dimension{dimensionCount === 1 ? '' : 's'}, {hierarchyCount} with levels
          {calendarOn && ' · generated calendar'}
        </div>
      </div>
      <div>
        <div className="text-[10px] text-muted-foreground mb-1">NAME</div>
        <input
          data-testid="cube-name"
          className="w-full bg-muted border border-border rounded px-2 py-1.5 text-foreground"
          value={name}
          onChange={(e) => onNameChange(e.target.value)}
          placeholder="orders_cube"
        />
        {!nameOk && (
          <div className="text-[10px] text-amber-400 mt-1">
            Lowercase letters, digits and underscores, starting with a letter (max 63) — the
            server&apos;s rule, checked here so it fails before the write, not during it.
          </div>
        )}
      </div>
      <div>
        <div className="text-[10px] text-muted-foreground mb-1">DESCRIPTION</div>
        <input
          className="w-full bg-muted border border-border rounded px-2 py-1.5 text-foreground"
          value={description}
          onChange={(e) => onDescriptionChange(e.target.value)}
          placeholder="optional"
        />
      </div>
      <div className="text-[10px] text-muted-foreground leading-relaxed" data-testid="cube-as-is-note">
        Dimension attributes are as-is — current values, not history. A customer who moved cities
        rolls up under today&apos;s city for every fact they were ever part of. The star is
        materialized once at creation: refresh each product to bring it up to date after a
        source refresh.
      </div>
      {error && (
        <div className="border border-rose-500/40 bg-rose-500/10 rounded p-2 text-[11px] text-rose-300">
          {error}
        </div>
      )}
    </div>
  );
};

// ── The container ────────────────────────────────────────────────────────────

interface ShapeAsCubeDialogProps {
  open: boolean;
  projectId: string;
  onClose: () => void;
  /** Fired once, after a successful create, so the opener can refresh its list. */
  onCreated?: (cube: CubeDto) => void;
}

export const ShapeAsCubeDialog: React.FC<ShapeAsCubeDialogProps> = ({
  open,
  projectId,
  onClose,
  onCreated,
}) => {
  const [evidence, setEvidence] = useState<CubeEvidence | null>(null);
  // Starts true: the dialog opens into the fetch, so "loading" is the honest first frame
  // rather than an empty candidate list that briefly claims there is nothing to shape.
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [step, setStep] = useState(0);
  const [factIri, setFactIri] = useState<string | null>(null);
  const [measures, setMeasures] = useState<MeasureDraft[]>([]);
  const [dimensions, setDimensions] = useState<DimensionDraft[]>([]);
  const [calendar, setCalendar] = useState<CubeCalendarDto | null>(null);
  const [calendarOn, setCalendarOn] = useState(false);

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [created, setCreated] = useState<CubeDto | null>(null);

  useEffect(() => {
    if (!open || !projectId) return;
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    api.getCubeEvidence(projectId)
      .then((e) => { if (!cancelled) setEvidence(e); })
      .catch((e: unknown) => {
        if (!cancelled) setLoadError(errorMessage(e, 'Failed to load the modelling evidence.'));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [open, projectId]);

  useEffect(() => {
    // A fresh open is a fresh wizard: leaving step 3 and half a definition behind from last
    // time would make "create" write something the user never reviewed.
    if (open) return;
    setStep(0);
    setFactIri(null);
    setMeasures([]);
    setDimensions([]);
    setCalendar(null);
    setCalendarOn(false);
    setName('');
    setDescription('');
    setCreateError(null);
    setCreated(null);
  }, [open]);

  const candidates = evidence ? rankFactCandidates(evidence) : [];
  const factClass: CubeEvidenceClass | null =
    evidence?.classes.find((c) => c.iri === factIri) ?? null;

  function selectFact(iri: string) {
    if (!evidence) return;
    setFactIri(iri);
    const cls = evidence.classes.find((c) => c.iri === iri);
    if (!cls) return;
    const joinCols = evidence.edges
      .filter((e) => e.leftClassIri === iri || e.rightClassIri === iri)
      .map((e) => (e.leftClassIri === iri ? e.leftColIri : e.rightColIri));
    setMeasures(suggestMeasures(cls, joinCols));
    setDimensions(suggestDimensions(evidence, iri));
    const cal = suggestCalendar(cls);
    setCalendar(cal);
    setCalendarOn(cal !== null && CALENDAR_PRESELECTED);
    setName(toCubeName(cls.name) ? `${toCubeName(cls.name)}_cube`.slice(0, 63) : '');
  }

  const accepted = dimensions.filter((d) => d.selected);
  const keptMeasures = measures.filter((m) => m.selected);

  const summaries: (string | null)[] = [
    factClass ? `${factClass.name} · ${num(factClass.rowCount)} rows` : null,
    factIri ? `${keptMeasures.length} measures · ${accepted.length} dims` : null,
    factIri ? `${accepted.filter((d) => d.hierarchy.length > 0).length} with levels` : null,
    name || null,
  ];

  async function create() {
    if (!factIri) return;
    setCreating(true);
    setCreateError(null);
    try {
      const cube = await api.createCube(projectId, {
        name,
        ...(description.trim() ? { description: description.trim() } : {}),
        definition: buildCubeDefinition(
          factIri,
          measures,
          dimensions,
          calendarOn ? calendar : null,
        ),
      });
      setCreated(cube);
      onCreated?.(cube);
    } catch (e: unknown) {
      setCreateError(errorMessage(e, 'Failed to create the cube.'));
    } finally {
      setCreating(false);
    }
  }

  const canContinue =
    (step === 0 && factIri !== null) ||
    (step === 1 && keptMeasures.length > 0) ||
    step === 2;

  const hint =
    created !== null
      ? 'Written. The cube and its products are in this project now.'
      : step === 0
        ? 'Nothing is written until the last step.'
        : step === 1
          ? 'Keep at least one measure — row_count counts fact rows; SUM and AVG need numeric columns.'
          : step === 2
            ? 'Skippable. Flat dimensions are a legitimate model.'
            : 'Creating writes the cube annotations and its products in one act.';

  return (
    <Dialog
      open={open}
      onClose={onClose}
      label="Shape as cube"
      maxWidth="max-w-4xl"
      className="font-mono"
      testId="shape-as-cube-dialog"
    >
      <div className="h-9 flex items-center px-4 bg-muted/30 border-b border-border text-[11px] text-muted-foreground">
        <span className="text-primary">$</span>&nbsp;shape as cube
        {/* A Preview until the wizard has run end to end against a live stack (V62); the API path
            has. Like Databricks and Snowflake, say what Preview means in words on the surface
            itself, not in a hover-only tooltip that keyboard and touch users never reach. */}
        <span
          data-testid="cube-preview-badge"
          className="ml-2 text-[9px] uppercase tracking-wider border border-warning/50 text-warning rounded px-1"
        >
          Preview
        </span>
        <span data-testid="cube-preview-note" className="ml-2 text-[10px] text-muted-foreground truncate">
          not yet tried end to end on a running deployment — check the products it creates before relying on them
        </span>
      </div>
      <div className="flex">
        <StepRail step={created ? STEPS.length - 1 : step} summaries={summaries} />
        <div className="flex-1 min-w-0 p-4 max-h-[65vh] overflow-y-auto">
          {loading ? (
            <div className="text-[11px] text-muted-foreground">loading evidence…</div>
          ) : loadError ? (
            <div className="border border-rose-500/40 bg-rose-500/10 rounded p-2 text-[11px] text-rose-300">
              {loadError}
            </div>
          ) : step === 0 ? (
            <FactStep candidates={candidates} selectedIri={factIri} onSelect={selectFact} />
          ) : step === 1 ? (
            <MeasuresStep
              measures={measures}
              dimensions={dimensions}
              onToggleMeasure={(iri) =>
                setMeasures((ms) => ms.map((m) => (m.columnIri === iri ? { ...m, selected: !m.selected } : m)))
              }
              onAggregator={(iri, aggregator) =>
                setMeasures((ms) => ms.map((m) => (m.columnIri === iri ? { ...m, aggregator } : m)))
              }
              onAdditivity={(iri, additivity) =>
                setMeasures((ms) => ms.map((m) => (m.columnIri === iri ? { ...m, additivity } : m)))
              }
              onToggleDimension={(key) =>
                setDimensions((ds) => ds.map((d) => (d.key === key ? { ...d, selected: !d.selected } : d)))
              }
              calendar={calendar}
              calendarOn={calendarOn}
              onToggleCalendar={() => setCalendarOn((on) => !on)}
            />
          ) : step === 2 ? (
            <HierarchiesStep
              dimensions={accepted}
              onSetHierarchy={(key, levels) =>
                setDimensions((ds) => ds.map((d) => (d.key === key ? { ...d, hierarchy: levels } : d)))
              }
            />
          ) : (
            <ReviewStep
              factName={factClass?.name ?? '—'}
              measureCount={keptMeasures.length}
              dimensionCount={accepted.length}
              hierarchyCount={accepted.filter((d) => d.hierarchy.length > 0).length}
              calendarOn={calendarOn && calendar !== null}
              name={name}
              onNameChange={setName}
              description={description}
              onDescriptionChange={setDescription}
              error={createError}
              created={created}
              onClose={onClose}
            />
          )}
        </div>
      </div>
      <div className="flex items-center justify-between gap-3 px-4 py-3 border-t border-border">
        <span className="text-[10px] text-muted-foreground">{hint}</span>
        <span className="flex gap-2">
          <button
            type="button"
            className="text-[11px] px-3 py-1 border border-border rounded text-muted-foreground hover:text-foreground"
            onClick={created ? onClose : () => (step === 0 ? onClose() : setStep(step - 1))}
          >
            {created ? 'close' : step === 0 ? 'cancel' : 'back'}
          </button>
          {!created && (
            step < 3 ? (
              <button
                type="button"
                data-testid="cube-continue"
                className="text-[11px] px-3 py-1 border border-primary rounded text-primary hover:bg-primary/10 disabled:opacity-40 disabled:cursor-not-allowed"
                disabled={!canContinue || loading || loadError !== null}
                onClick={() => setStep(step + 1)}
              >
                continue
              </button>
            ) : (
              <button
                type="button"
                data-testid="cube-create"
                className="text-[11px] px-3 py-1 border border-primary rounded text-primary hover:bg-primary/10 disabled:opacity-40 disabled:cursor-not-allowed"
                disabled={creating || !isValidCubeName(name) || keptMeasures.length === 0}
                onClick={() => void create()}
              >
                {creating ? 'creating…' : 'create cube'}
              </button>
            )
          )}
        </span>
      </div>
    </Dialog>
  );
};

export default ShapeAsCubeDialog;
