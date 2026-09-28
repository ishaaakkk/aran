/**
 * What the rollback confirmation says a rollback does — and what it does not undo (audit J4-F18).
 *
 * One line is about the promote guard (review UA5): a promote that would remove another project's
 * classes is refused (UI-OD2), but a rollback is not — it is a typed, tenant-wide recovery action,
 * and whether it should be guarded too is an owner question still open. Until it is ruled, the
 * dialog says so, so the typed confirmation is given knowing it.
 */
export function rollbackConsequences(targetVersion: number, activeVersion: number | null): string[] {
  return [
    `v${targetVersion}'s schema becomes a new active version${activeVersion !== null ? `; v${activeVersion} becomes historical` : ''}. Every project's queries read it at once.`,
    `Unlike a promote, a rollback is not refused when it removes classes another project promoted after v${targetVersion}: every class it removes is in the list below, whichever project it belongs to.`,
    'Integration models are not rolled back: their alignments are unchanged, so the next rebuild brings back what the rollback removed.',
    'Until a project rebuilds and promotes, its "Add without re-reading the others" is refused — its baseline is no longer the active schema.',
    'Materialized products go stale.',
  ];
}
