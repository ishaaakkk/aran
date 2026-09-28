import React from 'react';
import { Layers, Database, AlertCircle, X } from 'lucide-react';

interface SourceEnrollmentPanelProps {
  enrolledDatasetIds: string[];
  datasetName: (datasetId: string) => string;
  onRemoveSource: (datasetId: string) => void;
  modelError: string | null;
  /** A failed un-enrol, said here beside the source it concerns (J4-F14), not in another panel. */
  removeError?: string | null;
}

/** The enrolled-sources strip above the left/right dataset panels — the model is
 *  N-ary; the two canvases below show a pair at a time, but every enrolled source
 *  is compiled on Integrate. */
export const SourceEnrollmentPanel: React.FC<SourceEnrollmentPanelProps> = ({
  enrolledDatasetIds,
  datasetName,
  onRemoveSource,
  modelError,
  removeError,
}) => {
  return (
    <div className="flex flex-wrap items-center gap-2 bg-card border border-border rounded-lg px-4 py-2.5 shrink-0">
      <span className="font-mono text-[10px] font-bold uppercase tracking-wider text-muted-foreground shrink-0 flex items-center gap-1">
        <Layers size={11} /> Enrolled sources ({enrolledDatasetIds.length}):
      </span>
      {enrolledDatasetIds.length === 0 ? (
        <span className="font-mono text-[10px] text-muted-foreground/40 italic">
          none yet — selecting a dataset below enrols it in the model
        </span>
      ) : (
        enrolledDatasetIds.map((id) => (
          <span
            key={id}
            className="inline-flex items-center gap-1.5 font-mono text-[10px] bg-success/10 border border-success/30 text-success rounded px-2 py-1"
          >
            <Database size={10} />
            <span className="font-semibold">{datasetName(id)}</span>
            <button
              type="button"
              onClick={() => onRemoveSource(id)}
              title={`Remove ${datasetName(id)} from this model`}
              aria-label={`Remove ${datasetName(id)} from this model`}
              className="text-success/70 hover:text-error transition-colors cursor-pointer border-0 bg-transparent p-0 leading-none rounded focus:outline-none focus:ring-2 focus:ring-ring"
            >
              <X size={11} aria-hidden="true" />
            </button>
          </span>
        ))
      )}
      {modelError && (
        <span className="ml-auto font-mono text-[10px] text-error flex items-center gap-1">
          <AlertCircle size={11} /> {modelError}
        </span>
      )}
      {removeError && (
        <span role="alert" data-testid="align-unenrol-error" className="w-full font-mono text-[10.5px] text-error flex items-center gap-1">
          <AlertCircle size={11} aria-hidden="true" /> {removeError}
        </span>
      )}
    </div>
  );
};

export default SourceEnrollmentPanel;
