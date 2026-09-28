import { describe, expect, it } from 'vitest';
import { formatZoned } from './zonedTime';

describe('formatZoned (J4-F18)', () => {
  it('says the zone, so a promote time is not misread as the reader\'s own', () => {
    const utc = formatZoned('2026-09-25T10:00:00Z', 'en-GB', 'UTC');
    expect(utc).toContain('UTC');
    expect(utc).toContain('10:00');
    expect(formatZoned('2026-09-25T10:00:00Z', 'en-GB', 'Europe/Madrid')).toMatch(/12:00.*(CEST|GMT\+2)/);
  });

  it('hands back an unparseable value as it came rather than "Invalid Date"', () => {
    expect(formatZoned('not a date')).toBe('not a date');
  });
});
