import { describe, expect, it } from 'vitest'
import { ApiError } from '@/services/api'
import { checkFromError, checkFromRepository, checkFromResult, connectionStatusView } from './connectionStatus'
import type { DataRepository } from '@/services/types'

/**
 * What the repositories list's status column says (UI audit J2-F1, J2-F19).
 *
 * The column used to be fed by a probe of every repository on every visit, which posted the GET
 * body — password '********' — back as the thing to dial, so a password-protected Postgres read
 * UNREACHABLE forever, and a slow first driver download read UNREACHABLE too. These pin the three
 * truths the column may tell: what the last test found (and when), that no test has run, or that
 * the check itself could not complete — never "unreachable" for a test that never answered.
 */

const NOW = Date.parse('2026-09-25T12:00:00Z')

function pg(overrides: Partial<DataRepository> = {}): DataRepository {
  return {
    id: 'r1', projectId: 'p1', tenantId: 't1', name: 'prod-pg', description: '',
    type: 'RELATIONAL_DB', engine: 'POSTGRESQL', host: 'db', port: 5432, database: 'app',
    password: '********', lastConnectionCheck: null,
    ...overrides,
  } as DataRepository
}

describe('the repositories list (source pin, J2-F1)', () => {
  it('tests saved repositories only through the stored-repository route, never by posting a row back', async () => {
    // The body-only route with a row's GET body is exactly how '********' reached the database
    // as a password. No DOM in this project, so the wiring is pinned at the source.
    const { readFileSync } = await import('node:fs')
    const view = readFileSync(new URL('../views/RepositoriesListView.tsx', import.meta.url), 'utf8')
    expect(view).not.toContain('api.testConnection(')
    expect(view).toContain('api.testStoredConnection(')
  })
})

describe('checkFromRepository', () => {
  it('reads the recorded check — nothing is dialed to fill the column', () => {
    const check = checkFromRepository(pg({
      lastConnectionCheck: {
        success: true, message: 'Connected to POSTGRESQL at db:5432', details: null,
        testedAt: '2026-09-25T09:00:00Z',
      },
    }))
    expect(check).toEqual({
      state: 'ok', message: 'Connected to POSTGRESQL at db:5432', details: null,
      testedAt: '2026-09-25T09:00:00Z',
    })
  })

  it('has no check for a repository never tested (or saved since)', () => {
    expect(checkFromRepository(pg())).toBeNull()
    expect(checkFromRepository(pg({ lastConnectionCheck: undefined }))).toBeNull()
  })
})

describe('checkFromResult', () => {
  it('keeps a failed probe as a failure with its diagnostics', () => {
    expect(checkFromResult({
      success: false, message: 'Database connection refused',
      details: 'FATAL: password authentication failed', testedAt: '2026-09-25T11:59:00Z',
    })).toEqual({
      state: 'failed', message: 'Database connection refused',
      details: 'FATAL: password authentication failed', testedAt: '2026-09-25T11:59:00Z',
    })
  })
})

describe('checkFromError', () => {
  it('a client timeout is "could not check", never a verdict about the source', () => {
    const check = checkFromError(new ApiError(504, null, 'API request timed out (service may be offline)'))
    expect(check.state).toBe('unknown')
    expect(check.message).toMatch(/no answer within 60 s/i)
    expect(check.testedAt).toBeNull()
  })

  it('a 403 or a 5xx is "could not check" too, with the server\'s own words', () => {
    const forbidden = checkFromError(new ApiError(403, null, 'request failed: 403'))
    const broken = checkFromError(new ApiError(500, { detail: 'Failed to download driver JAR' } as never,
      'Failed to download driver JAR'))
    expect(forbidden.state).toBe('unknown')
    expect(broken.state).toBe('unknown')
    expect(broken.message).toContain('Failed to download driver JAR')
  })
})

describe('connectionStatusView', () => {
  it('says "Not tested" when no test has run, rather than guessing', () => {
    const view = connectionStatusView('RELATIONAL_DB', null, false, NOW)
    expect(view.label).toBe('Not tested')
    expect(view.variant).toBe('idle')
    expect(view.when).toBeNull()
  })

  it('names a passed test "Connected" and says how old it is', () => {
    const view = connectionStatusView('RELATIONAL_DB', {
      state: 'ok', message: 'Connected', details: null, testedAt: '2026-09-25T09:00:00Z',
    }, false, NOW)
    expect(view.label).toBe('Connected')
    expect(view.variant).toBe('ok')
    expect(view.when).toBe('3 h ago')
  })

  it('does not call an object store "connected": its probe checks the endpoint, not the keys', () => {
    const view = connectionStatusView('OBJECT_STORAGE', {
      state: 'ok', message: 'Endpoint reachable — HTTP 403', details: null, testedAt: '2026-09-25T11:00:00Z',
    }, false, NOW)
    expect(view.label).toBe('Endpoint reachable')
    expect(view.title).toMatch(/credentials .*not checked/i)
  })

  it('names a failed test "Failed" and carries the reason in its title', () => {
    const view = connectionStatusView('RELATIONAL_DB', {
      state: 'failed', message: 'Database connection refused', details: 'password authentication failed',
      testedAt: '2026-09-25T11:00:00Z',
    }, false, NOW)
    expect(view.label).toBe('Failed')
    expect(view.variant).toBe('err')
    expect(view.title).toContain('password authentication failed')
  })

  it('says "Could not check" — not "Unreachable" — when the test itself did not answer', () => {
    const view = connectionStatusView('RELATIONAL_DB', {
      state: 'unknown', message: 'No answer within 60 s', details: null, testedAt: null,
    }, false, NOW)
    expect(view.label).toBe('Could not check')
    expect(view.variant).toBe('warn')
  })

  it('shows a test in flight as such', () => {
    expect(connectionStatusView('RELATIONAL_DB', null, true, NOW).label).toBe('Testing…')
  })

  it('has nothing to test for an upload-only repository', () => {
    const view = connectionStatusView('FILE_SYSTEM', null, false, NOW)
    expect(view.label).toBe('Uploads only')
    expect(view.testable).toBe(false)
  })
})
