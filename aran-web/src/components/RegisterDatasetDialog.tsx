import React, {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import { Upload, X, FileText, Loader2, Table2, AlertTriangle } from 'lucide-react';
import { api } from '@/services/api';
import type { Dataset, DatasetCreated, DataRepository, RelationalEngineInfo } from '@/services/types';
import { importKindOf } from '@/lib/singerTap';
import { importModeNote } from '@/lib/repositoryKinds';
import { errorMessage } from '@/lib/errors';
import { Dialog } from '@/components/ui/Dialog';
import { DiscardChangesBar } from '@/components/ui/DiscardChangesBar';
import { dismissOutcome } from '@/lib/discardChanges';
import { readsLive, renameImpact } from '@/lib/datasetAccess';
import { DatasetRenameNote } from '@/components/datasets/DatasetRenameNote';

/** Public handle — parent calls dialogRef.current.open(existingDs?). */
export interface RegisterDatasetDialogHandle {
  open: (existing?: Dataset) => void;
}

interface RegisterDatasetDialogProps {
  projectId: string;
  repositoryId: string;
  /**
   * The repository the dataset is being registered against. When it is a
   * relational repository whose engine's extract is built in this release, an
   * "Import from table" mode is offered alongside file upload — a snapshot copy
   * in the lake. `undefined` while nothing is selected.
   */
  repository?: DataRepository;
  /**
   * Called after a successful save. A file upload or table import passes the
   * created dataset, whose `bootstrapJobId` is the only handle on the job now
   * running — the parent needs it to follow the job rather than poll blindly.
   */
  onSave: (created?: DatasetCreated) => void;
  /**
   * Names the repository already holds. A picked table that is already registered is greyed,
   * and a typed name that is taken is refused before a byte is uploaded — the server answers
   * 409 too, but only after the upload (UI audit J2-F9 interim, J2-F21).
   */
  registeredNames?: string[];
}

export const RegisterDatasetDialog = forwardRef<
  RegisterDatasetDialogHandle,
  RegisterDatasetDialogProps
>(({ projectId, repositoryId, repository, onSave, registeredNames = [] }, ref) => {
  const [visible, setVisible] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  /** The dataset as it was when the edit opened — what a rename is measured against (UI-OD8). */
  const [editingOriginal, setEditingOriginal] = useState<Dataset | null>(null);

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  /* ---- import-by-name mode ---------------------------------------- */
  /** What each relational engine can do (`GET /api/relational-engines`); null until it loads. */
  const [engines, setEngines] = useState<RelationalEngineInfo[] | null>(null);
  /** Why the capability read failed — said aloud, so upload-only is not mistaken for "no import". */
  const [enginesError, setEnginesError] = useState<string | null>(null);
  const importKind = importKindOf(repository, engines);
  const canImport = importKind !== null;
  const [mode, setMode] = useState<'upload' | 'import'>('upload');
  const [tables, setTables] = useState<string[] | null>(null);
  const [tablesLoading, setTablesLoading] = useState(false);
  const [selectedTable, setSelectedTable] = useState<string>('');

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /* ---- dismissal with edits (J2-F26) ------------------------------ */
  const [initialText, setInitialText] = useState({ name: '', description: '' });
  const [confirmingDiscard, setConfirmingDiscard] = useState(false);

  /* Lazily scan the repository the first time import mode opens — from the click that opens it,
     not an effect watching `mode` (react.dev/learn/you-might-not-need-an-effect). For a tap this
     RUNS the tap's discovery, which is why it is not done on mount. */
  const openImportMode = () => {
    setMode('import');
    if (tables !== null || tablesLoading) return;
    setTablesLoading(true);
    api
      .scanDatasets(projectId, repositoryId)
      .then((t) => setTables(t))
      .catch((err) => setError(errorMessage(err, `Failed to scan ${importKind ?? 'table'}s.`)))
      .finally(() => setTablesLoading(false));
  };

  /* The engine capabilities decide whether a relational repository offers table import (J2-F7).
     Read once per mount, only for a relational repository. A failed read offers upload only —
     claiming an import the build may not have is worse than not offering one — and says so, with
     a retry: silently dropping the option would read as the engine having none. */
  useEffect(() => {
    if (!visible || repository?.type !== 'RELATIONAL_DB' || engines !== null) return;
    let cancelled = false;
    api.listRelationalEngines()
      .then((e) => { if (!cancelled) setEngines(e); })
      .catch((err) => {
        if (!cancelled) {
          setEngines([]);
          setEnginesError(errorMessage(err, 'the request failed'));
        }
      });
    return () => { cancelled = true; };
  }, [visible, repository?.type, engines]);

  useImperativeHandle(ref, () => ({
    open(existing?: Dataset) {
      setError(null);
      setFile(null);
      setDragOver(false);
      setSaving(false);
      setMode('upload');
      setTables(null);
      setSelectedTable('');
      setConfirmingDiscard(false);
      if (existing) {
        setEditingId(existing.id);
        setEditingOriginal(existing);
        setName(existing.name);
        setDescription(existing.description);
        setInitialText({ name: existing.name, description: existing.description });
      } else {
        setEditingId(null);
        setEditingOriginal(null);
        setName('');
        setDescription('');
        setInitialText({ name: '', description: '' });
      }
      setVisible(true);
    },
  }));

  // Edited = anything the user chose or typed since open(): a file, a table, a changed name or
  // description. Dismissing then asks first (J2-F26).
  const dirty = file !== null || selectedTable !== ''
    || name !== initialText.name || description !== initialText.description;

  const close = useCallback(() => {
    if (saving) return;
    const outcome = dismissOutcome({ dirty, asking: confirmingDiscard });
    if (outcome === 'close') setVisible(false);
    else setConfirmingDiscard(outcome === 'ask');
  }, [saving, dirty, confirmingDiscard]);

  const handleFileChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const f = e.target.files?.[0] ?? null;
      setFile(f);
    },
    [],
  );

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const f = e.dataTransfer.files?.[0] ?? null;
    setFile(f);
  }, []);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(true);
  }, []);

  const handleDragLeave = useCallback(() => {
    setDragOver(false);
  }, []);

  const removeFile = useCallback(() => {
    setFile(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  }, []);

  const handleSave = useCallback(async () => {
    // Import-by-name: the name comes from what was picked, not from the name field.
    if (!editingId && canImport && mode === 'import') {
      if (!selectedTable) {
        setError(`Select a ${importKind} to import.`);
        return;
      }
      setSaving(true);
      setError(null);
      try {
        const created = importKind === 'stream'
          ? await api.importDatasetFromStream(projectId, repositoryId, selectedTable)
          : importKind === 'iceberg table'
            ? await api.importDatasetFromIcebergTable(projectId, repositoryId, selectedTable)
            : await api.importDatasetFromTable(projectId, repositoryId, selectedTable);
        setVisible(false);
        onSave(created);
      } catch (err: unknown) {
        setError(errorMessage(err, `Failed to import ${importKind}.`));
      } finally {
        setSaving(false);
      }
      return;
    }

    const trimmedName = name.trim();
    if (!trimmedName) {
      setError('Dataset name is required.');
      return;
    }
    if (!editingId && registeredNames.includes(trimmedName)) {
      setError(`A dataset named '${trimmedName}' already exists in this repository. Choose another name.`);
      return;
    }

    setSaving(true);
    setError(null);

    try {
      let created: DatasetCreated | undefined;
      if (editingId) {
        await api.updateDataset(projectId, repositoryId, editingId, {
          name: trimmedName,
          description: description.trim(),
        });
      } else if (file) {
        created = await api.createDatasetWithFile(projectId, repositoryId, {
          name: trimmedName,
          description: description.trim(),
        }, file);
      } else {
        created = await api.createDataset(projectId, repositoryId, {
          name: trimmedName,
          description: description.trim(),
        });
      }
      setVisible(false);
      onSave(created);
    } catch (err: unknown) {
      setError(errorMessage(err, 'Failed to save dataset.'));
    } finally {
      setSaving(false);
    }
  }, [name, description, file, editingId, canImport, importKind, mode, selectedTable, projectId, repositoryId, onSave, registeredNames]);

  if (!visible) return null;

  const isEditing = editingId !== null;
  const rename = isEditing && editingOriginal
    ? renameImpact(readsLive(repository, editingOriginal), editingOriginal.name, name)
    : 'none';

  // J2-F26: on the shared Dialog (Escape, focus trap, a name), and a dismissal with edits asks
  // first instead of dropping a chosen file or a typed name.
  return (
    <Dialog
      open
      onClose={close}
      label={isEditing ? 'Edit dataset' : 'Register dataset'}
      maxWidth="max-w-lg"
      className="relative mx-4 rounded-md"
    >
          <div className="flex items-center justify-between border-b border-border px-5 py-3">
            <h2 className="font-mono text-xs uppercase tracking-widest text-foreground">
              {isEditing ? 'edit dataset' : 'register dataset'}
            </h2>
            <button
              onClick={close}
              disabled={saving}
              className="text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40"
              aria-label="Close"
            >
              <X size={16} />
            </button>
          </div>

          <div className="px-5 py-4 space-y-4">
            {/* Mode selector — a PostgreSQL table or a Singer stream, or upload a file. */}
            {!isEditing && canImport && (
              <div className="space-y-1">
                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    onClick={() => setMode('upload')}
                    disabled={saving}
                    className={`flex items-center justify-center gap-2 rounded border px-3 py-2 font-mono text-[10px] uppercase tracking-widest transition-colors disabled:opacity-40 ${
                      mode === 'upload'
                        ? 'border-primary bg-primary/10 text-foreground'
                        : 'border-border text-muted-foreground hover:text-foreground'
                    }`}
                  >
                    <Upload size={12} /> Upload file
                  </button>
                  <button
                    type="button"
                    data-testid="dataset-mode-import"
                    onClick={openImportMode}
                    disabled={saving}
                    className={`flex items-center justify-center gap-2 rounded border px-3 py-2 font-mono text-[10px] uppercase tracking-widest transition-colors disabled:opacity-40 ${
                      mode === 'import'
                        ? 'border-primary bg-primary/10 text-foreground'
                        : 'border-border text-muted-foreground hover:text-foreground'
                    }`}
                  >
                    <Table2 size={12} /> Import from {importKind}
                  </button>
                </div>
                <p className="font-mono text-[10px] leading-relaxed text-muted-foreground pt-1">
                  {importKind && importModeNote(importKind, repository?.type === 'RELATIONAL_DB' && repository.federated === true)}
                </p>
              </div>
            )}

            {!isEditing && enginesError && (
              <TableImportUnchecked
                message={enginesError}
                onRetry={() => { setEnginesError(null); setEngines(null); }}
              />
            )}

            {!isEditing && canImport && mode === 'import' ? (
              <div className="space-y-1">
                <label htmlFor="selectedTable" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  {importKind}
                </label>
                {tablesLoading ? (
                  <div className="flex items-center gap-2 text-muted-foreground font-mono text-xs py-2">
                    <Loader2 size={12} className="animate-spin" />
                    {importKind === 'stream' ? ' running tap discovery…' : ' scanning tables…'}
                  </div>
                ) : (
                  <select
                    id="selectedTable"
                    data-testid="dataset-table-select"
                    value={selectedTable}
                    onChange={(e) => setSelectedTable(e.target.value)}
                    disabled={saving}
                    className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
                  >
                    <option value="">— select a {importKind} —</option>
                    {(tables ?? []).map((t) => (
                      <option key={t} value={t} disabled={registeredNames.includes(t)}>
                        {registeredNames.includes(t) ? `${t} — already registered` : t}
                      </option>
                    ))}
                  </select>
                )}
                {tables !== null && tables.length === 0 && !tablesLoading && (
                  <p className="font-mono text-[10px] text-muted-foreground pt-1">
                    No {importKind}s visible in this repository.
                  </p>
                )}

                {error && <p className="font-mono text-xs text-red-400 pt-1">{error}</p>}
              </div>
            ) : (
            <>
            <div className="space-y-1">
              <label htmlFor="datasetName" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                name
              </label>
              <input
                id="datasetName"
                type="text"
                data-testid="dataset-name-input"
                value={name}
                onChange={(e) => setName(e.target.value)}
                disabled={saving}
                readOnly={rename === 'blocked'}
                aria-describedby={rename === 'none' ? undefined : 'dataset-rename-note'}
                placeholder="e.g. customers_2024"
                className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50 read-only:opacity-70 read-only:cursor-not-allowed"
              />
              <DatasetRenameNote impact={rename} id="dataset-rename-note" />
            </div>

            <div className="space-y-1">
              <label htmlFor="datasetDescription" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                description
              </label>
              <textarea
                id="datasetDescription"
                data-testid="dataset-description-input"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                disabled={saving}
                rows={3}
                placeholder="Optional description..."
                className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
              />
            </div>

            {!isEditing && (
              <div className="space-y-1">
                <label htmlFor="datasetFile" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  file (optional)
                </label>

                {file ? (
                  <div className="flex items-center gap-2 rounded border border-border bg-background px-3 py-2">
                    <FileText size={14} className="text-primary shrink-0" />
                    <span className="font-mono text-xs text-foreground truncate flex-1">
                      {file.name}
                    </span>
                    <span className="font-mono text-[10px] text-muted-foreground shrink-0">
                      {(file.size / 1024).toFixed(1)} KB
                    </span>
                    <button
                      onClick={removeFile}
                      disabled={saving}
                      className="text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40"
                      aria-label="Remove file"
                    >
                      <X size={12} />
                    </button>
                  </div>
                ) : (
                  <div
                    onDrop={handleDrop}
                    onDragOver={handleDragOver}
                    onDragLeave={handleDragLeave}
                    onClick={() => fileInputRef.current?.click()}
                    role="button"
                    tabIndex={0}
                    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') fileInputRef.current?.click(); }}
                    className={`flex flex-col items-center justify-center gap-2 rounded border-2 border-dashed px-4 py-6 cursor-pointer transition-colors ${
                      dragOver
                        ? 'border-primary bg-primary/5'
                        : 'border-border hover:border-muted-foreground'
                    }`}
                  >
                    <Upload
                      size={20}
                      className={dragOver ? 'text-primary' : 'text-muted-foreground'}
                    />
                    <span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                      drop file here or click to browse
                    </span>
                  </div>
                )}

                <input
                  id="datasetFile"
                  ref={fileInputRef}
                  type="file"
                  data-testid="dataset-file-input"
                  onChange={handleFileChange}
                  className="hidden"
                />
              </div>
            )}

            {error && (
              <p role="alert" className="font-mono text-xs text-error">{error}</p>
            )}
            </>
            )}
          </div>

          <div className="flex items-center justify-end gap-2 border-t border-border px-5 py-3">
            <button
              onClick={close}
              disabled={saving}
              className="rounded border border-border px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-muted-foreground hover:text-foreground hover:border-foreground transition-colors disabled:opacity-40"
            >
              cancel
            </button>
            <button
              data-testid="dataset-submit"
              onClick={handleSave}
              disabled={saving}
              className="flex items-center gap-2 rounded bg-primary px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-60"
            >
              {saving && <Loader2 size={12} className="animate-spin" />}
              {isEditing ? 'update' : (canImport && mode === 'import') ? 'import' : 'register'}
            </button>
          </div>
          {confirmingDiscard && (
            <DiscardChangesBar
              what={isEditing ? 'this dataset' : 'the new dataset'}
              onKeep={() => setConfirmingDiscard(false)}
              onDiscard={() => { setConfirmingDiscard(false); setVisible(false); }}
            />
          )}
    </Dialog>
  );
});

RegisterDatasetDialog.displayName = 'RegisterDatasetDialog';

export default RegisterDatasetDialog;

/**
 * The capability read failed, so table import could not be checked: upload stays on offer, and
 * the note says why import is missing instead of letting its absence pass for "not supported".
 */
export function TableImportUnchecked({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div
      role="status"
      data-testid="dataset-import-unchecked"
      className="flex items-center gap-2 rounded border border-warning/40 bg-warning/5 px-3 py-2 font-mono text-[10px] leading-relaxed text-foreground"
    >
      <AlertTriangle size={12} className="text-warning shrink-0" aria-hidden="true" />
      <span className="flex-1">
        Could not check whether this engine supports table import ({message}), so only file
        upload is offered.
      </span>
      <button
        type="button"
        onClick={onRetry}
        className="rounded border border-border px-2 py-0.5 uppercase tracking-wider hover:bg-muted/20"
      >
        Retry
      </button>
    </div>
  );
}
