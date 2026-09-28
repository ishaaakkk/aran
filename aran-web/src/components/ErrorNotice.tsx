import React, { useState } from 'react';
import { AlertTriangle, Copy, Check, RotateCw } from 'lucide-react';
import { errorDetail, errorReference } from '@/lib/errors';

/**
 * One way to say "this failed" wherever a load or an action fails in place (UI audit J8-F2,
 * J8-F4): what failed, the server's own sentence, the reference support needs — copyable — and
 * the way on. It exists so that no view has a reason to render a failure as an empty list.
 *
 * `role="alert"`, so a screen reader hears it when it appears; colours from the error token,
 * which holds AA contrast on both skins (the `rose-400` it replaces did not on Field Notes).
 */
export interface ErrorNoticeProps {
  /** What failed, as the user would name it: "Couldn't load your datasets". */
  title: string;
  error: unknown;
  onRetry?: () => void;
  retrying?: boolean;
  retryLabel?: string;
  testId?: string;
  /** Extra guidance or actions, rendered under the message. */
  children?: React.ReactNode;
  /** A one-line strip instead of a centred block — for a banner above content that did load. */
  compact?: boolean;
}

export const ErrorReference: React.FC<{ reference: string }> = ({ reference }) => {
  const [copied, setCopied] = useState(false);
  const copy = () => {
    void navigator.clipboard?.writeText(reference).then(() => setCopied(true), () => setCopied(false));
  };
  return (
    <span className="inline-flex items-center gap-1.5 font-mono text-[11px] text-muted-foreground">
      Reference: <span className="select-text text-foreground" data-testid="error-reference">{reference}</span>
      <button
        type="button"
        onClick={copy}
        aria-label="Copy the reference"
        className="inline-flex items-center justify-center w-6 h-6 rounded border border-border hover:border-primary hover:text-primary focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary transition-colors"
      >
        {copied ? <Check size={11} /> : <Copy size={11} />}
      </button>
    </span>
  );
};

export const ErrorNotice: React.FC<ErrorNoticeProps> = ({
  title, error, onRetry, retrying = false, retryLabel = 'Retry', testId, children, compact = false,
}) => {
  const detail = errorDetail(error, 'Something went wrong.');
  const reference = errorReference(error);

  const retryButton = onRetry && (
    <button
      type="button"
      onClick={onRetry}
      disabled={retrying}
      className="inline-flex items-center gap-1.5 h-8 px-4 rounded-md border border-border font-semibold text-foreground hover:border-primary hover:text-primary transition-colors text-[12px] disabled:opacity-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
    >
      <RotateCw size={12} className={retrying ? 'animate-spin' : ''} />
      {retrying ? 'Retrying…' : retryLabel}
    </button>
  );

  if (compact) {
    return (
      <div
        role="alert"
        data-testid={testId}
        className="flex flex-wrap items-center gap-x-3 gap-y-2 rounded-md border border-error/40 bg-error/5 px-4 py-2.5 text-xs"
      >
        <AlertTriangle size={14} className="text-error shrink-0" aria-hidden="true" />
        <span className="font-semibold text-error">{title}</span>
        <span className="text-foreground/90 select-text">{detail}</span>
        {reference && <ErrorReference reference={reference} />}
        {children}
        {retryButton && <span className="ml-auto">{retryButton}</span>}
      </div>
    );
  }

  return (
    <div role="alert" data-testid={testId} className="flex flex-col items-center gap-3 py-8 px-4 text-center">
      <AlertTriangle size={22} className="text-error" aria-hidden="true" />
      <h3 className="text-base font-semibold text-error tracking-tight">{title}</h3>
      <p className="text-xs text-foreground/90 max-w-md select-text">{detail}</p>
      {reference && <ErrorReference reference={reference} />}
      {children}
      {retryButton && <div className="mt-1">{retryButton}</div>}
    </div>
  );
};

export default ErrorNotice;
