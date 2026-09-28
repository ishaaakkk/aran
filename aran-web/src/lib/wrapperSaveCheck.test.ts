import { describe, expect, it } from 'vitest'
import { describeWrapperSaveCheck } from './wrapperSaveCheck'

/**
 * J4-F11, wrapper half (second review of UA2): a save that outlived its deadline used to fade as a
 * four-second toast, with nothing re-reading the model to say whether the wrapper was stored — and
 * the natural next click, Save again, starts a second verify scan of the source.
 */
describe('describeWrapperSaveCheck', () => {
  const sql = 'SELECT a AS "x" FROM ${SNAPSHOT}'

  it('a stored wrapper equal to the one submitted is a save that landed', () => {
    expect(describeWrapperSaveCheck(sql, sql)).toMatchObject({ kind: 'saved' })
  })

  it('a different stored wrapper is not yet saved, and says not to save again', () => {
    const check = describeWrapperSaveCheck(sql, 'SELECT * FROM ${SNAPSHOT}')
    expect(check.kind).toBe('not-yet')
    expect(check.message).toMatch(/Check again/)
    expect(check.message).toMatch(/second verify/)
  })

  it('a class with no stored override is not yet saved either', () => {
    expect(describeWrapperSaveCheck(sql, null).kind).toBe('not-yet')
  })

  it('a re-read that failed says it could not check — never "saved" and never "failed"', () => {
    const check = describeWrapperSaveCheck(sql, undefined)
    expect(check.kind).toBe('unknown')
    expect(check.message).not.toMatch(/^Saved/)
    expect(check.message).not.toMatch(/failed/i)
  })
})
