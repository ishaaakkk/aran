import type { RepositoryType } from '@/services/types';

/**
 * How each repository kind is offered, and what the forms say about it.
 *
 * Out of the dialog component so the wording is asserted without a DOM
 * (`repositoryKinds.test.ts`) and so the dialog file stays component-only
 * (react-refresh/only-export-components). Every sentence here is checked against what the build
 * does (UI audit J2-F4, J2-F18, J2-F30):
 * - object storage: connection test and file upload only — no production path enrols a bucket's
 *   objects as datasets (V35); the test is an HTTP probe that passes on any answer
 *   (`DataRepositoryService`), so it proves reachability, not credentials; S3 and MinIO-compatible
 *   endpoints are what the Testcontainers suite exercises (Garage), GCS and Azure never were.
 * - importing from a federated relational repository gives a dataset BOTH a live read and a lake
 *   copy; `SourceAccess`/`PlacementDecider` serve the copy only under an `Every` refresh that is
 *   fresh enough, so a one-off import keeps queries live (λ placement).
 */

/** Display names for the repository kinds. Presentation only. */
export const REPOSITORY_TYPE_LABELS: Record<RepositoryType, string> = {
  RELATIONAL_DB: 'Relational Database',
  // UI-OD13: until a bucket enrolment path exists (V35).
  OBJECT_STORAGE: 'Object Storage — connect only (upload files)',
  FILE_SYSTEM: 'File System (upload files)',
  SINGER_TAP: 'SaaS API via Singer tap',
  ICEBERG_REST: 'Apache Iceberg REST Catalog',
};

/**
 * Order the kinds are offered in: a database and plain file uploads first, the two a first-time
 * evaluator most likely has at hand.
 */
export const REPOSITORY_TYPE_ORDER: RepositoryType[] = [
  'RELATIONAL_DB',
  'FILE_SYSTEM',
  'OBJECT_STORAGE',
  'ICEBERG_REST',
  'SINGER_TAP',
];

/** Object-storage providers, with the ones no test has reached marked as such (J2-F18). */
export const OBJECT_STORAGE_PROVIDERS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'S3', label: 'S3' },
  { value: 'MINIO', label: 'MINIO' },
  { value: 'GCS', label: 'GCS — untested' },
  { value: 'AZURE', label: 'AZURE — untested' },
];

/** Shown under the object-storage form. */
export const OBJECT_STORAGE_NOTE =
  'Connect only: Aran can check that the bucket endpoint answers, but cannot yet list or import ' +
  'its objects — datasets in this repository come from files you upload. A passing test proves ' +
  'the endpoint is reachable; it does not prove the keys are right.';

/** The Repositories page when a project has none (J2-F18, J1-F13). */
export const REPOSITORIES_EMPTY_STATE =
  'Register a relational database, an Iceberg REST catalog, or a place to upload files so Aran ' +
  'can bootstrap its datasets — or a SaaS API through a Singer tap, when your operator has ' +
  'enabled taps.';

/** Under the "Federated Execution" checkbox of a relational repository (J2-F4). */
export const FEDERATED_REPOSITORY_NOTE =
  'Queries read this database live, in place. A table you register without importing is only ' +
  'ever read live; importing a table also keeps a lake copy, which queries use only once that ' +
  'copy has an interval refresh and is fresh enough for the planner to serve it.';

/**
 * The body of a connection test (J2-F27). The request DTO requires a name (`@NotBlank`), which a
 * connectivity probe does not use, so the form used to disable Test until one was typed. The
 * test sends a placeholder instead; the saved repository still needs a real name.
 */
export function connectionTestPayload<T extends { name: string }>(payload: T): T {
  return payload.name.trim() ? payload : { ...payload, name: 'connection test' };
}

/** What the dataset dialog's import mode does, per kind of source (J2-F4). */
export function importModeNote(
  importKind: 'table' | 'stream' | 'iceberg table',
  federated: boolean,
): string {
  if (importKind === 'stream') {
    return 'Running the tap pulls the stream’s records into the lake as a snapshot. A tap is a ' +
      'snapshot mechanism by nature — there is no live passthrough to compare it with.';
  }
  if (importKind === 'table' && federated) {
    return 'Adds a lake copy of the table, but this repository is federated, so queries stay live. ' +
      'Set an interval refresh on the dataset and the planner may serve the copy while it is fresh.';
  }
  return 'Import copies the table into the lake as a snapshot, and queries read the copy — it is ' +
    'as fresh as its last import or refresh.';
}
