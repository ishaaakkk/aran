import React from 'react';
import { OChip } from '@/components/OChip';
import { AlertTriangle, ShieldAlert } from 'lucide-react';
import { DatasetFreshness } from './DatasetFreshness';
import type { DatasetRefreshPolicy } from '@/services/types';
import type { AggregatedDataset } from './types';
import type { SourceSchemaColumn } from './sourceSchema';
import { accessLabel, accessNote } from '@/lib/datasetAccess';

/**
 * The dataset's source schema as loaded for the Overview. Carries the dataset it is FOR: an answer
 * for a dataset the user has already left must not render against the one now open, and "still
 * loading" must not read as "no schema" (the refresh-policy state's precedent in DatasetsListView).
 */
export type SourceSchemaState =
  | { datasetId: string; status: 'loading' }
  | { datasetId: string; status: 'error'; message: string }
  | { datasetId: string; status: 'ready'; columns: SourceSchemaColumn[] };

interface DatasetOverviewTabProps {
  dataset: AggregatedDataset;
  /** The dataset's refresh policy, or null when it has no upstream to refresh from. */
  refreshPolicy?: DatasetRefreshPolicy | null;
  /**
   * Whether {@link refreshPolicy} is the answer for THIS dataset yet. False while the request is
   * in flight, because a null policy is a claim ("nothing to refresh from") rather than a
   * placeholder, and making that claim early is worse than saying nothing.
   */
  refreshReady?: boolean;
  onRefreshNow?: () => Promise<void>;
  /** The dataset's live job, in words ("Refreshing…"); null when none runs. */
  refreshBusyLabel?: string | null;
  onSetRefreshPolicy?: (policy: 'MANUAL' | 'EVERY', intervalSeconds?: number) => Promise<void>;
  /** The bootstrapped source schema (J2-F13); null until a load for this dataset starts. */
  schema?: SourceSchemaState | null;
  onRetrySchema?: () => void;
}

/**
 * The dataset's access mode as a chip, shared by the list and the Overview (J2-F3). Live is the
 * one a user most needs to notice — every query reaches the source — so it carries the accent.
 */
export function AccessBadge({ mode, testId }: { mode: AggregatedDataset['accessMode']; testId?: string }) {
  const live = mode === 'LIVE' || mode === 'LIVE_WITH_COPY';
  return (
    <span
      data-testid={testId}
      title={accessNote(mode)}
      className={`font-mono text-[9px] uppercase tracking-wider font-bold px-1.5 py-0.5 rounded-sm border ${live
        ? 'bg-primary/10 text-primary border-primary/30'
        : 'bg-muted/20 text-muted-foreground border-border'}`}
    >
      {accessLabel(mode)}
    </span>
  );
}

/** Why a dataset has no schema yet, in terms of its own lifecycle — never "run profiling" (J2-F13). */
function noSchemaReason(status: string | undefined): string {
  if (status === 'PENDING') return 'Bootstrapping — the schema appears here when it finishes.';
  if (status === 'FAILED') return 'The bootstrap failed, so no schema was extracted. Re-read the source schema from the dataset list.';
  return 'No source schema recorded for this dataset. Bootstrap extracts it from the source.';
}

