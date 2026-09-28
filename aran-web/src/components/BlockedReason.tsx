import React from 'react';

/**
 * Why an action is unavailable, as visible text: a `title` reaches neither touch nor keyboard
 * users, and Tab skips a disabled button, so the reason sits beside it and the button points at
 * it with `aria-describedby`.
 *
 * Always mounted — empty when nothing blocks. A live region that arrives already holding its text
 * is often not announced by screen readers; a change inside one that is already mounted is, so the
 * reason changing (loading → "create a project first") is heard (second review of UA2, WCAG 4.1.3).
 */
export const BlockedReason: React.FC<{ id: string; reason: string | null; className?: string }> = ({
  id, reason, className = 'text-[11px] text-muted-foreground',
}) => (
  <span id={id} role="status" className={className}>
    {reason ?? ''}
  </span>
);

export default BlockedReason;
