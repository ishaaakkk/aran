import { describe, it, expect } from 'vitest'
import { accessLabel, accessNote, readsLive, renameImpact } from './datasetAccess'
import type { DataRepository } from '@/services/types'

describe('dataset access mode (J2-F3)', () => {
  it('labels each mode in the words a user reads it', () => {
    expect(accessLabel('SNAPSHOT')).toBe('Snapshot')
    expect(accessLabel('LIVE')).toBe('Live')
    expect(accessLabel('LIVE_WITH_COPY')).toBe('Live + copy')
  })

  it('says what each mode means for a query, including when the copy is used', () => {
    expect(accessNote('LIVE')).toMatch(/every query reads the source/i)
    expect(accessNote('LIVE_WITH_COPY')).toMatch(/interval refresh/i)
    expect(accessNote('SNAPSHOT')).toMatch(/lake snapshot/i)
  })

  it('admits not knowing, rather than guessing, for a response that carries no mode', () => {
    expect(accessLabel(undefined)).toBe('—')
    expect(accessNote(undefined)).toMatch(/not reported/i)
  })
})


const base = { id: 'r1', projectId: 'p1', tenantId: 't1', name: 'repo', description: '' };

const postgres = (federated: boolean): DataRepository => ({
  ...base, type: 'RELATIONAL_DB', engine: 'POSTGRESQL', host: 'h', port: 5432, database: 'd', federated,
});

describe('readsLive — the client mirror of SourceAccess.resolve', () => {
  it('a federated relational repository on a scanner engine is read live', () => {
    expect(readsLive(postgres(true), { snapshotId: undefined })).toBe(true);
    // Even with a lake copy: a federated relational repository never serves the copy by default.
    expect(readsLive(postgres(true), { snapshotId: 'snap' })).toBe(true);
  });

  it('a federation request the engine cannot grant is not live', () => {
    const oracle: DataRepository = { ...base, type: 'RELATIONAL_DB', engine: 'ORACLE', host: 'h', port: 1, database: 'd', federated: true };
    expect(readsLive(oracle, { snapshotId: undefined })).toBe(false);
    expect(readsLive(postgres(false), { snapshotId: undefined })).toBe(false);
  });

  it('object storage and Iceberg are live until the dataset is copied into the lake', () => {
    const s3: DataRepository = { ...base, type: 'OBJECT_STORAGE', provider: 's3', bucketName: 'b' };
    expect(readsLive(s3, { snapshotId: undefined })).toBe(true);
    expect(readsLive(s3, { snapshotId: 'snap' })).toBe(false);
  });

  it('uploads are never live, and an unknown repository is not guessed live', () => {
    expect(readsLive({ ...base, type: 'FILE_SYSTEM' }, { snapshotId: 'snap' })).toBe(false);
    expect(readsLive(undefined, { snapshotId: undefined })).toBe(false);
  });
});

describe('renameImpact (UI-OD8)', () => {
  it('blocks any rename of a live dataset', () => {
    expect(renameImpact(true, 'orders', 'orders')).toBe('blocked');
    expect(renameImpact(true, 'orders', 'orders_v2')).toBe('blocked');
  });

  it('warns — never blocks — when an upload is renamed (d1-schema-drift relies on it)', () => {
    expect(renameImpact(false, 'acm', 'acm-renamed')).toBe('warn');
  });

  it('says nothing when the name is unchanged or empty', () => {
    expect(renameImpact(false, 'acm', 'acm')).toBe('none');
    expect(renameImpact(false, 'acm', ' acm ')).toBe('none');
    expect(renameImpact(false, 'acm', '')).toBe('none');
  });
});
