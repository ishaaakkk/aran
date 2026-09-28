import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyText } from './clipboard'

/**
 * UI audit J5-F17: on an `http://` LAN install `navigator.clipboard` does not exist (it needs a
 * secure context), so Share threw and did nothing; with the permission denied it still said
 * "✓ copied". The one rule now: report "copied" only when the write resolved.
 */
afterEach(() => {
  vi.unstubAllGlobals()
})

describe('copyText (J5-F17)', () => {
  it('is false when the page has no clipboard API (an insecure context)', async () => {
    vi.stubGlobal('navigator', {})
    await expect(copyText('x')).resolves.toBe(false)
  })

  it('is false when the write is refused', async () => {
    vi.stubGlobal('navigator', { clipboard: { writeText: () => Promise.reject(new Error('denied')) } })
    await expect(copyText('x')).resolves.toBe(false)
  })

  it('is true only once the write resolved', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    await expect(copyText('hello')).resolves.toBe(true)
    expect(writeText).toHaveBeenCalledWith('hello')
  })
})
