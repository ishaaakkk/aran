/**
 * What the repositories list's status column may say about a connection (UI audit J2-F1, J2-F19).
 *
 * The column used to probe every repository on page load and post each row's GET body back as the
 * thing to dial — so a stored password went out as the '********' mask and a password-protected
 * Postgres read UNREACHABLE on every visit, while a first driver download simply timed out into
 * the same word. Now nothing runs on load: the column shows the last test someone ran (recorded
 * server-side, with its time), or that none has run, or that a test could not complete. Fivetran
 * and Airbyte show a connector's last setup/connection test the same way — a result with a
 * timestamp, rerun on demand — rather than probing on every page view.
 */
import { ApiError } from '@/services/api'
import { relativeTime } from '@/lib/adminFormat'
import type { ConnectionTestResult, DataRepository, RepositoryType } from '@/services/types'

/**
 * `ok`/`failed` are the source's answer; `unknown` means the test itself did not complete — a
 * client deadline, a 403, a server error — which says nothing about the source.
 */
export interface RowCheck {
  state: 'ok' | 'failed' | 'unknown'
  message: string
  details: string | null
  /** When the probe ran; null when it never answered. */
  testedAt: string | null
}

export function checkFromRepository(repo: DataRepository): RowCheck | null {
  const last = repo.lastConnectionCheck
  if (!last) return null
  return {
    state: last.success ? 'ok' : 'failed',
    message: last.message,
    details: last.details ?? null,
    testedAt: last.testedAt,
  }
}

export function checkFromResult(result: ConnectionTestResult): RowCheck {
  return {
    state: result.success ? 'ok' : 'failed',
    message: result.message,
    details: result.details ?? null,
    testedAt: result.testedAt,
  }
}

/** A test that threw never produced a verdict — whatever the reason, it is "could not check". */
export function checkFromError(err: unknown): RowCheck {
  let message: string
  if (err instanceof ApiError && err.status === 504 && err.problem === null) {
    message = 'No answer within 60 s. A slow source, or a first test that downloads its database '
      + 'driver, can take that long — try again.'
  } else if (err instanceof Error && err.message) {
    message = err.message
  } else {
    message = 'The test did not complete.'
  }
  return { state: 'unknown', message, details: null, testedAt: null }
}

export interface ConnectionStatusView {
  label: string
  variant: 'ok' | 'warn' | 'err' | 'idle' | 'accent'
  /** The whole story for a tooltip and a screen reader: what was checked and what it said. */
  title: string
  /** "3 h ago", or null when there is no dated result to show. */
  when: string | null
  /** False for a kind with nothing to dial. */
  testable: boolean
}

export function connectionStatusView(
  type: RepositoryType,
  check: RowCheck | null,
  testing: boolean,
  now: number = Date.now(),
): ConnectionStatusView {
  if (type === 'FILE_SYSTEM') {
    return {
      label: 'Uploads only', variant: 'idle', when: null, testable: false,
      title: 'Nothing to connect to: this repository holds the files uploaded to it.',
    }
  }
  if (testing) {
    return { label: 'Testing…', variant: 'accent', when: null, testable: true, title: 'Connection test running.' }
  }
  if (!check) {
    return {
      label: 'Not tested', variant: 'idle', when: null, testable: true,
      title: 'No connection test has run since this repository was saved. Test it to find out.',
    }
  }
  const when = check.testedAt ? relativeTime(check.testedAt, now) : null
  const detail = check.details ? ` — ${check.details}` : ''
  const at = check.testedAt ? ` (tested ${new Date(check.testedAt).toLocaleString()})` : ''
  switch (check.state) {
    case 'ok':
      if (type === 'OBJECT_STORAGE') {
        // The probe is an HTTP GET to the endpoint: any answer, 403 included, passes. Saying
        // "connected" would claim the keys and bucket work (J2-F19: wrong keys read Reachable).
        return {
          label: 'Endpoint reachable', variant: 'ok', when, testable: true,
          title: `${check.message}${at}. The endpoint answered; credentials and bucket were not checked.`,
        }
      }
      return { label: 'Connected', variant: 'ok', when, testable: true, title: `${check.message}${at}` }
    case 'failed':
      return { label: 'Failed', variant: 'err', when, testable: true, title: `${check.message}${detail}${at}` }
    case 'unknown':
      return {
        label: 'Could not check', variant: 'warn', when: null, testable: true,
        title: `The test did not complete, so this says nothing about the source: ${check.message}`,
      }
  }
}
