import type React from 'react';

/** What a confirmation says — see ConfirmDialog for the two tiers. */
export interface ConfirmRequest {
  title: string;
  /** One sentence above the list. */
  body?: React.ReactNode;
  /** What happens if the user confirms, one item per consequence. */
  consequences?: React.ReactNode[];
  confirmLabel: string;
  /** `danger` for destroy/replace; `default` for a consequential but constructive action. */
  tone?: 'danger' | 'default';
  /** When set, the confirm button stays disabled until this exact text is typed. */
  typeToConfirm?: string;
  /** Stable hook for tests; the confirm button gets `${testId}-confirm`, cancel `${testId}-cancel`. */
  testId?: string;
}

/** Whether the confirm button may be pressed: never while busy, and only on the exact typed name. */
export function canConfirm(typed: string, typeToConfirm: string | undefined, busy: boolean): boolean {
  if (busy) return false;
  return typeToConfirm === undefined || typed.trim() === typeToConfirm;
}
