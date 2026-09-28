import React from 'react';
import { Link } from 'react-router-dom';
import { InfoTip } from '@/components/InfoTip';
import { ErrorBanner } from '@/components/ui/ErrorBanner';
import { Dialog } from '@/components/ui/Dialog';
import { CONCEPTS } from '@/lib/concepts';
import { ACCESS_MODE_COPY } from '@/lib/productAccessMode';
import type { Project, ProductAccessMode } from '@/services/types';
import { limitOf } from '@/lib/workbenchRun';


/**
 * "Save as data product" — the creation dialog opened from the workbench result panel
 * (`OqlPlanView`'s `onSaveAsProduct`).
 *
 * Extracted out of `OqlPlanView` (Phase 5) so the access-mode choice can be exercised by
 * `renderToStaticMarkup` directly: in `OqlPlanView` this dialog is gated behind local
 * `useState` with no prop to force it open, and this project has no jsdom to click the
 * button that does. State stays in the parent (this is a controlled/presentational
 * component only) — same shape as `UseWithAgentsDialog`, see its test for the same idiom.
 */
interface SaveAsProductDialogProps {
  projects: Project[];
  projectId: string;
  onProjectIdChange: (id: string) => void;
  name: string;
  onNameChange: (name: string) => void;
  description: string;
  onDescriptionChange: (description: string) => void;
  accessMode: ProductAccessMode;
  onAccessModeChange: (mode: ProductAccessMode) => void;
  /** The ACTIVE schema version's number, or null when none is promoted yet. */
  schemaVersionNumber: number | null;
  saving: boolean;
  error: string | null;
  /** The created product's id, once saving succeeds — swaps the form for a confirmation. */
  savedId: string | null;
  onSave: () => void;
  onClose: () => void;
  /**
   * The OQL this product will be defined by — the run whose result is on screen, never the
   * editor's current text (J5-F1). Shown read-only so the definition is what the user reads
   * before saving it.
   */
  definition?: string;
}

