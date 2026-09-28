/** Shared shapes between DatasetsListView and its extracted panels. */

import type { Dataset } from '@/services/types';

export interface AggregatedDataset extends Dataset {
  idx: string;
  projectName: string;
  projectColor: string;
  repoName: string;
  repoType: string;
  projectId: string;
}

/**
 * The identity of what the wrapper editor is currently editing: one dataset's one source class.
 *
 * **V49.** `DatasetsListView` seeds the editor from the model exactly when this value changes, and
 * never on a bare model reload. It has to be a value and not the `modelSources` array, because
 * `loadModelForDataset` allocates a fresh array every time it runs — so an effect keyed on that
 * array re-fired on every reload and overwrote whatever the user had typed with the stored
 * wrapper, which the Validate & Preview and Save handlers then sent to the server.
 *
 * `null` means nothing is selected (the detail panel is closed, or the dataset is not enrolled),
 * which is itself a selection change: it clears the editor. The dataset id is included even though
 * a source-class IRI already embeds one, so that the key stays correct if a class IRI is ever
 * shared by two datasets.
 */
export function wrapperSeedSelection(
  datasetId: string | undefined,
  classIri: string | null,
): string | null {
  return classIri === null ? null : `${datasetId ?? ''}::${classIri}`;
}

/**
 * A state updater that lands `next` only while the state still describes the same dataset.
 *
 * Loads for the open dataset carry the id they are FOR; a response for a dataset the user has
 * since left must not overwrite the one now shown (requests land out of order). Returned as an
 * updater rather than inlined at each call site because the React Compiler does not lower a
 * conditional inside a `try` block, and a bail-out silently switches its lint checks off for the
 * whole component.
 */
export function settleFor<T extends { datasetId: string }>(next: T): (prev: T | null) => T | null {
  return (prev) => (prev && prev.datasetId !== next.datasetId ? prev : next);
}
