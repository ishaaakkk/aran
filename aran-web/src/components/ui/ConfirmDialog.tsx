import React, { useId, useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { Dialog } from './Dialog';
import { canConfirm, type ConfirmRequest } from '@/lib/confirm';

/**
 * The one confirmation for an action that changes or destroys something (audit J8-F14). It
 * replaced five `window.confirm` calls: a native dialog cannot list consequences, cannot be
 * styled or read by the page's own tests (Playwright dismisses it), and said things like "delete
 * dataset metadata" for a delete that rejects alignments across projects.
 *
 * Two tiers, both on the one Dialog (Escape cancels, focus returns to the opener):
 *   - a consequence list — every item a thing that happens, in words the user can check;
 *   - a typed confirmation (`typeToConfirm`) for the irreversible and far-reaching, as the
 *     schema rollback already does (Snowsight / GitHub "type the name to confirm").
 * Cancel comes first in the DOM, so it — not the destructive button — takes initial focus.
 */

interface ConfirmDialogProps extends ConfirmRequest {
  open: boolean;
  busy?: boolean;
  /** Holds the confirm button back without claiming work is running (e.g. while its evidence loads). */
  confirmDisabled?: boolean;
  /** Rendered inside the dialog — a failure that lands behind the backdrop reads as a hang. */
  error?: string | null;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
  /** Extra content between the consequences and the buttons (e.g. a notes field). */
  children?: React.ReactNode;
}

export const ConfirmDialog: React.FC<ConfirmDialogProps> = ({
  open,
  title,
  body,
  consequences,
  confirmLabel,
  cancelLabel = 'Cancel',
  tone = 'danger',
  typeToConfirm,
  testId = 'confirm-dialog',
  busy = false,
  confirmDisabled = false,
  error,
  onConfirm,
  onCancel,
  children,
}) => {
  const [typed, setTyped] = useState('');
  // Named by its visible heading and described by its consequences, so a screen reader announces
  // what the decision means on open, not only the title and the focused button (WAI-ARIA APG).
  const titleId = useId();
  const consequencesId = useId();
  const enabled = canConfirm(typed, typeToConfirm, busy) && !confirmDisabled;
  const close = () => {
    if (busy) return;
    setTyped('');
    onCancel();
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      label={title}
      labelledBy={titleId}
      describedBy={consequences && consequences.length > 0 ? consequencesId : undefined}
      role="alertdialog"
      maxWidth="max-w-lg"
      testId={testId}
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (!enabled) return;
          setTyped('');
          onConfirm();
        }}
        className="p-5 flex flex-col gap-3 text-[12.5px] text-foreground"
      >
        <div className="flex items-start gap-2.5">
          {tone === 'danger' && <AlertTriangle size={16} className="text-error shrink-0 mt-0.5" aria-hidden="true" />}
          <h2 id={titleId} className="text-[14px] font-semibold leading-snug">{title}</h2>
        </div>
        {body && <div className="text-muted-foreground leading-relaxed">{body}</div>}
        {consequences && consequences.length > 0 && (
          <ul id={consequencesId} data-testid={`${testId}-consequences`} className="list-disc pl-5 flex flex-col gap-1 leading-relaxed">
            {consequences.map((c, i) => (
              <li key={i}>{c}</li>
            ))}
          </ul>
        )}
        {children}
        {typeToConfirm !== undefined && (
          <label className="flex flex-col gap-1.5 text-[12px] text-muted-foreground">
            <span>
              Type <span className="font-mono font-semibold text-foreground">{typeToConfirm}</span> to confirm.
            </span>
            <input
              type="text"
              data-testid={`${testId}-typed`}
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              autoComplete="off"
              className="w-full bg-input/40 border border-border rounded p-2 font-mono text-[12px] text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </label>
        )}
        {error && (
          <div role="alert" data-testid={`${testId}-error`}
            className="p-3 border border-error/40 bg-error/10 rounded text-[12px] text-error leading-relaxed">
            {error}
          </div>
        )}
        <div className="flex justify-end gap-2 pt-1">
          <button
            type="button"
            data-testid={`${testId}-cancel`}
            onClick={close}
            disabled={busy}
            className="text-[12px] text-foreground border border-border rounded-md px-3 py-1.5 bg-transparent hover:bg-muted/50 cursor-pointer disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-ring"
          >
            {cancelLabel}
          </button>
          <button
            type="submit"
            data-testid={`${testId}-confirm`}
            disabled={!enabled}
            className={`text-[12px] font-semibold rounded-md px-3 py-1.5 border cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed focus:outline-none focus:ring-2 focus:ring-ring ${
              tone === 'danger'
                ? 'bg-error/15 text-error border-error/50 hover:bg-error/25'
                : 'bg-primary text-primary-foreground border-primary hover:bg-primary/90'
            }`}
          >
            {busy ? 'Working…' : confirmLabel}
          </button>
        </div>
      </form>
    </Dialog>
  );
};

export default ConfirmDialog;
