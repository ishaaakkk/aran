import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { LicensePanel, OrganizationSummary, TenantSelectView } from './TenantSelectView'
import { UNLIMITED } from '../lib/licenseDisplay'
import type { LicenseSummary, TenantMembership } from '../services/types'

/**
 * UI audit J1-F19 = J7-F19: Account › Organization read "Your organizations — Select or
 * review…" and "Active Session", so an owner looked for a switcher that cannot exist (one
 * organization per user, V23), could not select the tenant ID to copy it, and read
 * "ENTERPRISE" and "3 / 2147483647" on a self-hosted install. The view reads the auth store,
 * which `renderToStaticMarkup` sees only in its initial state, so its parts are rendered directly.
 */
const TENANT: TenantMembership = {
  tenantId: '0b6f3c9e-1111-4222-8333-944455556666',
  slug: 'upc',
  name: 'UPC',
  role: 'OWNER',
  joinedAt: '2026-08-01T00:00:00Z',
}

const UNLIMITED_LICENSE = {
  tenantId: TENANT.tenantId,
  plan: 'ENTERPRISE',
  status: 'ACTIVE',
  mutationsAllowed: true,
  maxUsers: UNLIMITED,
  currentUsers: 3,
  maxProjects: UNLIMITED,
  currentProjects: 2,
  features: [],
  expiresAt: null,
} as unknown as LicenseSummary

describe('the Organization page (J1-F19, J7-F19)', () => {
  it('is titled "Organization" and says there is nothing to switch', () => {
    const html = renderToStaticMarkup(<MemoryRouter><TenantSelectView /></MemoryRouter>)
    expect(html).toContain('>Organization<')
    expect(html).not.toContain('Your organizations')
    expect(html).not.toMatch(/Select or review/)
    expect(html).not.toContain('select-none')
  })

  it('shows name, role and a copyable ID, with no session-switching language', () => {
    const html = renderToStaticMarkup(<OrganizationSummary tenant={TENANT} />)
    expect(html).toContain('UPC')
    expect(html).toContain('OWNER')
    expect(html).toContain(TENANT.tenantId)
    expect(html).toContain('aria-label="Copy organization ID"')
    expect(html).not.toContain('Active Session')
  })

  it('names the self-hosted unlimited mode and never prints the sentinel', () => {
    const html = renderToStaticMarkup(<LicensePanel license={UNLIMITED_LICENSE} />)
    expect(html).toContain('Unlimited (self-hosted)')
    expect(html).not.toContain(String(UNLIMITED))
    expect(html).toContain('3 · no limit')
  })
})
