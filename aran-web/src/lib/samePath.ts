/**
 * A path on this origin, or null — the one guard every "come back to where you were" hop uses
 * (sign-in, sign-out-and-in, the offline page's Continue). A value that starts with `//` or `/\`
 * is a protocol-relative URL to another host in every browser, so it is refused, as is anything
 * that is not a path at all; a second, drifted copy of this check is how an open redirect starts.
 */
export function sameOriginPath(path: string | null | undefined): string | null {
  if (!path || !path.startsWith('/')) return null
  if (path.startsWith('//') || path.startsWith('/\\')) return null
  return path
}
