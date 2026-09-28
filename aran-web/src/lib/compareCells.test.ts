import { describe, it, expect } from 'vitest';
import { compareCells } from '@/lib/compareCells';

/**
 * The workbench sorted numeric columns lexicographically because the engine stringified
 * every value at the JDBC boundary. Values now keep their JSON type; these pin the
 * comparator that consumes them.
 */
describe('compareCells', () => {
  const sorted = (xs: unknown[]) => [...xs].sort(compareCells);

  it('orders numbers numerically, not as strings', () => {
    // The bug: as strings these order "100" < "20" < "9".
    expect(sorted([9, 100, 20])).toEqual([9, 20, 100]);
  });

  it('orders negative numbers below positive ones', () => {
    expect(sorted([3, -10, 0])).toEqual([-10, 0, 3]);
  });

  it('orders strings lexicographically', () => {
    expect(sorted(['pear', 'apple', 'fig'])).toEqual(['apple', 'fig', 'pear']);
  });

  it('sorts nulls, undefined and blanks last regardless of direction', () => {
    expect(sorted([null, 5, 1])).toEqual([1, 5, null]);
    expect(sorted([undefined, 'b', 'a'])).toEqual(['a', 'b', undefined]);
    expect(sorted(['', 2, 1])).toEqual([1, 2, '']);
  });

  it('orders booleans false before true', () => {
    expect(sorted([true, false, true])).toEqual([false, true, true]);
  });

  it('still orders numerically the high-precision values the backend stringifies', () => {
    // BIGINT past 2^53, DECIMAL and HUGEINT arrive as strings by design, to avoid
    // JSON.parse silently rounding them. They must not regress to lexicographic order.
    expect(sorted(['9007199254740993', '9007199254740992'])).toEqual([
      '9007199254740992',
      '9007199254740993',
    ]);
    expect(sorted(['1.50', '1.25', '10.00'])).toEqual(['1.25', '1.50', '10.00']);
  });

  it('is symmetric', () => {
    expect(compareCells(1, 2)).toBeLessThan(0);
    expect(compareCells(2, 1)).toBeGreaterThan(0);
    expect(compareCells(2, 2)).toBe(0);
  });

  it('falls back to string order for equal-valued but differently-typed cells', () => {
    // 5 and "5" compare numerically equal, so ordering is stable rather than arbitrary.
    expect(compareCells(5, '5')).toBe(0);
  });
});
