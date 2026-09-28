import React from 'react';

/**
 * The one inline error block. The hand-rolled one it replaces — `border-rose-500/40
 * bg-rose-500/10 text-rose-300` — was written for the dark skin and measured about 1.6:1 in the
 * default light skin (UI audit J6-F13): a refused delete or a failed refresh explained in text
 * nobody could read. This one sits on the semantic `--destructive` / `--destructive-foreground`
 * pair, which each skin defines at AA contrast (≈6.7:1 light, ≈7:1 dark), and is announced
 * (`role="alert"`), since an error that appears after an action is news the user did not look for.
 */
interface ErrorBannerProps {
  children: React.ReactNode;
  /** A short bold lead ("Couldn't load data products"), when the message alone lacks context. */
  title?: string;
  /** The next action — a Retry button, a link. */
  action?: React.ReactNode;
  testId?: string;
  className?: string;
}

export const ErrorBanner: React.FC<ErrorBannerProps> = ({ children, title, action, testId, className = '' }) => (
  <div
    role="alert"
    data-testid={testId}
    className={`border border-error/40 bg-destructive text-destructive-foreground rounded p-3 text-[11px] leading-relaxed ${className}`}
  >
    {title && <div className="font-semibold mb-0.5">{title}</div>}
    <div className="break-words">{children}</div>
    {action && <div className="mt-2">{action}</div>}
  </div>
);

export default ErrorBanner;
