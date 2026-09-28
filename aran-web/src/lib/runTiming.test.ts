import { describe, expect, it } from 'vitest'
import { runTiming } from './runTiming'
import type { PlanningReport } from '@/services/types'

const planning = (elapsedMillis: number): PlanningReport => ({
  productions: 120, budget: 5000, elapsedMillis, degradation: 'NONE', tripped: null, productionsByRule: {},
})

describe('runTiming (J5-F13)', () => {
  it('names planning and execution apart when the planner reported its time', () => {
    expect(runTiming({ elapsedMs: 35, planning: planning(19_812) })).toBe('planned 19,812 ms · ran 35 ms')
  })

  it('reads a planning report that arrived JSON-encoded, as rewrite-oql sends it', () => {
    expect(runTiming({ elapsedMs: 7, planning: JSON.stringify(planning(4)) as never })).toBe('planned 4 ms · ran 7 ms')
  })

  it('keeps the bare execution time when there is no planning report (raw SQL)', () => {
    expect(runTiming({ elapsedMs: 35 })).toBe('35 ms')
  })
})
