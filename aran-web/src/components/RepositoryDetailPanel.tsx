import React, { useState } from 'react';
import { X, Pencil, Trash2, Database, HardDrive, Folder, Copy, Check, Plug } from 'lucide-react';
import type { DataRepository, Project } from '@/services/types';
import { copyText } from '@/lib/clipboard';
import type { ConnectionStatusView } from '@/lib/connectionStatus';
import { StatusDot } from '@/components/StatusDot';

interface RepositoryDetailPanelProps {
  repository: DataRepository;
  project?: Project;
  onClose: () => void;
  onEdit?: (repo: DataRepository) => void;
  onDelete?: (repo: DataRepository) => void;
  datasetCount?: number;
  /** What the last connection test found (J2-F19); omitted, the section is not shown. */
  connection?: ConnectionStatusView;
}

const typeIcon: Record<string, React.ReactNode> = {
  RELATIONAL_DB:   <Database className="w-4 h-4" />,
  OBJECT_STORAGE:  <HardDrive className="w-4 h-4" />,
  FILE_SYSTEM:     <Folder className="w-4 h-4" />,
  SINGER_TAP:      <Plug className="w-4 h-4" />,
  ICEBERG_REST:    <HardDrive className="w-4 h-4" />,
};

const typeLabel: Record<string, string> = {
  RELATIONAL_DB:   'Relational DB',
  OBJECT_STORAGE:  'Object Storage',
  FILE_SYSTEM:     'File System',
  SINGER_TAP:      'Singer Tap',
  ICEBERG_REST:    'Iceberg Catalog',
};

function getConnectionSummary(repo: DataRepository): { label: string; value: string }[] {
  switch (repo.type) {
    case 'RELATIONAL_DB':
      return [
        { label: 'Engine',   value: repo.engine },
        { label: 'Host',     value: `${repo.host}:${repo.port}` },
        { label: 'Database', value: repo.database },
        ...(repo.schema   ? [{ label: 'Schema',   value: repo.schema }]   : []),
        ...(repo.username ? [{ label: 'Username', value: repo.username }] : []),
      ];
    case 'OBJECT_STORAGE':
      return [
        { label: 'Provider', value: repo.provider },
        { label: 'Bucket',   value: repo.bucketName },
        ...(repo.endpoint ? [{ label: 'Endpoint', value: repo.endpoint }] : []),
        ...(repo.region   ? [{ label: 'Region',   value: repo.region }]   : []),
      ];
    case 'FILE_SYSTEM':
      return [{ label: 'Holds', value: 'uploaded files' }];
    case 'ICEBERG_REST':
      return [
        { label: 'Catalog',  value: repo.catalogUri },
        ...(repo.warehouse ? [{ label: 'Warehouse', value: repo.warehouse }] : []),
        { label: 'Provider', value: repo.provider },
        ...(repo.endpoint  ? [{ label: 'Endpoint',  value: repo.endpoint }]  : []),
        ...(repo.region    ? [{ label: 'Region',    value: repo.region }]    : []),
      ];
    case 'SINGER_TAP':
      // Settings, not a connection string: a tap has no address Aran dials. Secret values
      // arrive already masked from the server — this panel never decides what to hide.
      return [
        { label: 'Tap', value: repo.tapId },
        ...Object.entries(repo.config ?? {}).map(([key, value]) => ({ label: key, value })),
      ];
    default:
      return [];
  }
}

/**
 * One connection value with its copy button. J5-F17's rule: "copied" only when the write
 * resolved; when it did not (an `http://` install has no clipboard API, or permission was
 * denied) the row says so and the value — `select-text`, since the list view around this sheet
 * is `select-none` — is there to select by hand.
 */
export const ConnectionDetailRow: React.FC<{
  label: string;
  value: string;
  /** true = copied, false = the copy failed, null = no copy attempted. */
  copied: boolean | null;
  onCopy: () => void;
}> = ({ label, value, copied, onCopy }) => (
  <div className="flex items-start gap-2 group">
    <span className="text-[9px] font-mono uppercase tracking-wider text-muted-foreground w-20 shrink-0 pt-[1px]">
      {label}
    </span>
    <span className="flex-1 min-w-0 flex flex-col gap-0.5">
      <span className="text-[10.5px] font-mono text-foreground/80 break-all select-text">{value}</span>
      {copied === false && (
        <span role="status" className="text-[10px] text-muted-foreground">
          couldn’t copy — select it
        </span>
      )}
    </span>
    <button
      type="button"
      title={copied === false ? `Couldn’t copy — select the ${label} instead` : `Copy ${label}`}
      onClick={onCopy}
      aria-label={`Copy ${label}`}
      className="shrink-0 opacity-0 group-hover:opacity-100 focus-visible:opacity-100 text-muted-foreground hover:text-foreground transition-all p-0.5"
    >
      {copied === true
        ? <Check className="w-3 h-3 text-emerald-400" />
        : <Copy className="w-3 h-3" />}
    </button>
  </div>
);

