import { describe, expect, it } from 'vitest'
import { compactCount, formatBytes, relativeTime } from './adminFormat'

const NOW = Date.parse('2026-08-14T12:00:00Z')

function ago(ms: number): string {
  return new Date(NOW - ms).toISOString()
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

describe('relativeTime', () => {
  it('says "never" for a token that has not been used', () => {
    expect(relativeTime(null, NOW)).toBe('never')
  })

  it('does not confuse "never" with an unparseable timestamp', () => {
    expect(relativeTime('not-a-date', NOW)).toBe('never')
  })

  it('collapses the last minute to "just now"', () => {
    expect(relativeTime(ago(30_000), NOW)).toBe('just now')
    expect(relativeTime(ago(NOW - NOW), NOW)).toBe('just now')
  })

  it('steps through minutes, hours and days', () => {
    expect(relativeTime(ago(5 * MINUTE), NOW)).toBe('5 min ago')
    expect(relativeTime(ago(59 * MINUTE), NOW)).toBe('59 min ago')
    expect(relativeTime(ago(3 * HOUR), NOW)).toBe('3 h ago')
    expect(relativeTime(ago(23 * HOUR), NOW)).toBe('23 h ago')
    expect(relativeTime(ago(2 * DAY), NOW)).toBe('2 d ago')
    expect(relativeTime(ago(29 * DAY), NOW)).toBe('29 d ago')
  })

  it('falls back to a date once relative time stops being informative', () => {
    const old = relativeTime(ago(400 * DAY), NOW)
    expect(old).not.toMatch(/ago|never|just now/)
  })
})

describe('compactCount', () => {
  it('keeps small counts exact — the difference between 3 and 30 is the point', () => {
    expect(compactCount(0)).toBe('0')
    expect(compactCount(3)).toBe('3')
    expect(compactCount(999)).toBe('999')
  })

  it('shortens thousands and millions', () => {
    expect(compactCount(1000)).toBe('1.0k')
    expect(compactCount(12_500)).toBe('12.5k')
    expect(compactCount(999_999)).toBe('1000.0k')
    expect(compactCount(1_000_000)).toBe('1.0M')
    expect(compactCount(4_200_000)).toBe('4.2M')
  })
})

describe('formatBytes', () => {
  it('renders no volume as an em-dash, not "0 B"', () => {
    expect(formatBytes(0)).toBe('—')
  })

  it('shows whole bytes without a decimal', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(1023)).toBe('1023 B')
  })

  it('climbs binary units', () => {
    expect(formatBytes(1024)).toBe('1.0 KB')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB')
    expect(formatBytes(3 * 1024 ** 3)).toBe('3.0 GB')
  })

  it('stops at terabytes rather than inventing a unit', () => {
    expect(formatBytes(4 * 1024 ** 5)).toBe('4096.0 TB')
  })
})
