/** Local-name of an IRI, for compact display — everything after the last `/` or `#`. */
export const localName = (iri: string) =>
  iri.substring(Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#')) + 1);

/**
 * The IRI fragment the server mints from a label — a twin of `Names.iriFragment` (aran-core),
 * pinned to its regex by iri.test.ts. For DISPLAY only: showing the name an integrated resource
 * will really get (a relationship typed `has author` is queried as `-[has_author]->`). Never use it
 * to build an identifier the server is expected to resolve — the server mints its own.
 */
export const IRI_FRAGMENT_UNSAFE = /[^A-Za-z0-9._-]/g;

export const iriFragment = (label: string) => label.replace(IRI_FRAGMENT_UNSAFE, '_');
