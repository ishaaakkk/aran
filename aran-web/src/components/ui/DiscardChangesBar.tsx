import React, { useEffect, useState } from 'react';

/**
 * The inline "discard your changes?" check a form dialog shows when it is dismissed with edits
 * (UI audit J2-F26). Inline rather than a second modal on top of the first: the question is about
 * this form, the answer usually is "keep editing", and a stacked dialog would steal the focus the
 * form just had. It names what would be lost; the default (first) action keeps it.
 *
 * Keyboard: "Keep editing" takes focus when the question appears, so Enter answers it and one Tab
 * reaches Discard — before, focus stayed in the form and the question was announced but out of
 * reach. A second Escape also keeps editing (`dismissOutcome`). Keeping hands focus back to the
 * control that had it when the question appeared.
 */
export const DiscardChangesBar: React.FC<{ what: string; onKeep: () => void; onDiscard: () => void }> = ({
  what,
  onKeep,
  onDiscard,
}) => {
  // Read once, on the render that mounts the bar — before "Keep editing" takes focus.
  const [returnTo] = useState<Element | null>(() => (typeof document === 'undefined' ? null : document.activeElement));
  useEffect(
    () => () => {
      // On Discard the whole dialog is gone (the element is disconnected) and Dialog returns
      // focus to its opener instead.
      if (returnTo instanceof HTMLElement && returnTo !== document.body && returnTo.isConnected) returnTo.focus();
    },
    [returnTo],
  );

  return (
    <div
      role="alert"
      data-testid="discard-changes-bar"
      className="flex flex-wrap items-center justify-between gap-2 border-t border-warning/40 bg-warning/10 px-5 py-2.5 text-[12px] text-foreground"
    >
      <span>Discard your changes to {what}?</span>
      <span className="flex gap-2">
        <button
          type="button"
          onClick={onKeep}
          // Not focus on page load, which no-autofocus exists to prevent: a question the user's own
          // Escape just raised, inside a modal, whose safe answer takes focus as the WAI-ARIA
          // alert-dialog pattern prescribes.
          // eslint-disable-next-line jsx-a11y/no-autofocus -- see above
          autoFocus
          className="rounded border border-border px-3 py-1 font-medium hover:border-foreground/40"
        >
          Keep editing
        </button>
        <button
          type="button"
          data-testid="discard-changes-confirm"
          onClick={onDiscard}
          className="rounded border border-error/40 bg-destructive px-3 py-1 font-medium text-destructive-foreground hover:bg-error/15"
        >
          Discard
        </button>
      </span>
    </div>
  );
};

export default DiscardChangesBar;
