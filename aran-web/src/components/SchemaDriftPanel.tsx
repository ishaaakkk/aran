import React from 'react'
import { AlertTriangle, ArrowRight, CheckCircle, Info, Loader2, ShieldAlert } from 'lucide-react'
import type { DriftImpact, DriftReport, DriftSeverity } from '@/services/types'
import {
  bySeverity, confidenceLabel, countOf, decide, isBaseline, isUnchanged, kindLabel,
  repairableRenames, verdict, type RepairableRename,
} from '@/lib/driftModel'

/**
 * What re-bootstrapping this source would change, and what it would break.
 *
 * <p>Shown before a re-bootstrap that the drift gate withheld, so the decision is made against
 * the actual impact rather than a yes/no dialog. The panel is deliberately not a diff viewer:
 * a list of moved columns is not the question — "three accepted mappings and a join key depend
 * on this" is.
 */
interface SchemaDriftPanelProps {
  report: DriftReport
  /** Renames the user has ticked for repair, by `fromIri`. */
  selectedRenames: string[]
  onToggleRename: (fromIri: string) => void
  onApplyAnyway: () => void
  onRepairAndApply: () => void
  onCancel: () => void
  busy?: boolean
}

const SEVERITY_STYLE: Record<DriftSeverity, { chip: string; icon: React.ReactNode }> = {
  BREAKING: {
    chip: 'bg-rose-500/10 text-rose-400 border-rose-500/25',
    icon: <ShieldAlert size={12} className="text-rose-400 shrink-0" />,
  },
  WARNING: {
    chip: 'bg-amber-500/10 text-amber-400 border-amber-500/25',
    icon: <AlertTriangle size={12} className="text-amber-400 shrink-0" />,
  },
  INFO: {
    chip: 'bg-sky-500/10 text-sky-400 border-sky-500/25',
    icon: <Info size={12} className="text-sky-400 shrink-0" />,
  },
}

export const SchemaDriftPanel: React.FC<SchemaDriftPanelProps> = ({
  report, selectedRenames, onToggleRename, onApplyAnyway, onRepairAndApply, onCancel, busy,
}) => {
  const worst = verdict(report)
  const decision = decide(report)
  const renames = repairableRenames(report)
  const impacts = bySeverity(report.impacts)

  if (isBaseline(report) || isUnchanged(report)) {
    return (
      <div
        data-testid="drift-panel-clean"
        className="flex items-center gap-2 px-4 py-3 rounded border border-emerald-500/25 bg-emerald-500/5"
      >
        <CheckCircle size={14} className="text-emerald-400 shrink-0" />
        <span className="font-mono text-[11px] text-muted-foreground">{decision.reason}</span>
      </div>
    )
  }

  return (
    <div data-testid="drift-panel" className="flex flex-col gap-3 min-h-0">
      {/* Verdict */}
      <div className={`flex items-start gap-2 px-4 py-3 rounded border ${SEVERITY_STYLE[worst].chip}`}>
        {SEVERITY_STYLE[worst].icon}
        <div className="min-w-0">
          <div className="font-mono text-[11px] font-bold uppercase tracking-wider">
            schema drift — {worst}
          </div>
          <p data-testid="drift-verdict-reason" className="font-mono text-[10px] text-muted-foreground mt-1">
            {decision.reason}
          </p>
          <p className="font-mono text-[10px] text-muted-foreground/70 mt-1">
            {countOf(report, 'BREAKING')} breaking · {countOf(report, 'WARNING')} warning ·{' '}
            {countOf(report, 'INFO')} info
          </p>
        </div>
      </div>

      {/* Changes, worst first */}
      <div className="flex-1 min-h-0 overflow-y-auto space-y-2 pr-1">
        {impacts.map((impact, index) => (
          <ImpactRow key={`${impact.change.kind}-${impact.change.propertyIri ?? impact.change.classIri}-${index}`} impact={impact} />
        ))}
      </div>

      {/* Repairable renames */}
      {renames.length > 0 && (
        <div className="border-t border-border pt-3 space-y-2">
          <div className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
            repair — repoint mappings at the new column
          </div>
          {renames.map(rename => (
            <RenameRow
              key={rename.fromIri}
              rename={rename}
              checked={selectedRenames.includes(rename.fromIri)}
              onToggle={() => onToggleRename(rename.fromIri)}
            />
          ))}
          <p className="font-mono text-[9px] text-muted-foreground/60 leading-relaxed">
            A rename is a proposal, not a finding — check the columns above before repairing.
            Repointing a mapping at the wrong column is worse than the broken reference it replaces.
          </p>
        </div>
      )}

      {/* Actions. The rightmost slot is where a confirm lands, so it holds the RECOMMENDED
          action (repair) — never "apply anyway", which leaves mappings dangling. */}
      <div className="flex items-center justify-end gap-2 border-t border-border pt-3">
        <button
          type="button"
          data-testid="drift-cancel"
          onClick={onCancel}
          disabled={busy}
          className="px-3 py-1.5 rounded font-mono text-[10px] uppercase tracking-wider border border-border text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40"
        >
          Keep current schema
        </button>
        <button
          type="button"
          data-testid="drift-force"
          onClick={onApplyAnyway}
          disabled={busy}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded font-mono text-[10px] uppercase tracking-wider border border-rose-500/30 bg-rose-500/10 text-rose-400 hover:bg-rose-500/20 transition-colors disabled:opacity-40"
        >
          {busy ? <Loader2 size={11} className="animate-spin" /> : null}
          Apply anyway
        </button>
        {renames.length > 0 && (
          <button
            type="button"
            data-testid="drift-repair"
            onClick={onRepairAndApply}
            disabled={busy || selectedRenames.length === 0}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded font-mono text-[10px] uppercase tracking-wider border border-emerald-500/30 bg-emerald-500/10 text-emerald-400 hover:bg-emerald-500/20 transition-colors disabled:opacity-40"
          >
            {busy ? <Loader2 size={11} className="animate-spin" /> : null}
            Repair {selectedRenames.length} &amp; apply
          </button>
        )}
      </div>
    </div>
  )
}

