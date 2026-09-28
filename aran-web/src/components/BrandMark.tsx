/** Aran's mark: two ranges of the Val d'Aran. Colours are fixed so the icon matches the favicon in both themes. */
export function BrandMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="7" fill="#6D28D9" />
      <path d="M13 24.5 21 9.5l8 15Z" fill="#B9A2F8" stroke="#B9A2F8" strokeWidth="1.2" strokeLinejoin="round" />
      <path d="M3 24.5 11.5 11l8.5 13.5Z" fill="#fff" stroke="#fff" strokeWidth="1.2" strokeLinejoin="round" />
    </svg>
  );
}