export const RepositoryDetailPanel: React.FC<RepositoryDetailPanelProps> = ({
  repository,
  project,
  onClose,
  onEdit,
  onDelete,
  datasetCount,
  connection,
}) => {
  const connectionDetails = getConnectionSummary(repository);
  const [copy, setCopy] = useState<{ label: string; ok: boolean } | null>(null);

  const copyValue = async (label: string, value: string) => {
    const ok = await copyText(value);
    setCopy({ label, ok });
    // A failure stays up longer: it asks the reader to do something.
    setTimeout(() => setCopy((c) => (c?.label === label ? null : c)), ok ? 2000 : 6000);
  };

  return (
    <div className="h-full min-h-0 flex flex-col">
      <div className="h-11 flex items-center justify-between px-4 border-b border-border bg-card/60 shrink-0">
        <span className="text-[10px] font-mono font-bold uppercase tracking-wider text-primary select-none">
          repository · detail
        </span>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close repository details"
          className="text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-5">
        {datasetCount !== undefined && (
          <div className="flex items-center justify-between rounded-md border border-border/60 bg-muted/10 px-4 py-2.5 font-mono">
            <span className="text-[9px] uppercase tracking-wider text-muted-foreground">Registered datasets</span>
            <span className="text-sm font-bold text-foreground tabular-nums">{datasetCount}</span>
          </div>
        )}

        <div className="flex items-start gap-3">
          <span className="text-primary shrink-0 mt-0.5">
            {typeIcon[repository.type] ?? <Database className="w-4 h-4" />}
          </span>
          <div className="min-w-0">
            <h3 className="text-sm font-semibold text-foreground leading-snug truncate">
              {repository.name}
            </h3>
            <span className="text-[9px] font-mono uppercase tracking-wider text-muted-foreground">
              {typeLabel[repository.type] ?? repository.type}
            </span>
          </div>
        </div>

        {repository.description && (
          <p className="text-[11px] text-muted-foreground leading-relaxed">
            {repository.description}
          </p>
        )}

        {project && (
          <div className="flex items-center gap-2">
            <span
              className="w-2 h-2 rounded-full shrink-0"
              style={{ backgroundColor: project.color || '#7c3aed' }}
            />
            <span className="text-[10px] font-mono text-foreground/70 truncate">
              {project.name}
            </span>
          </div>
        )}

        <span className="block h-[1px] bg-border" />

        {/* The last test's own words, in full — the list row only has room for its label. */}
        {connection && (
          <div className="space-y-1.5" data-testid="repo-detail-last-test">
            <span className="text-[9px] font-mono uppercase tracking-wider text-muted-foreground">
              Last connection test
            </span>
            <div className="flex items-center gap-2">
              <StatusDot variant={connection.variant} label={connection.label} />
              {connection.when && (
                <span className="text-[10px] font-mono text-muted-foreground">{connection.when}</span>
              )}
            </div>
            <p className="text-[10.5px] text-foreground/80 leading-relaxed break-words">{connection.title}</p>
          </div>
        )}

        {/* Connection details with copy-on-click */}
        <div className="space-y-2.5">
          <span className="text-[9px] font-mono uppercase tracking-wider text-muted-foreground">
            Connection
          </span>
          {connectionDetails.map(({ label, value }) => (
            <ConnectionDetailRow
              key={label}
              label={label}
              value={value}
              copied={copy?.label === label ? copy.ok : null}
              onCopy={() => void copyValue(label, value)}
            />
          ))}
        </div>
      </div>

      <div className="shrink-0 border-t border-border p-3 flex items-center gap-2">
        {onEdit && (
          <button
            type="button"
            onClick={() => onEdit(repository)}
            className="flex-1 h-8 flex items-center justify-center gap-1.5 text-[10px] font-mono font-semibold uppercase tracking-wider text-foreground/80 hover:text-primary border border-border hover:border-primary/30 rounded-md transition-all cursor-pointer"
          >
            <Pencil className="w-3 h-3" />
            Edit
          </button>
        )}
        {onDelete && (
          <button
            type="button"
            onClick={() => onDelete(repository)}
            className="flex-1 h-8 flex items-center justify-center gap-1.5 text-[10px] font-mono font-semibold uppercase tracking-wider text-foreground/80 hover:text-rose-400 border border-border hover:border-rose-500/30 rounded-md transition-all cursor-pointer"
          >
            <Trash2 className="w-3 h-3" />
            Delete
          </button>
        )}
        <button
          type="button"
          onClick={onClose}
          className="flex-1 h-8 flex items-center justify-center gap-1.5 text-[10px] font-mono font-semibold uppercase tracking-wider text-muted-foreground hover:text-foreground border border-border rounded-md transition-all cursor-pointer"
        >
          Close
        </button>
      </div>
    </div>
  );
};

export default RepositoryDetailPanel;