const ImpactRow: React.FC<{ impact: DriftImpact }> = ({ impact }) => {
  const style = SEVERITY_STYLE[impact.severity]
  const confidence = confidenceLabel(impact.change)
  return (
    <div
      data-testid={`drift-impact-${impact.change.kind}`}
      className="flex items-start gap-2 px-3 py-2 rounded border border-border/50 bg-muted/10"
    >
      {style.icon}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`font-mono text-[8px] uppercase tracking-wider font-extrabold px-1.5 rounded-sm border ${style.chip}`}>
            {kindLabel(impact.change)}
          </span>
          {confidence && (
            <span className="font-mono text-[9px] text-muted-foreground/70">{confidence}</span>
          )}
        </div>
        <p className="font-mono text-[11px] text-foreground/90 mt-1 break-words">{impact.change.detail}</p>
        <p className="font-mono text-[10px] text-muted-foreground mt-1 break-words">{impact.reason}</p>
      </div>
    </div>
  )
}

const RenameRow: React.FC<{ rename: RepairableRename; checked: boolean; onToggle: () => void }> = ({
  rename, checked, onToggle,
}) => (
  <label className="flex items-center gap-2 px-3 py-2 rounded border border-border/50 bg-muted/10 cursor-pointer">
    <input
      type="checkbox"
      data-testid={`drift-rename-${rename.fromLabel}`}
      checked={checked}
      onChange={onToggle}
      className="accent-emerald-500"
    />
    <span className="font-mono text-[11px] text-foreground/90 flex items-center gap-1.5 min-w-0">
      <span className="truncate">{rename.fromLabel}</span>
      <ArrowRight size={10} className="text-muted-foreground shrink-0" />
      <span className="truncate">{rename.toLabel}</span>
    </span>
    <span className="font-mono text-[9px] text-muted-foreground ml-auto shrink-0">
      {rename.mappingCount} mapping{rename.mappingCount === 1 ? '' : 's'}
    </span>
  </label>
)
