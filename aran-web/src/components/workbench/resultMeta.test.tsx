import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { ResultPanel } from './WorkbenchPanels'
import type { QueryRunResult } from '../../services/types'

/**
 * The result meta line names planning and execution apart (UI audit J5-F13): a Run that planned
 * for 20 s and executed in 35 ms used to read "35 ms".
 */
describe('the result meta line', () => {
  const base: QueryRunResult = {
    headers: ['title'], headerNames: ['title'], rows: [['x']] as QueryRunResult['rows'],
    rowCount: 1, truncated: false, elapsedMs: 35,
  }

  it('shows the planning time the run reported beside the execution time', () => {
    const html = renderToStaticMarkup(<ResultPanel result={{
      ...base,
      planning: { productions: 9, budget: 5000, elapsedMillis: 19_812, degradation: 'NONE', tripped: null, productionsByRule: {} },
    }} />)
    expect(html).toContain('planned 19,812 ms · ran 35 ms')
  })

  it('shows the execution time alone for a raw SQL run, which has no planner report', () => {
    const html = renderToStaticMarkup(<ResultPanel result={base} />)
    expect(html).toContain('35 ms')
    expect(html).not.toContain('planned')
  })
})
