import React from 'react';
import { CircleCheck, X } from 'lucide-react';
import type { ToastMessage } from '@/lib/toast';

/**
 * The one toast (UI audit J8-F8). Datasets and Repositories each hand-rolled one that vanished
 * after 4 s whatever it said, with no live region: a failed save read in another panel, or by a
 * screen reader, was simply gone. The policy now: a success is polite and hides itself —
 * `DatasetsPage.saveWrapper` waits for exactly that — and an error is an alert that stays until
 * dismissed or replaced by the next message.
 *
 * Bottom-right, not under the top bar: an error that stays up there lay over the toolbar's
 * primary action (`repos-new-button`, `datasets-register-button`, `dataset-close`), so the retry
 * the message itself asked for could not be clicked until the message was dismissed.
 *
 * Both live regions are always mounted and only their content changes: a region inserted together
 * with its text is often not announced, one that exists before the text arrives is. The corner
 * itself ignores the pointer, so the empty regions never sit over anything clickable.
 *
 * A success's text is the foreground colour, with green only on its icon and border: the success
 * token is under WCAG AA as 12 px text on a light card (`contrast.test.tsx`).
 */

export const Toast: React.FC<{ toast: ToastMessage | null; onDismiss: () => void; testId?: string }> = ({
  toast,
  onDismiss,
  testId,
}) => {
  const error = toast?.type === 'error';
  const box = toast && (
    <div
      data-testid={testId}
      data-type={toast.type}
      className={`pointer-events-auto max-w-md rounded-md border px-4 py-3 shadow-lg font-mono text-xs flex items-start gap-2 animate-in ${
        error
          ? 'bg-destructive text-destructive-foreground border-error/40'
          : 'bg-card text-foreground border-success/50'
      }`}
    >
      {!error && <CircleCheck size={14} aria-hidden="true" className="shrink-0 mt-px text-success" />}
      <span className="flex-1 break-words">{toast.message}</span>
      {error && (
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss notification"
          data-testid="toast-dismiss"
          className="shrink-0 opacity-80 hover:opacity-100"
        >
          <X size={14} />
        </button>
      )}
    </div>
  );
  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex flex-col items-end gap-2">
      <div role="status" aria-live="polite" aria-atomic="true">
        {toast && !error ? box : null}
      </div>
      <div role="alert" aria-live="assertive" aria-atomic="true">
        {error ? box : null}
      </div>
    </div>
  );
};

export default Toast;
