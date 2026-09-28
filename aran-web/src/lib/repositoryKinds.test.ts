import { describe, expect, it } from 'vitest'
import {
  connectionTestPayload,
  FEDERATED_REPOSITORY_NOTE,
  importModeNote,
  OBJECT_STORAGE_NOTE,
  OBJECT_STORAGE_PROVIDERS,
  REPOSITORIES_EMPTY_STATE,
  REPOSITORY_TYPE_LABELS,
  REPOSITORY_TYPE_ORDER,
} from './repositoryKinds'

/**
 * What each repository kind is offered as, and what the forms say it does (J2-F4, J2-F18,
 * J2-F30; UI-OD13). The picker used to offer "Object Storage / Data Lake" as
 * if a bucket could be enrolled, and the empty state promised "database, API, or object storage
 * bucket" — V35: no production path builds an object-storage dataset, and there is no API kind.
 */
describe('the repository kind picker', () => {
  it('labels object storage "connect only — upload files" until a bucket enrolment path exists (UI-OD13)', () => {
    expect(REPOSITORY_TYPE_LABELS.OBJECT_STORAGE).toContain('connect only')
    expect(REPOSITORY_TYPE_LABELS.OBJECT_STORAGE).toContain('upload files')
    expect(REPOSITORY_TYPE_LABELS.OBJECT_STORAGE).not.toContain('Data Lake')
  })

  it('offers every kind exactly once', () => {
    expect(new Set(REPOSITORY_TYPE_ORDER).size).toBe(REPOSITORY_TYPE_ORDER.length)
    expect([...REPOSITORY_TYPE_ORDER].sort()).toEqual(Object.keys(REPOSITORY_TYPE_LABELS).sort())
  })

  it('marks the object-storage providers no test has ever reached as untested (J2-F18)', () => {
    const label = (v: string) => OBJECT_STORAGE_PROVIDERS.find((p) => p.value === v)?.label ?? ''
    expect(label('GCS')).toContain('untested')
    expect(label('AZURE')).toContain('untested')
    expect(label('S3')).not.toContain('untested')
  })

  it('says a passing object-storage test proves the endpoint answers, not that the keys work', () => {
    expect(OBJECT_STORAGE_NOTE).toContain('does not prove the keys')
    expect(OBJECT_STORAGE_NOTE).toContain('upload')
  })
})

describe('testing a connection before naming it (J2-F27)', () => {
  it('fills a placeholder name only for the test, since the request requires one', () => {
    const payload = connectionTestPayload({ name: '  ', description: '', type: 'FILE_SYSTEM' })
    expect(payload.name).toBe('connection test')
  })

  it('keeps a name the user typed', () => {
    expect(connectionTestPayload({ name: 'prod', description: '', type: 'FILE_SYSTEM' }).name).toBe('prod')
  })
})

describe('the Repositories empty state (J2-F18, J1-F13)', () => {
  it('promises only kinds this build can take data from', () => {
    expect(REPOSITORIES_EMPTY_STATE).not.toMatch(/\bAPI,|object storage bucket/)
    expect(REPOSITORIES_EMPTY_STATE).toContain('Singer tap')
  })
})

describe('what importing from a federated repository does (J2-F4)', () => {
  it('a federated repository keeps queries live; the copy is served only under an interval refresh', () => {
    const note = importModeNote('table', true)
    expect(note).not.toContain('offline-safe')
    expect(note).toContain('queries stay live')
    expect(note).toContain('interval refresh')
  })

  it('a non-federated import is the snapshot queries read', () => {
    expect(importModeNote('table', false)).toContain('queries read the copy')
  })

  it('the federated checkbox says the same, not "instead of downloading snapshots"', () => {
    expect(FEDERATED_REPOSITORY_NOTE).not.toContain('instead of downloading snapshots')
    expect(FEDERATED_REPOSITORY_NOTE).toContain('interval refresh')
  })
})
