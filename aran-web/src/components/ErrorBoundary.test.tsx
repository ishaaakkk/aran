import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { ErrorBoundaryInner } from './ErrorBoundary'
import { ApiError } from '@/services/api'
import { isChunkLoadError } from '@/lib/errors'

vi.mock('@/services/keycloak', () => ({ ensureFreshToken: async () => 'token', tokenParsed: () => undefined }))

/**
 * The route error boundary (UI audit J8-F6). Exercised on the instance: static rendering does not
 * run error boundaries, and the three behaviours are each one method.
 */
const boundary = (resetKey: string, error: Error) => {
  const b = new ErrorBoundaryInner({ resetKey, children: 'page' })
  b.state = { error }
  return b
}

describe('ErrorBoundary', () => {
  it('clears a caught error when the route changes, so one crashed view does not poison the others', () => {
    const b = boundary('/oql', new Error('boom'))
    const setState = vi.fn()
    b.setState = setState

    b.componentDidUpdate({ resetKey: '/datasets', children: 'page' })

    expect(setState).toHaveBeenCalledWith({ error: null })
  })

  it('keeps the error while the route stays the same', () => {
    const b = boundary('/oql', new Error('boom'))
    const setState = vi.fn()
    b.setState = setState

    b.componentDidUpdate({ resetKey: '/oql', children: 'page' })

    expect(setState).not.toHaveBeenCalled()
  })

  it('a stale chunk after a redeploy offers a reload, not a "Try again" that repeats the failure', () => {
    const error = new TypeError('Failed to fetch dynamically imported module: /assets/OqlPlanView-abc.js')
    expect(isChunkLoadError(error)).toBe(true)

    const html = renderToStaticMarkup(<>{boundary('/oql', error).render()}</>)
    expect(html).toContain('Aran was updated')
    expect(html).toContain('Reload')
    expect(html).not.toContain('Try again')
  })

  it('a render error reads on the error token (the destructive token was 1.09:1) and quotes a reference when it has one', () => {
    const error = new ApiError(500, null, 'An internal server error occurred.', { correlationId: 'corr-render' })

    const html = renderToStaticMarkup(<>{boundary('/oql', error).render()}</>)
    expect(html).toContain('text-error')
    expect(html).not.toContain('text-destructive')
    expect(html).toContain('corr-render')
    expect(html).toContain('role="alert"')
  })
})
