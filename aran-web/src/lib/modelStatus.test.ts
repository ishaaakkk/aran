import { describe, expect, it } from 'vitest'
import { modelStatus } from './modelStatus'

describe('modelStatus — the header says what the model read did, nothing more', () => {
  it('a model that failed to load is never a green "Saved"', () => {
    const status = modelStatus({ projectSelected: true, loading: false, error: 'Failed to load integration model', loaded: false })
    expect(status).toEqual({ variant: 'err', label: "Couldn't load" })
  })

  it('a page load reads "Loading…", not "Saving…"', () => {
    expect(modelStatus({ projectSelected: true, loading: true, error: null, loaded: false }))
      .toEqual({ variant: 'warn', label: 'Loading…' })
  })

  it('a loaded model is "Up to date" — what is shown is what the server holds', () => {
    expect(modelStatus({ projectSelected: true, loading: false, error: null, loaded: true }))
      .toEqual({ variant: 'ok', label: 'Up to date' })
  })

  it('no project, no status', () => {
    expect(modelStatus({ projectSelected: false, loading: false, error: null, loaded: false })).toBeNull()
  })

  it('never claims a save', () => {
    const states = [true, false].flatMap((loading) => [null, 'x'].flatMap((error) =>
      [true, false].map((loaded) => modelStatus({ projectSelected: true, loading, error, loaded }))))
    for (const s of states) expect(s?.label ?? '').not.toMatch(/sav/i)
  })
})