export const SaveAsProductDialog: React.FC<SaveAsProductDialogProps> = ({
  projects,
  projectId,
  onProjectIdChange,
  name,
  onNameChange,
  description,
  onDescriptionChange,
  accessMode,
  onAccessModeChange,
  schemaVersionNumber,
  saving,
  error,
  savedId,
  onSave,
  onClose,
  definition,
}) => {
  const definitionLimit = definition ? limitOf(definition) : null;
  return (
    <Dialog
      open
      onClose={onClose}
      label="Save as data product"
      maxWidth="max-w-[460px]"
      className="font-mono"
      testId="save-product-dialog"
    >
        <div className="h-9 flex items-center px-4 bg-muted/30 border-b border-border text-[11px] text-muted-foreground">
          <span className="text-primary">$</span>&nbsp;save as data product
        </div>
        <div className="p-4 space-y-3 text-[12px]">
          {savedId ? (
            <div className="space-y-3">
              <div className="text-success">✓ data product created</div>
              {/* What the user has now, in one sentence (J6-F5): a MATERIALIZED product is a
                  DRAFT with no rows until its first refresh, and no door (JDBC, Iceberg, agents)
                  serves it until then — dbt Cloud / Snowsight say the same of a model or dynamic
                  table that has not run yet. */}
              <div className="text-[11px] text-foreground leading-relaxed" data-testid="product-saved-note">
                {accessMode === 'VIRTUAL'
                  ? 'Readable now — it is recomputed from its sources on every read.'
                  : 'Saved as DRAFT with no rows yet. Refresh it on the Data products page to build its first snapshot — until then JDBC, Iceberg and agents cannot read it.'}
              </div>
              {/* Link text kept: ProductsPage.saveCurrentQueryAsProduct waits for /open data products/. */}
              <Link to={`/products?product=${encodeURIComponent(savedId)}`} className="text-primary underline" onClick={onClose}>
                open data products →
              </Link>
            </div>
          ) : (
            <>
              {definition && (
                <div>
                  <div className="text-[10px] text-muted-foreground mb-1">DEFINITION — the query that produced the result on screen</div>
                  <pre
                    data-testid="product-definition"
                    className="max-h-28 overflow-auto whitespace-pre-wrap break-words bg-muted/40 border border-border rounded px-2 py-1.5 text-[11px] text-foreground select-text"
                  >{definition}</pre>
                  {definitionLimit !== null && (
                    <div data-testid="product-definition-limit" className="mt-1 text-[10px] text-warning">
                      This product will hold at most {definitionLimit.toLocaleString('en-US')} rows: the query&apos;s LIMIT applies. Remove it to keep every row.
                    </div>
                  )}
                </div>
              )}
              <div>
                <label htmlFor="product-save-project" className="block text-[10px] text-muted-foreground mb-1">PROJECT</label>
                <select
                  id="product-save-project"
                  className="w-full bg-muted border border-border rounded px-2 py-1.5 text-foreground"
                  value={projectId}
                  onChange={e => onProjectIdChange(e.target.value)}
                >
                  <option value="">— select a project —</option>
                  {projects.map(p => (
                    <option key={p.id} value={p.id}>{p.name}</option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor="product-save-name" className="block text-[10px] text-muted-foreground mb-1">NAME</label>
                <input
                  id="product-save-name"
                  className="w-full bg-muted border border-border rounded px-2 py-1.5 text-foreground"
                  value={name} onChange={e => onNameChange(e.target.value)}
                  placeholder="papers_by_author"
                />
              </div>
              <div>
                <label htmlFor="product-save-description" className="block text-[10px] text-muted-foreground mb-1">DESCRIPTION</label>
                <input
                  id="product-save-description"
                  className="w-full bg-muted border border-border rounded px-2 py-1.5 text-foreground"
                  value={description} onChange={e => onDescriptionChange(e.target.value)}
                  placeholder="optional"
                />
              </div>
              <div>
                <div className="text-[10px] text-muted-foreground mb-1.5 flex items-center gap-1.5">
                  ACCESS MODE
                  <InfoTip text={CONCEPTS.accessMode} label="access modes" />
                </div>
                <div className="flex flex-col gap-2.5" data-testid="product-access-mode-choice">
                  <label
                    className="flex items-start gap-2 cursor-pointer"
                    aria-label={`Materialized: ${ACCESS_MODE_COPY.MATERIALIZED.choice}`}
                  >
                    <input
                      type="radio"
                      name="product-access-mode"
                      value="MATERIALIZED"
                      className="mt-0.5"
                      data-testid="product-access-mode-materialized"
                      checked={accessMode === 'MATERIALIZED'}
                      onChange={() => onAccessModeChange('MATERIALIZED')}
                    />
                    <span>
                      <span className="text-foreground">Materialized</span>
                      <span className="block text-[10px] text-muted-foreground leading-relaxed">
                        {ACCESS_MODE_COPY.MATERIALIZED.choice}
                      </span>
                    </span>
                  </label>
                  <label
                    className="flex items-start gap-2 cursor-pointer"
                    aria-label={`Virtual: ${ACCESS_MODE_COPY.VIRTUAL.choice}`}
                  >
                    <input
                      type="radio"
                      name="product-access-mode"
                      value="VIRTUAL"
                      className="mt-0.5"
                      data-testid="product-access-mode-virtual"
                      checked={accessMode === 'VIRTUAL'}
                      onChange={() => onAccessModeChange('VIRTUAL')}
                    />
                    <span>
                      <span className="text-foreground">Virtual</span>
                      <span className="block text-[10px] text-muted-foreground leading-relaxed">
                        {ACCESS_MODE_COPY.VIRTUAL.choice}
                      </span>
                    </span>
                  </label>
                </div>
              </div>
              <div className="text-[10px] text-muted-foreground">
                {schemaVersionNumber != null
                  ? `Defined against schema v${schemaVersionNumber}, the active one. A refresh or a virtual read runs under whatever schema is active then, so rows may change; to change the query, create a new product.`
                  : 'No ACTIVE schema version — promote one before saving a product.'}
              </div>
              {error && <ErrorBanner className="p-2">{error}</ErrorBanner>}
              <div className="flex justify-end gap-2 pt-1">
                <button className="btn btn-ghost sm" onClick={onClose}>cancel</button>
                <button
                  className="btn sm"
                  disabled={saving || !projectId || !name.trim()}
                  onClick={onSave}
                >
                  {saving ? 'saving…' : 'save'}
                </button>
              </div>
            </>
          )}
        </div>
    </Dialog>
  );
};
