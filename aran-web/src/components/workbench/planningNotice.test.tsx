import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { ResultPanel } from './WorkbenchPanels'
import type { OqlRewriteResult } from '../../services/types'

/**
 * UI audit J8-F22: a cut-short plan told the analyst reading it to "Raise
 * ARAN_PLANNER_PRODUCTION_BUDGET" — a server environment variable only an operator can set.
 * The notice now says who can act, and keeps the variable behind "Admin details".
 */
const EXPLAIN = {
  sql: 'SELECT 1',
  logicalPlan: 'LogicalValues',
  optimizedPlan: 'LogicalValues',
  planning: {
    productions: 5000,
    budget: 5000,
    elapsedMillis: 1200,
    degradation: 'BEST_SO_FAR',
    tripped: 'PRODUCTION_BUDGET',
    productionsByRule: {},
  },
} as unknown as OqlRewriteResult

describe('the planning notice (J8-F22)', () => {
  it('addresses the person who can act, with the setting behind "Admin details"', () => {
    const html = renderToStaticMarkup(<ResultPanel activeTab="explain" explainResult={EXPLAIN} />)
    expect(html).toContain('data-testid="planning-notice"')
    expect(html).not.toContain('Raise ARAN_PLANNER_PRODUCTION_BUDGET')
    expect(html).toContain('An administrator can raise the planner')
    expect(html).toMatch(/<details[\s\S]*Admin details[\s\S]*ARAN_PLANNER_PRODUCTION_BUDGET[\s\S]*<\/details>/)
  })
})
