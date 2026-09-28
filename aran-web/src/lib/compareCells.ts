/**
 * Orders two result cells by type rather than by their string form.
 *
 * <p>The engine used to stringify every value at the JDBC boundary, so this comparator's
 * `va < vb` was always a string comparison and numeric columns sorted lexicographically —
 * `"100" < "20" < "9"`. Values now arrive with their JSON type intact, so numbers must be
 * compared as numbers.
 *
 * Nulls sort last in both directions (they are absence, not a smallest value), and numeric
 * strings are still compared numerically so the high-precision columns the backend
 * deliberately stringifies — BIGINT past 2^53, DECIMAL, HUGEINT — do not regress to
 * lexicographic order.
 */
export function compareCells(a: unknown, b: unknown): number {
  const aNull = a === null || a === undefined || a === '';
  const bNull = b === null || b === undefined || b === '';
  if (aNull || bNull) return aNull && bNull ? 0 : aNull ? 1 : -1;

  if (typeof a === 'number' && typeof b === 'number') return a - b;
  if (typeof a === 'boolean' && typeof b === 'boolean') return Number(a) - Number(b);

  // Values the backend stringifies to avoid precision loss still deserve numeric order.
  const na = typeof a === 'number' ? a : Number(a);
  const nb = typeof b === 'number' ? b : Number(b);
  if (!Number.isNaN(na) && !Number.isNaN(nb) && na !== nb) return na < nb ? -1 : 1;

  const sa = String(a);
  const sb = String(b);
  return sa < sb ? -1 : sa > sb ? 1 : 0;
}

