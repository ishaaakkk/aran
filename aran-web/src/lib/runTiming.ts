import type { PlanningReport, QueryRunResult } from '@/services/types'

/** `elapsedMillis` off a planning report that may arrive as an object or a JSON string. */
function planningMillis(planning: PlanningReport | string | null | undefined): number | null {
  if (!planning) return null
  if (typeof planning !== 'string') return Number.isFinite(planning.elapsedMillis) ? planning.elapsedMillis : null
  try {
    const parsed = JSON.parse(planning) as PlanningReport
    return Number.isFinite(parsed.elapsedMillis) ? parsed.elapsedMillis : null
  } catch {
    return null
  }
}

/**
 * The result meta line's timing (UI audit J5-F13). `elapsedMs` is execution only, so a Run that
 * spent 20 s planning read "35 ms" — the one number an admin would use to find the slow part
 * pointed away from it. With the planner's own account present, both phases are named.
 */
export function runTiming(result: Pick<QueryRunResult, 'elapsedMs' | 'planning'>): string {
  const planned = planningMillis(result.planning)
  const ran = `${result.elapsedMs.toLocaleString('en-US')} ms`
  return planned === null ? ran : `planned ${planned.toLocaleString('en-US')} ms · ran ${ran}`
}
