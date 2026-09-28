import React, { useEffect, useRef } from 'react';

/**
 * The one modal wrapper. The hand-rolled pattern it replaces — a backdrop div with
 * role="button", no role="dialog", no focus management, no Escape — was copy-pasted
 * across Datasets, Repositories, Projects, Versions, Products and the account dialogs
 * (redesign brief, cross-cutting "One Dialog primitive"). This owns exactly the
 * accessibility contract; the content inside stays whatever the caller renders, so
 * adopting it never changes a testid.
 *
 * Contract: role="dialog" + aria-modal, Escape closes, backdrop click closes (panel
 * clicks don't), focus moves into the panel on open and back to the opener on close,
 * and Tab cycles inside the panel instead of escaping into the page behind it.
 */

interface DialogProps {
  open: boolean;
  onClose: () => void;
  /** Accessible name for the dialog — used as `aria-label` unless `labelledBy` is given. */
  label: string;
  /**
   * Id of the visible heading that names the dialog. Preferred over `label` when the dialog shows
   * its title, so the name and the heading cannot drift (WAI-ARIA APG dialog pattern).
   */
  labelledBy?: string;
  /**
   * Id of the element holding the message — for an `alertdialog`, what the decision means — so a
   * screen reader announces it on open instead of only the title and the focused button.
   */
  describedBy?: string;
  /** Tailwind width class for the panel, e.g. "max-w-md". Default max-w-md. */
  maxWidth?: string;
  /** Extra classes for the panel element (background, border, radius come standard). */
  className?: string;
  /** Extra classes for the backdrop layer (e.g. a different z-index). */
  backdropClassName?: string;
  /** data-testid for the panel element, so adopters keep their e2e contract. */
  testId?: string;
  /**
   * `modal` (default) centres the panel; `sheet` anchors it to the right edge, full height below
   * the 48 px top bar — the side-panel shape (project and repository details) with the same
   * accessibility contract, instead of a hand-rolled overlay (UI audit J1-F18, J2-F25).
   */
  variant?: 'modal' | 'sheet';
  /** `alertdialog` for a confirmation that interrupts to ask for a decision (WAI-ARIA APG). */
  role?: 'dialog' | 'alertdialog';
  children: React.ReactNode;
}

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

export const Dialog: React.FC<DialogProps> = ({
  open,
  onClose,
  label,
  maxWidth,
  className = '',
  backdropClassName = '',
  testId,
  variant = 'modal',
  labelledBy,
  describedBy,
  role = 'dialog',
  children,
}) => {
  const panelRef = useRef<HTMLDivElement>(null);
  const openerRef = useRef<Element | null>(null);

  useEffect(() => {
    if (!open) return;
    openerRef.current = document.activeElement;
    // Focus the first focusable control, falling back to the panel itself.
    const panel = panelRef.current;
    if (panel) {
      const first = panel.querySelector<HTMLElement>(FOCUSABLE);
      (first ?? panel).focus();
    }
    return () => {
      // Restore focus to whatever opened the dialog — screen-reader and keyboard
      // users otherwise land back at the top of the document.
      if (openerRef.current instanceof HTMLElement) openerRef.current.focus();
    };
  }, [open]);

  if (!open) return null;

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      onClose();
      return;
    }
    if (e.key !== 'Tab') return;
    const panel = panelRef.current;
    if (!panel) return;
    const focusables = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (focusables.length === 0) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  const sheet = variant === 'sheet';
  // A sheet is 20 rem unless the caller asks for more (the jobs drawer); a modal keeps its old default.
  const width = maxWidth ?? (sheet ? 'max-w-xs' : 'max-w-md');
  return (
    <div
      className={
        sheet
          ? `fixed inset-0 z-50 bg-black/35 backdrop-blur-[2px] ${backdropClassName}`
          : `fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4 animate-in ${backdropClassName}`
      }
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      role="presentation"
    >
      {/* The keydown here implements the dialog pattern's Escape + focus trap, which is exactly
          what role="dialog"/"alertdialog" is for; there is no more-interactive element to move it
          to. (With the role passed as a prop, jsx-a11y no longer flags it, so no disable.) */}
      <div
        ref={panelRef}
        role={role}
        aria-modal="true"
        aria-label={labelledBy ? undefined : label}
        aria-labelledby={labelledBy}
        aria-describedby={describedBy}
        data-testid={testId}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        className={
          sheet
            ? `absolute right-0 top-12 bottom-0 w-full ${width} glass border-l border-border shadow-2xl flex flex-col outline-none animate-in ${className}`
            : `w-full ${width} bg-card border border-border rounded-lg shadow-2xl overflow-hidden outline-none ${className}`
        }
      >
        {children}
      </div>
    </div>
  );
};

export default Dialog;
