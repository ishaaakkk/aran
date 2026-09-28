/**
 * The toast policy (UI audit J8-F8), outside the component so the component file stays
 * component-only (react-refresh/only-export-components): a success hides itself —
 * `DatasetsPage.saveWrapper` waits for exactly that — and an error stays until dismissed or
 * replaced, because a failure read four seconds too late was a failure never read.
 */
export interface ToastMessage {
  message: string;
  type: 'success' | 'error';
}

/** How long a toast of this type stays up; null = until dismissed. */
export function toastLifetimeMs(type: ToastMessage['type']): number | null {
  return type === 'success' ? 4000 : null;
}
