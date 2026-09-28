import React, { useEffect, useRef } from 'react';

/**
 * Shown when Share could not copy (UI audit J5-F17) — no clipboard API outside a secure context,
 * or the permission refused. The link is put in a read-only field and selected, so the person
 * who just pressed Share copies it with one shortcut instead of being told it was copied when it
 * was not. Focus moves here because the user's own click asked for the link (the field appears
 * in answer to it), not on page load — the case `jsx-a11y/no-autofocus` exists to prevent.
 */
export const ShareLinkFallback: React.FC<{ url: string; onDismiss: () => void }> = ({ url, onDismiss }) => {
  const inputRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.select();
  }, [url]);

  return (
    <span className="inline-flex items-center gap-1" role="status" data-testid="oql-share-fallback">
      <span className="text-[11px] text-muted-foreground">Couldn’t copy — copy this link:</span>
      <input
        ref={inputRef}
        readOnly
        value={url}
        aria-label="Share link"
        onFocus={(e) => e.currentTarget.select()}
        className="w-56 text-[11px] font-mono bg-muted border border-border rounded px-1.5 py-0.5 text-foreground"
      />
      <button type="button" className="btn btn-ghost sm" onClick={onDismiss} aria-label="Dismiss share link">
        ×
      </button>
    </span>
  );
};

export default ShareLinkFallback;
