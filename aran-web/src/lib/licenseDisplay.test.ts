import { describe, expect, it } from 'vitest'
import { planLabel, quotaLabel, UNLIMITED } from './licenseDisplay'
import type { LicenseSummary } from '../services/types'

/**
 * UI audit J7-F19: a self-hosted owner read "ENTERPRISE" and meters of "3 / 2147483647". The
 * unlimited licensing mode (`UnlimitedLicensingConfig`) reports Integer.MAX_VALUE as a sentinel
 * for "no ceiling" — a number the page must never print as a limit.
 */
function license(overrides: Partial<LicenseSummary> = {}): LicenseSummary {
  return {
    tenantId: 't-1',
    plan: 'ENTERPRISE',
    status: 'ACTIVE',
    mutationsAllowed: true,
    maxUsers: UNLIMITED,
    currentUsers: 3,
    maxProjects: UNLIMITED,
    currentProjects: 2,
    features: [],
    expiresAt: null,
    ...overrides,
  } as LicenseSummary
}

describe('licenseDisplay (J7-F19)', () => {
  it('names the self-hosted unlimited mode instead of an ENTERPRISE plan nobody bought', () => {
    expect(planLabel(license())).toBe('Unlimited (self-hosted)')
  })

  it('keeps a real plan name when there are real limits', () => {
    expect(planLabel(license({ plan: 'PRO', maxUsers: 10, maxProjects: 20 }))).toBe('PRO')
  })

  it('never prints the sentinel as a ceiling', () => {
    expect(quotaLabel(3, UNLIMITED)).toBe('3 · no limit')
    expect(quotaLabel(3, 10)).toBe('3 / 10')
  })
})
