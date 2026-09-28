/**
 * A caught value is `unknown` — it is whatever was thrown, which need not be an
 * Error. This is the one place that decides what to show the user for it.
 */

/**
 * The fields of `ApiError` this module reads, structurally — so it stays free of the api
 * module (and keycloak behind it), which every view already imports it next to.
 */
interface ReferencedError {
  kind?: string
  correlationId?: string | null
}

/**
 * The reference worth quoting for this failure, or null.
 *
 * Only for the failures a user cannot explain themselves — a server fault and a request the
 * page stopped waiting for (UI audit J8-F4). A domain refusal ("No active global schema"), a
 * conflict or a cancel already says what happened; a reference there is noise.
 */
export function errorReference(err: unknown): string | null {
  if (!(err instanceof Error)) return null
  const { kind, correlationId } = err as Error & ReferencedError
  if (!correlationId) return null
  return kind === 'server' || kind === 'timeout' ? correlationId : null
}

/**
 * A lazily loaded route chunk that no longer exists — a deploy replaced it while this tab still
 * ran the old bundle. "Try again" re-requests the same missing file forever; only a reload
 * fetches the new bundle (UI audit J8-F6).
 */
export function isChunkLoadError(error: Error): boolean {
  return error.name === 'ChunkLoadError'
    || /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed/i
      .test(error.message);
}

const LIMIT_NOUNS: Record<string, string> = {
  maxProjects: 'projects',
  maxUsers: 'members',
}

/**
 * A 402 licence-limit refusal as a sentence a user can act on (UI audit J1-F6), from the
 * structured fields the server sends for exactly this purpose — its own detail is
 * "license limit 'maxProjects' exceeded: 3 / 3". Null for anything else.
 */
export function licenceLimitMessage(err: unknown): string | null {
  const problem = (err as { status?: number; problem?: { limit?: string; currentUsage?: number; maxAllowed?: number } | null } | null)
  if (problem?.status !== 402 || !problem.problem) return null
  const { limit, currentUsage, maxAllowed } = problem.problem
  const noun = limit ? LIMIT_NOUNS[limit] : undefined
  if (!noun || currentUsage === undefined || maxAllowed === undefined) return null
  return `Your organization has ${currentUsage} of the ${maxAllowed} ${noun} its plan allows. An owner can raise the limit.`
}

/** The failure's own sentence, with no reference — for surfaces that render the reference apart. */
export function errorDetail(err: unknown, fallback = 'An unexpected error occurred.'): string {
  if (err instanceof Error && err.message) return err.message;
  if (typeof err === 'string' && err) return err;
  return fallback;
}

/**
 * What to show for a failure in one line: its sentence, and the reference to quote when there
 * is one — so every toast and inline error that already goes through here carries it.
 */
export function errorMessage(err: unknown, fallback = 'An unexpected error occurred.'): string {
  const detail = errorDetail(err, fallback)
  const reference = errorReference(err)
  return reference ? `${detail} Reference: ${reference}` : detail
}
