/**
 * What a dismissal gesture — Escape, a backdrop click, Cancel — does to a form dialog (UI audit
 * J2-F26): a clean form closes, an edited one asks first, and a second gesture while it is asking
 * means "keep editing". Only the button named Discard throws work away, so no reflex (a double
 * Escape, a second click outside) can.
 */
export type DismissOutcome = 'close' | 'ask' | 'keep';

export function dismissOutcome({ dirty, asking }: { dirty: boolean; asking: boolean }): DismissOutcome {
  if (asking) return 'keep';
  return dirty ? 'ask' : 'close';
}