/** Metadata & schema tab: identifying fields on the left, the bootstrapped source schema on the right. */
export const DatasetOverviewTab: React.FC<DatasetOverviewTabProps> = ({
  dataset,
  refreshPolicy = null,
  refreshReady = false,
  onRefreshNow,
  refreshBusyLabel = null,
  onSetRefreshPolicy,
  schema = null,
  onRetrySchema,
}) => {
  const ownSchema = schema && schema.datasetId === dataset.id ? schema : null;
  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-6 flex-1 overflow-auto">
      {/* Left Metadata Pane */}
      <div className="md:col-span-1 space-y-4 border-b md:border-b-0 md:border-r border-border/40 pb-6 md:pb-0 md:pr-6 overflow-y-auto">
        <div className="space-y-1.5">
          <div className="text-[10px] text-muted-foreground uppercase">Dataset ID</div>
          <div className="text-xs text-foreground font-mono bg-muted/20 px-2.5 py-1.5 rounded border border-border/50 select-all">{dataset.id}</div>
        </div>
        <div className="space-y-1.5">
          <div className="text-[10px] text-muted-foreground uppercase">Name</div>
          <div className="text-xs text-foreground font-semibold font-mono">{dataset.name}</div>
        </div>
        <div className="space-y-1.5">
          <div className="text-[10px] text-muted-foreground uppercase">Description</div>
          <div className="text-xs text-muted-foreground font-mono whitespace-pre-wrap">{dataset.description || 'No description provided.'}</div>
        </div>
        <div className="space-y-1.5">
          <div className="text-[10px] text-muted-foreground uppercase">Associated Project</div>
          <div className="text-xs text-foreground flex items-center gap-1.5 font-mono">
            <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: dataset.projectColor }} />
            {dataset.projectName}
          </div>
        </div>
        <div className="space-y-1.5">
          <div className="text-[10px] text-muted-foreground uppercase">Source Repository</div>
          <div className="text-xs text-foreground font-mono">{dataset.repoName}</div>
        </div>
        <div className="space-y-1.5">
          <div className="text-[10px] text-muted-foreground uppercase">Repository Type</div>
          <div className="text-xs text-foreground font-mono">
            <OChip label={dataset.repoType.replace('_', ' ')} type="class" sm />
          </div>
        </div>
        <div className="space-y-1.5">
          <div className="text-[10px] text-muted-foreground uppercase">Access</div>
          <div className="text-xs text-foreground font-mono flex items-center gap-2" data-testid="dataset-access">
            <AccessBadge mode={dataset.accessMode} />
          </div>
          <div className="text-[10px] text-muted-foreground font-mono leading-relaxed">{accessNote(dataset.accessMode)}</div>
        </div>
        <div className="space-y-1.5">
          <div className="text-[10px] text-muted-foreground uppercase">Snapshot Binding ID</div>
          <div className="text-xs text-foreground font-mono">
            {dataset.snapshotId ? (
              <span className="text-emerald-400 font-bold bg-emerald-500/10 border border-emerald-500/25 px-2 py-0.5 rounded text-[10px]">{dataset.snapshotId}</span>
            ) : (
              <span className="text-muted-foreground italic text-[10px]">
                {dataset.accessMode === 'LIVE' ? 'none — read live' : '[none bound]'}
              </span>
            )}
          </div>
        </div>
        {/* Only when the parent can actually act — a control that reports a policy it cannot change
            is worse than no control. */}
        {refreshReady && onRefreshNow && onSetRefreshPolicy && (
          <DatasetFreshness
            policy={refreshPolicy}
            accessMode={dataset.accessMode}
            onRefreshNow={onRefreshNow}
            onSetPolicy={onSetRefreshPolicy}
            busyLabel={refreshBusyLabel}
          />
        )}
      </div>

      {/* Right: the source schema bootstrap extracted (J2-F13). It used to read Dataset.columns,
          a wire field the backend never sent, so every dataset said "No schema properties stored". */}
      <div className="md:col-span-2 flex flex-col min-h-0 text-foreground" data-testid="dataset-source-schema">
        <h3 className="text-[10px] text-muted-foreground uppercase mb-2 shrink-0">Source schema</h3>
        {!ownSchema || ownSchema.status === 'loading' ? (
          <div className="p-8 border border-dashed border-border rounded flex-1 text-center text-xs text-muted-foreground">
            Loading the source schema…
          </div>
        ) : ownSchema.status === 'error' ? (
          <div role="alert" className="flex flex-col items-center justify-center gap-2 p-8 border border-dashed border-border rounded flex-1 text-center text-xs text-foreground">
            <AlertTriangle size={18} className="text-warning" aria-hidden="true" />
            <div>Could not load the source schema: {ownSchema.message}</div>
            {onRetrySchema && (
              <button type="button" onClick={onRetrySchema}
                className="rounded border border-border px-3 py-1 text-[11px] uppercase tracking-wider hover:bg-muted/20">
                Retry
              </button>
            )}
          </div>
        ) : ownSchema.columns.length > 0 ? (
          <div className="overflow-auto border border-border rounded flex-1">
            <table className="w-full text-xs font-mono">
              <thead>
                <tr className="border-b border-border bg-muted/20">
                  <th className="px-4 py-2 text-left text-[9px] uppercase text-muted-foreground border-r border-border">Class</th>
                  <th className="px-4 py-2 text-left text-[9px] uppercase text-muted-foreground border-r border-border">Property</th>
                  <th className="px-4 py-2 text-left text-[9px] uppercase text-muted-foreground">Type (as bootstrapped)</th>
                </tr>
              </thead>
              <tbody>
                {ownSchema.columns.map((col) => (
                  <tr key={col.iri} title={col.iri} className="border-b border-border/50 last:border-b-0 hover:bg-muted/10 transition-colors">
                    <td className="px-4 py-2 text-muted-foreground border-r border-border/50">{col.className}</td>
                    <td className="px-4 py-2 text-foreground font-semibold border-r border-border/50 select-text">{col.property}</td>
                    <td className="px-4 py-2">
                      {col.datatype ? <OChip label={col.datatype} type="datatype" sm /> : <span className="text-muted-foreground">type not recorded</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center p-8 border border-dashed border-border rounded flex-1 text-center text-xs text-muted-foreground space-y-2">
            <ShieldAlert size={20} className="text-muted-foreground/60" aria-hidden="true" />
            <div>{noSchemaReason(dataset.bootstrapStatus)}</div>
          </div>
        )}
      </div>
    </div>
  );
};

export default DatasetOverviewTab;
