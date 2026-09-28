import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { ListEmptyBody } from './ListEmptyBody'
import type { EmptyListClaim } from '@/lib/listLoads'

/**
 * The body the Datasets and Repositories lists render when they have no row to show (review of
 * UA2, MAJOR): the onboarding state — "No … yet" and its Register button — belongs to a complete,
 * empty load and to nothing else. It used to be the `else` of a loading/error ternary, so a
 * partial load that returned no rows offered to register the very things that failed to load.
 */
const render = (claim: EmptyListClaim) =>
  renderToStaticMarkup(
    <ListEmptyBody
      claim={claim}
      noun="datasets"
      loadingLabel="Aggregating schemas..."
      errorTitle="Couldn't load your datasets"
      error={new Error('boom')}
      onRetry={() => {}}
      errorTestId="datasets-load-error"
      incompleteTestId="datasets-incomplete-empty"
    >
      <div data-testid="onboarding">No datasets found <button>Register Dataset</button></div>
    </ListEmptyBody>,
  )

describe('ListEmptyBody', () => {
  it('a partial load with no rows says so, and offers no Register', () => {
    const html = render('incomplete')
    expect(html).toContain('data-testid="datasets-incomplete-empty"')
    expect(html).toContain('Nothing loaded from the sources that answered')
    expect(html).toContain('does not mean you have no datasets')
    expect(html).not.toContain('data-testid="onboarding"')
    expect(html).not.toContain('Register')
  })

  it('only a complete, empty load shows the onboarding state', () => {
    expect(render('empty')).toContain('data-testid="onboarding"')
  })

  it.each<EmptyListClaim>(['loading', 'error', 'filtered', 'incomplete'])('%s never shows it', (claim) => {
    expect(render(claim)).not.toContain('data-testid="onboarding"')
  })

  it('a filter that hides every loaded row is not "none"', () => {
    expect(render('filtered')).toContain('No datasets match the current search or filter.')
  })

  it('a failed load is the error notice with its retry', () => {
    const html = render('error')
    expect(html).toContain('data-testid="datasets-load-error"')
    expect(html).toContain('Retry')
  })
})
