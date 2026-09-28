import { describe, expect, it } from 'vitest'
import { fanOut } from './fanOut'

describe('fanOut (J8-F2)', () => {
  it('keeps what loaded and names what did not, instead of dropping the failure', async () => {
    const boom = new Error('504 from the lake')
    const result = await fanOut(
      [{ id: 'a', name: 'papers' }, { id: 'b', name: 'health' }, { id: 'c', name: 'venues' }],
      (p) => p.name,
      async (p) => {
        if (p.id === 'b') throw boom
        return [`${p.id}-1`, `${p.id}-2`]
      },
    )

    expect(result.items).toEqual(['a-1', 'a-2', 'c-1', 'c-2'])
    expect(result.failures).toEqual([{ name: 'health', error: boom }])
  })

  it('keeps the sources\' order whatever order the answers arrive in', async () => {
    const delay = (ms: number) => new Promise((r) => setTimeout(r, ms))
    const result = await fanOut([30, 1, 10], String, async (ms) => {
      await delay(ms)
      return [ms]
    })
    expect(result.items).toEqual([30, 1, 10])
  })
})
