import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useState,
} from 'react';
import { X, CheckCircle2, AlertTriangle, Loader2 } from 'lucide-react';
import { api } from '@/services/api';
import { ErrorNotice } from './ErrorNotice';
import type { DataRepository, RelationalEngineInfo, RepositoryInput, RepositoryType, SingerTapInfo } from '@/services/types';
import { engineCanIngest, relationalIngestionModes } from '@/services/types';
import { MASKED_SECRET, tapCatalogState, tapConfigPayload } from '@/lib/singerTap';
import { InfoTip } from '@/components/InfoTip';
import { CONCEPTS } from '@/lib/concepts';
import { errorMessage } from '@/lib/errors';
import { Dialog } from '@/components/ui/Dialog';
import { DiscardChangesBar } from '@/components/ui/DiscardChangesBar';
import { dismissOutcome } from '@/lib/discardChanges';
import {
  connectionTestPayload,
  FEDERATED_REPOSITORY_NOTE,
  OBJECT_STORAGE_NOTE,
  OBJECT_STORAGE_PROVIDERS,
  REPOSITORY_TYPE_LABELS,
  REPOSITORY_TYPE_ORDER,
} from '@/lib/repositoryKinds';

export interface RegisterRepositoryDialogHandle {
  open: (existing?: DataRepository) => void;
}

interface RegisterRepositoryDialogProps {
  projectId: string;
  /** Called with the repository as the server stored it. */
  onSave: (saved: DataRepository) => void;
}

export const RegisterRepositoryDialog = forwardRef<
  RegisterRepositoryDialogHandle,
  RegisterRepositoryDialogProps
