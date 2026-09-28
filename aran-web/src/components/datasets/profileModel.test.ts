import { describe, it, expect } from 'vitest'
import { profilingRequest } from './profileModel'

describe('profilingRequest', () => {
  it('keeps numeric columns: the Profile tab never narrows a generation to varchar only (J3-F2)', () => {
    // A run supersedes the dataset's stored SNAPSHOT generation. `varcharOnly: 'true'` dropped
    // every integer key from it, so discovery could no longer propose `author_id = id` and the
    // planner lost NDV and key evidence — silently. Bootstrap profiles with 'false'; so must this.
    const req = profilingRequest({ id: 'ds-1', snapshotId: 'snap-1' })
    expect(req.operation).toBe('profile')
    expect(req.parameters.varcharOnly).toBe('false')
  })

  it('persists what it measures: datasetId is always sent', () => {
    // Without datasetId the operator computes and returns a profile and stores nothing.
    const req = profilingRequest({ id: 'ds-1', snapshotId: 'snap-1' })
    expect(req.parameters.datasetId).toBe('ds-1')
    expect(req.parameters.snapshotId).toBe('snap-1')
  })
})
