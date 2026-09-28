/**
 * A timestamp with its zone said (audit J4-F18): "25 Sept 2026, 12:00 CEST", never a bare local
 * time a reader in another office misreads as theirs. `locale`/`timeZone` default to the viewer's.
 */
export function formatZoned(iso: string, locale?: string, timeZone?: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString(locale, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZoneName: 'short',
    timeZone,
  });
}