>(({ projectId, onSave }, ref) => {
  const [visible, setVisible] = useState(false);
  const [editingRepoId, setEditingRepoId] = useState<string | null>(null);

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [type, setType] = useState<RepositoryType>('RELATIONAL_DB');

  /**
   * What each database engine can do, per `GET /api/relational-engines`. `null` until it loads.
   *
   * The engine dropdown used to be six hardcoded options with a hardcoded port table, which said
   * nothing about the fact that some engines register, introspect, and cannot move a row (HBase
   * still cannot). That is the same overclaim the repository-type list had, one level down.
   */
  const [engines, setEngines] = useState<RelationalEngineInfo[] | null>(null);

  const [engine, setEngine] = useState('POSTGRESQL');
  // J2-F27: a placeholder, not a value — a pre-filled "localhost" was submitted by anyone who
  // did not notice it.
  const [host, setHost] = useState('');
  const [port, setPort] = useState(5432);
  const [database, setDatabase] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [schema, setSchema] = useState('public');
  const [federated, setFederated] = useState(false);

  const [provider, setProvider] = useState('S3');
  const [endpoint, setEndpoint] = useState('');
  const [bucketName, setBucketName] = useState('');
  const [accessKey, setAccessKey] = useState('');
  const [secretKey, setSecretKey] = useState('');
  const [region, setRegion] = useState('us-east-1');

  // Singer tap fields. `taps` is null until GET /api/singer-taps answers, and an EMPTY array is
  // a real answer meaning the feature is off — the two must not render the same.
  const [taps, setTaps] = useState<SingerTapInfo[] | null>(null);
  const [tapsError, setTapsError] = useState<unknown>(null);
  const [tapsAttempt, setTapsAttempt] = useState(0);
  const [tapId, setTapId] = useState('');
  const [tapConfig, setTapConfig] = useState<Record<string, string>>({});

  // Iceberg REST catalog fields (Gap 2). The data-plane credentials reuse the object-storage
  // state (provider/endpoint/accessKey/secretKey/region) — same concept, same columns server-side.
  const [catalogUri, setCatalogUri] = useState('');
  const [warehouse, setWarehouse] = useState('');
  const [catalogToken, setCatalogToken] = useState('');

  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [testError, setTestError] = useState<string | null>(null);

  // J2-F26: any field the user changes marks the form edited (a change event from any control
  // in the form body bubbles to one handler); dismissing an edited form asks first.
  const [dirty, setDirty] = useState(false);
  const [confirmingDiscard, setConfirmingDiscard] = useState(false);

  const onTypeChange = (newType: RepositoryType) => {
    setType(newType);
    setTestResult(null);
    setTestError(null);
    if (newType === 'RELATIONAL_DB') {
      setEngine('POSTGRESQL');
      setHost('');
      setPort(5432);
      setFederated(false);
    } else if (newType === 'OBJECT_STORAGE') {
      setProvider('S3');
      setRegion('us-east-1');
    } else if (newType === 'SINGER_TAP') {
      setTapId('');
      setTapConfig({});
    } else if (newType === 'ICEBERG_REST') {
      setCatalogUri('');
      setWarehouse('');
      setCatalogToken('');
      setProvider('S3');
      setEndpoint('');
      setAccessKey('');
      setSecretKey('');
      setRegion('us-east-1');
    }
  };

  /** The tap currently selected, or null while the catalogue is loading or none is chosen. */
  const selectedTap = taps?.find((t) => t.id === tapId) ?? null;

  const onTapChange = (newTapId: string) => {
    setTapId(newTapId);
    // Settings belong to a tap, so switching taps discards them rather than carrying values a
    // different tap would refuse as unknown keys.
    setTapConfig({});
  };

  const onRelationalEngineChange = (newEngine: string) => {
    setEngine(newEngine);
    // The port a database listens on is a property of the engine, so the engine owns it. This
    // was a hardcoded ladder that a new engine had to remember to extend.
    const known = engines?.find((e) => e.id === newEngine);
    if (known) setPort(known.defaultPort);
  };

  /** The selected engine's published capability, or null while the list is loading. */
  const selectedEngine = engines?.find((e) => e.id === engine) ?? null;

  // Loaded once the dialog is first opened rather than on mount: the answer is a constant of the
  // build, and fetching it before the user has opened anything spends a request on most sessions
  // that never register a repository.
  useEffect(() => {
    if (!visible || engines !== null) return;
    let cancelled = false;
    api
      .listRelationalEngines()
      .then((list) => { if (!cancelled) setEngines(list); })
      .catch(() => {
        // Leave it null: the form falls back to the engine already selected and makes no
        // capability claim, rather than blanking a dropdown because one request failed.
      });
    return () => {
      cancelled = true;
    };
  }, [visible, engines]);

  // Same lazy shape as the list above: fetched when the dialog first opens. A failure leaves the
  // list null — the form never asserts the feature is off because one request failed — and is
  // said, with a retry, where the tap picker would be (J2-F32).
  useEffect(() => {
    if (!visible || taps !== null) return;
    let cancelled = false;
    api
      .listSingerTaps()
      .then((list) => { if (!cancelled) { setTaps(list); setTapsError(null); } })
      .catch((err: unknown) => { if (!cancelled) setTapsError(err); });
    return () => {
      cancelled = true;
    };
  }, [visible, taps, tapsAttempt]);

  const tapState = tapCatalogState(taps, tapsError != null);

  useImperativeHandle(ref, () => ({
    open(existing?: DataRepository) {
      setTestResult(null);
      setTestError(null);
      setTesting(false);
      setSaving(false);
      setDirty(false);
      setConfirmingDiscard(false);

      if (existing) {
        setEditingRepoId(existing.id);
        setName(existing.name || '');
        setDescription(existing.description || '');
        setType(existing.type);

        if (existing.type === 'RELATIONAL_DB') {
          setEngine(existing.engine || 'POSTGRESQL');
          setHost(existing.host || '');
          setPort(existing.port || 5432);
          setDatabase(existing.database || '');
          setUsername(existing.username || '');
          setPassword(existing.password || '');
          setSchema(existing.schema || 'public');
          setFederated(existing.federated || false);
        } else if (existing.type === 'OBJECT_STORAGE') {
          setProvider(existing.provider || 'S3');
          setEndpoint(existing.endpoint || '');
          setBucketName(existing.bucketName || '');
          setAccessKey(existing.accessKey || '');
          setSecretKey(existing.secretKey || '');
          setRegion(existing.region || 'us-east-1');
        } else if (existing.type === 'ICEBERG_REST') {
          setCatalogUri(existing.catalogUri || '');
          setWarehouse(existing.warehouse || '');
          // Both credentials arrive masked; kept as-is so an unchanged submit sends the
          // sentinel back and the server keeps each stored credential independently.
          setCatalogToken(existing.token || '');
          setProvider(existing.provider || 'S3');
          setEndpoint(existing.endpoint || '');
          setAccessKey(existing.accessKey || '');
          setSecretKey(existing.secretKey || '');
          setRegion(existing.region || 'us-east-1');
        } else if (existing.type === 'SINGER_TAP') {
          setTapId(existing.tapId || '');
          // Secret values arrive masked. Kept as-is so an unchanged submit sends the sentinel
          // back and the server keeps the stored credential.
          setTapConfig({ ...(existing.config || {}) });
        }
      } else {
        setEditingRepoId(null);
        setName('');
        setDescription('');
        onTypeChange('RELATIONAL_DB');
      }
      setVisible(true);
    },
  }));

  const close = () => {
    if (saving || testing) return;
    const outcome = dismissOutcome({ dirty, asking: confirmingDiscard });
    if (outcome === 'close') setVisible(false);
    else setConfirmingDiscard(outcome === 'ask');
  };

  const getPayload = (): RepositoryInput => {
    const common = { name, description };

    // A switch over the full RepositoryType union: each case pins `type` to its
    // literal, which is what makes the result a valid member of RepositoryInput.
    switch (type) {
      case 'RELATIONAL_DB':
        return { ...common, type, engine, host, port, database, username, password, schema, federated };
      case 'OBJECT_STORAGE':
        return { ...common, type, provider, endpoint, bucketName, accessKey, secretKey, region };
      case 'FILE_SYSTEM':
        return { ...common, type };
      case 'SINGER_TAP':
        return { ...common, type, tapId, config: tapConfigPayload(selectedTap, tapConfig) };
      case 'ICEBERG_REST':
        return { ...common, type, catalogUri, warehouse, token: catalogToken, provider, endpoint, accessKey, secretKey, region };
    }
  };

  const testRepoConnection = async () => {
    setTesting(true);
    setTestResult(null);
    setTestError(null);

    try {
      // Editing a saved repository: its credentials arrive masked, so the test goes to the stored
      // repository's own route, which takes each masked value from storage (J2-F1). Posting the
      // form to the body-only route would dial '********' as the password.
      const res = editingRepoId
        ? await api.testStoredConnection(projectId, editingRepoId, getPayload())
        : await api.testConnection(projectId, connectionTestPayload(getPayload()));
      if (res.success) {
        setTestResult(res.message || 'Connection successful! Dynamic driver resolved and active ping succeeded.');
      } else {
        const detail = res.details ? `\n${res.details}` : '';
        setTestError(
          (res.message || 'Connection failed. Please check host, port, credentials, and network logs.') + detail
        );
      }
    } catch (err: unknown) {
      setTestError(
        errorMessage(err, 'Network error occurred during connection testing.')
      );
    } finally {
      setTesting(false);
    }
  };

  const saveRepository = async () => {
    setSaving(true);
    setTestError(null);
    try {
      const saved = editingRepoId
        ? await api.updateRepository(projectId, editingRepoId, getPayload())
        : await api.createRepository(projectId, getPayload());
      onSave(saved);
      setVisible(false);
    } catch (err: unknown) {
      setTestError(errorMessage(err, 'Error occurred while saving repository.'));
    } finally {
      setSaving(false);
    }
  };

  if (!visible) return null;

  // J2-F26: on the shared Dialog (Escape, focus trap, a name); a dismissal with edits asks first
  // instead of dropping a filled-in form on one stray click.
  return (
    <Dialog
      open
      onClose={close}
      label={editingRepoId ? 'Edit data repository' : 'Register data repository'}
      maxWidth="max-w-lg"
      className="relative mx-4 rounded text-foreground"
    >
        <div className="flex items-center justify-between border-b border-border bg-muted/20 px-5 py-3">
          <h2 className="font-mono text-xs uppercase tracking-widest text-foreground flex items-center gap-1.5">
            <span className="text-primary font-bold">#</span>
            {editingRepoId ? 'edit data repository' : 'register data repository'}
          </h2>
          <button
            onClick={close}
            disabled={saving || testing}
            aria-label="Close"
            className="text-muted-foreground hover:text-foreground transition-colors disabled:opacity-40"
          >
            <X size={16} />
          </button>
        </div>

        <div className="px-5 py-4 space-y-4 max-h-[60vh] overflow-y-auto" onChange={() => setDirty(true)}>
          <div className="space-y-1">
            <label htmlFor="repoName" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
              Repository Name
            </label>
            <input
              id="repoName"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={saving || testing}
              placeholder="e.g. Production Analytics DB"
              data-testid="repo-name-input"
              className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
            />
          </div>

          <div className="space-y-1">
            <label htmlFor="repoDesc" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
              Description
            </label>
            <textarea
              id="repoDesc"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              disabled={saving || testing}
              rows={2}
              placeholder="Describe the contents or environment..."
              data-testid="repo-description-input"
              className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground resize-none focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
            />
          </div>

          <div className="space-y-1">
            <label htmlFor="repoType" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
              Repository Type
            </label>
            <select
              id="repoType"
              value={type}
              onChange={(e) => onTypeChange(e.target.value as RepositoryType)}
              disabled={saving || testing || editingRepoId !== null}
              data-testid="repo-type-select"
              className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
            >
              {REPOSITORY_TYPE_ORDER.map((t) => {
                // A kind this instance cannot serve is shown disabled with its reason, not
                // offered and then refused after it is picked (J2-F32).
                const off = t === 'SINGER_TAP' && tapState === 'off' && type !== 'SINGER_TAP';
                return (
                  <option key={t} value={t} disabled={off}>
                    {REPOSITORY_TYPE_LABELS[t]}{off ? ' — none configured on this instance' : ''}
                  </option>
                );
              })}
            </select>

            {type === 'RELATIONAL_DB' && (
              <p
                className="font-mono text-[10px] leading-relaxed text-muted-foreground pt-1"
                data-testid="repo-relational-ingestion-hint"
              >
                {/* Per selected engine, not a fixed sentence. The fixed one named PostgreSQL as
                    the only table import, which stopped being true the moment the generic JDBC
                    extract shipped — and nothing would have flagged it. */}
                Ingestion: {relationalIngestionModes(selectedEngine).join(', ')}.
              </p>
            )}
          </div>

          {type === 'RELATIONAL_DB' && (
            <div className="grid grid-cols-12 gap-3 pt-2 border-t border-border/40">
              <div className="col-span-5 space-y-1">
                <label htmlFor="repoEngine" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  Engine
                </label>
                <select
                  id="repoEngine"
                  value={engine}
                  onChange={(e) => onRelationalEngineChange(e.target.value)}
                  disabled={saving || testing}
                  className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
                >
                  {/* Server-published. Engines that cannot move a row say so here rather than
                      after a user has filled in credentials and waited for a bootstrap. */}
                  {(engines ?? []).map((e) => (
                    <option key={e.id} value={e.id}>
                      {e.displayName}
                      {engineCanIngest(e) ? '' : ' — connect only'}
                    </option>
                  ))}
                  {engines === null && <option value={engine}>{engine}</option>}
                </select>
              </div>

              <div className="col-span-4 space-y-1">
                <label htmlFor="repoHost" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  Host
                </label>
                <input
                  id="repoHost"
                  type="text"
                  value={host}
                  onChange={(e) => setHost(e.target.value)}
                  disabled={saving || testing}
                  placeholder="db.example.org"
                  data-testid="repo-host-input"
                  className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                />
              </div>

              <div className="col-span-3 space-y-1">
                <label htmlFor="repoPort" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  Port
                </label>
                <input
                  id="repoPort"
                  type="number"
                  value={port}
                  onChange={(e) => setPort(parseInt(e.target.value) || 0)}
                  disabled={saving || testing}
                  data-testid="repo-port-input"
                  className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                />
              </div>

              <div className="col-span-6 space-y-1">
                <label htmlFor="repoDatabase" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  Database Name
                </label>
                <input
                  id="repoDatabase"
                  type="text"
                  value={database}
                  onChange={(e) => setDatabase(e.target.value)}
                  disabled={saving || testing}
                  placeholder="e.g. aran"
                  data-testid="repo-database-input"
                  className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                />
              </div>

              <div className="col-span-6 space-y-1">
                <label htmlFor="repoSchema" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  Schema
                </label>
                <input
                  id="repoSchema"
                  type="text"
                  value={schema}
                  onChange={(e) => setSchema(e.target.value)}
                  disabled={saving || testing}
                  placeholder="public"
                  data-testid="repo-schema-input"
                  className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                />
              </div>

              <div className="col-span-6 space-y-1">
                <label htmlFor="repoUsername" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  Username
                </label>
                <input
                  id="repoUsername"
                  type="text"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  disabled={saving || testing}
                  placeholder="postgres"
                  data-testid="repo-username-input"
                  className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                />
              </div>

              <div className="col-span-6 space-y-1">
                <label htmlFor="repoPassword" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  Password
                </label>
                <input
                  id="repoPassword"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={saving || testing}
                  data-testid="repo-password-input"
                  className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                />
              </div>

              {(selectedEngine ? selectedEngine.supportsFederation : ['POSTGRESQL', 'MYSQL', 'MARIADB'].includes(engine)) && (
                <div className="col-span-12 space-y-1 pt-2">
                  <label htmlFor="repoFederated" className="flex items-center gap-2 font-mono text-sm text-foreground cursor-pointer">
                    <input
                      id="repoFederated"
                      type="checkbox"
                      checked={federated}
                      onChange={(e) => setFederated(e.target.checked)}
                      disabled={saving || testing}
                      className="rounded border-border text-primary focus:ring-primary bg-background"
                    />
                    <span>Federated Execution</span>
                    <InfoTip text={CONCEPTS.federation} label="federated execution" />
                  </label>
                  <p className="font-mono text-[10px] text-muted-foreground ml-6 leading-tight">
                    {FEDERATED_REPOSITORY_NOTE}
                  </p>
                </div>
              )}
            </div>
          )}

          {type === 'OBJECT_STORAGE' && (
            <div className="space-y-3 pt-2 border-t border-border/40">
              <p
                className="font-mono text-[10px] leading-relaxed text-muted-foreground"
                data-testid="repo-object-storage-note"
              >
                {OBJECT_STORAGE_NOTE}
              </p>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label htmlFor="repoProvider" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Cloud Provider
                  </label>
                  <select
                    id="repoProvider"
                    value={provider}
                    onChange={(e) => setProvider(e.target.value)}
                    disabled={saving || testing}
                    className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                  >
                    {OBJECT_STORAGE_PROVIDERS.map((p) => (
                      <option key={p.value} value={p.value}>{p.label}</option>
                    ))}
                  </select>
                </div>

                <div className="space-y-1">
                  <label htmlFor="repoBucket" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Bucket Name
                  </label>
                  <input
                    id="repoBucket"
                    type="text"
                    value={bucketName}
                    onChange={(e) => setBucketName(e.target.value)}
                    disabled={saving || testing}
                    placeholder="my-aran-lake"
                    className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                  />
                </div>
              </div>

              <div className="space-y-1">
                <label htmlFor="repoEndpoint" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  Custom Endpoint (optional)
                </label>
                <input
                  id="repoEndpoint"
                  type="text"
                  value={endpoint}
                  onChange={(e) => setEndpoint(e.target.value)}
                  disabled={saving || testing}
                  placeholder="e.g. http://localhost:9000 (MinIO)"
                  className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label htmlFor="repoAccess" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Access Key / ID
                  </label>
                  <input
                    id="repoAccess"
                    type="text"
                    value={accessKey}
                    onChange={(e) => setAccessKey(e.target.value)}
                    disabled={saving || testing}
                    className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                  />
                </div>

                <div className="space-y-1">
                  <label htmlFor="repoSecret" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Secret Key
                  </label>
                  <input
                    id="repoSecret"
                    type="password"
                    value={secretKey}
                    onChange={(e) => setSecretKey(e.target.value)}
                    disabled={saving || testing}
                    className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                  />
                </div>
              </div>

              <div className="w-1/2 space-y-1">
                <label htmlFor="repoRegion" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  Region
                </label>
                <input
                  id="repoRegion"
                  type="text"
                  value={region}
                  onChange={(e) => setRegion(e.target.value)}
                  disabled={saving || testing}
                  placeholder="us-east-1"
                  className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                />
              </div>
            </div>
          )}

          {type === 'FILE_SYSTEM' && (
            <p
              className="pt-2 border-t border-border/40 font-mono text-[10px] leading-relaxed text-muted-foreground"
              data-testid="repo-filesystem-upload-note"
            >
              Nothing to configure. A file-system repository holds the CSV, JSON and Parquet files
              you upload to it as datasets — Aran reads no directory on the server for it.
            </p>
          )}

          {/* Singer Tap Specific Fields */}
          {type === 'ICEBERG_REST' && (
            <div className="space-y-3 pt-2 border-t border-border/40">
              <div className="space-y-1">
                <label htmlFor="repoCatalogUri" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  Catalog URI
                </label>
                <input
                  id="repoCatalogUri"
                  type="text"
                  value={catalogUri}
                  onChange={(e) => setCatalogUri(e.target.value)}
                  disabled={saving || testing}
                  placeholder="e.g. https://polaris.example/api/catalog"
                  className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label htmlFor="repoWarehouse" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Warehouse (optional)
                  </label>
                  <input
                    id="repoWarehouse"
                    type="text"
                    value={warehouse}
                    onChange={(e) => setWarehouse(e.target.value)}
                    disabled={saving || testing}
                    className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                  />
                </div>

                <div className="space-y-1">
                  <label htmlFor="repoCatalogToken" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Catalog Bearer Token (optional)
                  </label>
                  <input
                    id="repoCatalogToken"
                    type="password"
                    value={catalogToken}
                    onChange={(e) => setCatalogToken(e.target.value)}
                    disabled={saving || testing}
                    className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                  />
                </div>
              </div>

              <p className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground pt-1">
                Data-plane storage credentials (for the files the catalog points at)
              </p>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label htmlFor="repoIceProvider" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Storage Provider
                  </label>
                  <select
                    id="repoIceProvider"
                    value={provider}
                    onChange={(e) => setProvider(e.target.value)}
                    disabled={saving || testing}
                    className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                  >
                    <option value="S3">S3</option>
                    <option value="MINIO">MINIO</option>
                    <option value="GCS">GCS</option>
                  </select>
                </div>

                <div className="space-y-1">
                  <label htmlFor="repoIceEndpoint" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Storage Endpoint (optional)
                  </label>
                  <input
                    id="repoIceEndpoint"
                    type="text"
                    value={endpoint}
                    onChange={(e) => setEndpoint(e.target.value)}
                    disabled={saving || testing}
                    placeholder="e.g. http://localhost:9000 (MinIO)"
                    className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label htmlFor="repoIceAccess" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Access Key / ID
                  </label>
                  <input
                    id="repoIceAccess"
                    type="text"
                    value={accessKey}
                    onChange={(e) => setAccessKey(e.target.value)}
                    disabled={saving || testing}
                    className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                  />
                </div>

                <div className="space-y-1">
                  <label htmlFor="repoIceSecret" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    Secret Key
                  </label>
                  <input
                    id="repoIceSecret"
                    type="password"
                    value={secretKey}
                    onChange={(e) => setSecretKey(e.target.value)}
                    disabled={saving || testing}
                    className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                  />
                </div>
              </div>

              <div className="w-1/2 space-y-1">
                <label htmlFor="repoIceRegion" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  Region
                </label>
                <input
                  id="repoIceRegion"
                  type="text"
                  value={region}
                  onChange={(e) => setRegion(e.target.value)}
                  disabled={saving || testing}
                  className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                />
              </div>
            </div>
          )}

          {type === 'SINGER_TAP' && (
            <div className="space-y-3 pt-2 border-t border-border/40">
              {/* An empty catalogue is a real answer, and the controller's own rule is to say the
                  feature is off rather than render an empty dropdown: a tap is an executable the
                  OPERATOR installs and allowlists, so nothing a user does here can add one. */}
              {tapState === 'error' ? (
                <ErrorNotice
                  compact
                  testId="repo-singer-load-error"
                  title="Could not load the Singer taps"
                  error={tapsError}
                  onRetry={() => { setTapsError(null); setTapsAttempt((n) => n + 1); }}
                />
              ) : tapState === 'off' ? (
                <p
                  className="font-mono text-[10px] leading-relaxed text-muted-foreground"
                  data-testid="repo-singer-disabled"
                >
                  No Singer taps are configured on this Aran instance, so this source type is off. A tap is
                  a program the server operator installs and allowlists under
                  <span className="text-foreground"> aran.singer.taps</span> — ask whoever runs
                  this instance to add one.
                </p>
              ) : (
                <>
                  <div className="space-y-1">
                    <label htmlFor="repoTap" className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                      Tap
                    </label>
                    <select
                      id="repoTap"
                      value={tapId}
                      onChange={(e) => onTapChange(e.target.value)}
                      disabled={saving || testing || tapState === 'loading'}
                      data-testid="repo-tap-select"
                      className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
                    >
                      <option value="">
                        {tapState === 'loading' ? 'loading taps…' : 'Select a tap…'}
                      </option>
                      {(taps ?? []).map((t) => (
                        <option key={t.id} value={t.id}>{t.label}</option>
                      ))}
                    </select>
                  </div>

                  {/* Rendered from the tap's own declared keys — never a fixed field list, which
                      would go stale the moment an operator allowlists a different tap. */}
                  {selectedTap?.configKeys.map((key) => {
                    const secret = selectedTap.secretKeys.includes(key);
                    return (
                      <div key={key} className="space-y-1">
                        <label
                          htmlFor={`repoTapConfig-${key}`}
                          className="block font-mono text-[10px] uppercase tracking-widest text-muted-foreground"
                        >
                          {key}{secret ? ' (secret)' : ''}
                        </label>
                        <input
                          id={`repoTapConfig-${key}`}
                          type={secret ? 'password' : 'text'}
                          value={tapConfig[key] ?? ''}
                          onChange={(e) => setTapConfig({ ...tapConfig, [key]: e.target.value })}
                          disabled={saving || testing}
                          data-testid={`repo-tap-config-${key}`}
                          className="w-full rounded border border-border bg-background px-3 py-2 font-mono text-sm text-foreground focus:outline-none"
                        />
                        {secret && tapConfig[key] === MASKED_SECRET && (
                          <p className="font-mono text-[10px] text-muted-foreground">
                            Stored value kept. Type a new one to replace it.
                          </p>
                        )}
                      </div>
                    );
                  })}
                </>
              )}
            </div>
          )}

          {/* Connection Test Status Box */}
          {editingRepoId && type !== 'FILE_SYSTEM' && (
            <p className="text-[11px] text-muted-foreground" data-testid="repo-masked-note">
              Saved credentials show as ********. Test and Save use the stored value unless you type
              a new one; pointing a kept credential at a new host needs it re-entered.
            </p>
          )}

        </div>

        {/* J2-F27: the test's progress and outcome sit above the footer, outside the scrolling
            form — they rendered below the fold, so a click on Test seemed to do nothing. */}
        {(testing || testResult || testError) && (
          <div
            className="border-t border-border px-5 py-3 text-xs font-mono"
            role={testError ? 'alert' : 'status'}
            data-testid="repo-test-status"
          >
            {testing && (
              <div className="flex items-center gap-2 text-muted-foreground">
                <Loader2 size={14} className="animate-spin text-primary" />
                Testing the connection… a first test of an engine may download its driver.
              </div>
            )}
            {testResult && (
              <div className="flex items-start gap-2 text-success">
                <CheckCircle2 size={16} className="shrink-0 pt-0.5" />
                <span>{testResult}</span>
              </div>
            )}
            {testError && (
              <div className="text-error space-y-1">
                <div className="flex items-center gap-2 font-semibold">
                  <AlertTriangle size={16} className="shrink-0" />
                  Connection Failed
                </div>
                <div className="bg-destructive text-destructive-foreground p-2 rounded max-h-28 overflow-y-auto text-[10.5px] leading-relaxed break-all select-text whitespace-pre-line">
                  {testError}
                </div>
              </div>
            )}
          </div>
        )}

        <div className="flex items-center justify-between border-t border-border px-5 py-3 bg-muted/5">
          <button
            type="button"
            onClick={testRepoConnection}
            disabled={testing || saving}
            className="flex items-center gap-1.5 rounded border border-border/80 bg-muted/20 px-3.5 py-1.5 font-mono text-xs uppercase tracking-wider text-primary hover:bg-primary/10 transition-colors disabled:opacity-40"
          >
            {testing && <Loader2 size={12} className="animate-spin" />}
            Test Connection
          </button>

          <div className="flex gap-2">
            <button
              type="button"
              onClick={close}
              disabled={saving || testing}
              className="rounded border border-border px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-muted-foreground hover:text-foreground hover:border-foreground transition-colors disabled:opacity-40"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={saveRepository}
              disabled={saving || testing || !name.trim()}
              data-testid="repo-submit"
              className="flex items-center gap-2 rounded bg-primary px-4 py-1.5 font-mono text-xs uppercase tracking-widest text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-60"
            >
              {saving && <Loader2 size={12} className="animate-spin" />}
              {editingRepoId ? 'Save Changes' : 'Save & Register'}
            </button>
          </div>
        </div>
        {confirmingDiscard && (
          <DiscardChangesBar
            what={editingRepoId ? 'this repository' : 'the new repository'}
            onKeep={() => setConfirmingDiscard(false)}
            onDiscard={() => { setConfirmingDiscard(false); setVisible(false); }}
          />
        )}
    </Dialog>
  );
});

RegisterRepositoryDialog.displayName = 'RegisterRepositoryDialog';

export default RegisterRepositoryDialog;
