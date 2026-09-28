import { describe, expect, it } from 'vitest'
import { formatRefreshed, initialResource, resourceReducer } from './useAdminResource'

/**
 * Audit J7-F11: switching the reporting window blanked the table and one failed read blanked
 * every section. The transitions that stop both are pure, so they are pinned here.
 */

describe('resourceReducer', () => {
  const loaded = resourceReducer<number[]>(initialResource(), {
    type: 'success', data: [1, 2], at: new Date('2026-09-25T14:02:00Z'),
  })

  it('starts loading with nothing to show', () => {
    expect(initialResource()).toEqual({ data: null, error: null, loading: true, refreshedAt: null })
  })

  it('keeps the data on screen while a reload is in flight — no blank table on a window change', () => {
    const reloading = resourceReducer(loaded, { type: 'start' })
    expect(reloading.data).toEqual([1, 2])
    expect(reloading.loading).toBe(true)
  })

  it('a failed refresh keeps the earlier data and its age, and says it failed', () => {
    const failed = resourceReducer(resourceReducer(loaded, { type: 'start' }),
      { type: 'failure', error: 'lake did not answer' })
    expect(failed.data).toEqual([1, 2])
    expect(failed.error).toBe('lake did not answer')
    expect(failed.refreshedAt).toEqual(new Date('2026-09-25T14:02:00Z'))
    expect(failed.loading).toBe(false)
  })

  it('a retry clears the error before it runs, and a success replaces it', () => {
    const failed = resourceReducer(initialResource<number[]>(), { type: 'failure', error: 'x' })
    expect(resourceReducer(failed, { type: 'start' }).error).toBeNull()
  })
})

describe('formatRefreshed', () => {
  it('states the zone — this line gets pasted into tickets', () => {
    expect(formatRefreshed(new Date('2026-09-25T14:02:59Z'))).toBe('14:02 UTC')
  })
})
