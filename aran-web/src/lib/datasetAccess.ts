import type { DataRepository, Dataset, DatasetAccessMode } from '@/services/types';

/**
 * How a dataset is read, in words (J2-F3). The mode is the server's (`DatasetAccessMode`,
 * computed from `SourceAccess.resolve` and the dataset's own copy); this only names it.
 */
export function accessLabel(mode: DatasetAccessMode | undefined): string {
  switch (mode) {
    case 'SNAPSHOT': return 'Snapshot';
    case 'LIVE': return 'Live';
    case 'LIVE_WITH_COPY': return 'Live + copy';
    default: return '—';
  }
}

export function accessNote(mode: DatasetAccessMode | undefined): string {
  switch (mode) {
    case 'SNAPSHOT':
      return 'Queries read the dataset\'s lake snapshot. It changes only when the dataset is refreshed or re-uploaded.';
    case 'LIVE':
      return 'Every query reads the source directly; nothing is copied into the lake.';
    case 'LIVE_WITH_COPY':
      return 'Queries read the source live. The lake copy is used instead only while an interval refresh keeps it fresh and the planner finds it cheaper.';
    default:
      return 'The server has not reported how this dataset is read.';
  }
}

/**
 * Whether queries read this dataset live from its source rather than from a lake copy — the
 * client's mirror of the server's `SourceAccess.resolve` (FEDERATED), until the dataset DTO carries
 * its access mode itself (audit J2-F3). The server stays authoritative: it refuses a live rename
 * with a 409 whatever this says.
 *
 * - relational: the repository asked to federate AND its engine has a live scanner
 *   (`FederatedScanExpression`: Postgres, MySQL, MariaDB); a flag on any other engine is a
 *   request the server does not grant;
 * - object storage and Iceberg: live until this dataset has been copied into the lake;
 * - uploads and Singer taps: never.
 */
const LIVE_SCAN_ENGINES = new Set(['POSTGRESQL', 'MYSQL', 'MARIADB']);

export function readsLive(
  repository: DataRepository | undefined,
  dataset: Pick<Dataset, 'snapshotId'>,
): boolean {
  if (!repository) return false;
  switch (repository.type) {
    case 'RELATIONAL_DB':
      return !!repository.federated && LIVE_SCAN_ENGINES.has(repository.engine.toUpperCase());
    case 'OBJECT_STORAGE':
    case 'ICEBERG_REST':
      return !dataset.snapshotId;
    default:
      return false;
  }
}

/**
 * What renaming a dataset does (UI-OD8, audit J2-F6), for the edit dialog to say before the user
 * presses Update. `blocked` — a live dataset's name is the table its queries read, so the rename
 * is refused (the server refuses it too). `warn` — an upload's name seeds its schema IRIs, so its
 * next re-bootstrap or refresh re-mints them and, when alignments use them, is held as BREAKING
 * drift until repaired. A warning, not a block: d1-schema-drift renames an upload on purpose.
 */
export type RenameImpact = 'none' | 'warn' | 'blocked';

export function renameImpact(live: boolean, originalName: string, nextName: string): RenameImpact {
  if (live) return 'blocked';
  return nextName.trim() !== '' && nextName.trim() !== originalName ? 'warn' : 'none';
}
