import React from 'react';
import { X } from 'lucide-react';
import { type ActionOutcome, passwordChangeMessage } from '../services/keycloak';

/**
 * What happened to a "Change password" round trip, said once on return (audit J7-F14's review).
 *
 * Keycloak reports the outcome on the redirect back; before this, a change, a cancel and a failure
 * all landed on the same silent page. A failure is an alert — the password did not change and the
 * person may believe it did — and the rest are a status. It stays until dismissed rather than
 * fading while someone is still looking elsewhere.
 */
export const PasswordChangeNotice: React.FC<{ outcome: ActionOutcome; onDismiss: () => void }> = ({
  outcome, onDismiss,
}) => (
  <div
    role={outcome === 'error' ? 'alert' : 'status'}
    data-testid="password-change-notice"
    className={`fixed bottom-4 left-60 z-50 flex max-w-sm items-start gap-2 rounded-md border px-3 py-2 text-xs shadow-lg bg-card ${
      outcome === 'error' ? 'border-error/40 text-error' : 'border-border text-foreground'
    }`}
  >
    <span className="flex-1">{passwordChangeMessage(outcome)}</span>
    <button
      type="button"
      onClick={onDismiss}
      aria-label="Dismiss"
      className="shrink-0 rounded p-0.5 text-muted-foreground hover:text-foreground"
    >
      <X className="h-3.5 w-3.5" aria-hidden="true" />
    </button>
  </div>
);

export default PasswordChangeNotice;
