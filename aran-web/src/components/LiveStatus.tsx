import React from 'react';

/**
 * A status message in a live region that is mounted before it has anything to say.
 *
 * A `role="status"` element that arrives already holding its text is often not announced by
 * screen readers; a change inside one that is already mounted is (WCAG 4.1.3). So this is rendered
 * unconditionally — visually hidden while empty, so it takes no room — and the caller changes its
 * children. {@link BlockedReason} is the same rule for why an action is unavailable; this is it for
 * an outcome (third review of UA2).
 *
 * Keep controls outside it: what is announced should be the sentence, not a button's name.
 */
export const LiveStatus: React.FC<{
  id?: string;
  testId?: string;
  className?: string;
  children?: React.ReactNode;
}> = ({ id, testId, className = '', children }) => {
  const empty = children === null || children === undefined || children === false || children === '';
  return (
    <p id={id} role="status" data-testid={testId} className={empty ? 'sr-only' : className}>
      {empty ? null : children}
    </p>
  );
};

export default LiveStatus;
