import type { LicenseSummary } from '@/services/types';

/**
 * The unlimited licensing mode's "no ceiling" sentinel (`UnlimitedLicensingConfig`: the quota
 * gates compare `current >= max`, so Integer.MAX_VALUE is unreachable rather than large). A
 * count this size is never a limit to show (UI audit J7-F19).
 */
export const UNLIMITED = 2147483647;

export const isUnlimited = (max: number): boolean => max >= UNLIMITED;

/** The plan as a person would name it: the self-hosted unlimited mode is not a plan bought. */
export function planLabel(license: LicenseSummary): string {
  if (isUnlimited(license.maxUsers) && isUnlimited(license.maxProjects)) return 'Unlimited (self-hosted)';
  return license.plan;
}

export function quotaLabel(current: number, max: number): string {
  return isUnlimited(max) ? `${current} · no limit` : `${current} / ${max}`;
}
