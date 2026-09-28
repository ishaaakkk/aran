import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { probeBackendHealth } from './backendHealth'

/**
 * The standalone OFFLINE indicator: the top bar and the offline screen read `/actuator/health`
 * from the SPA's origin, which the API port answers 404 — so a healthy stack showed OFFLINE.
 */

const fetchMock = vi.fn()

const answer = (status: number, body: unknown) =>
  ({ ok: status >= 200 && status < 300, status, json: async () => body }) as Response

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  vi.spyOn(console, 'warn').mockImplementation(() => {})
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('probeBackendHealth', () => {
  it('asks the same-origin /api/health, never the management-port-only /actuator', async () => {
    fetchMock.mockResolvedValue(answer(200, { status: 'UP', components: { db: { status: 'UP' } } }))

    await probeBackendHealth()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/health')
  })

  it('reports a healthy backend and database as operational', async () => {
    fetchMock.mockResolvedValue(answer(200, { status: 'UP', components: { db: { status: 'UP' } } }))

    expect(await probeBackendHealth()).toEqual({ api: 'operational', db: 'operational' })
  })

  it('reports a down database as an outage even while the process is ready', async () => {
    fetchMock.mockResolvedValue(answer(200, { status: 'UP', components: { db: { status: 'DOWN' } } }))

    expect(await probeBackendHealth()).toEqual({ api: 'operational', db: 'outage' })
  })

  it('reports the 404 the API port gives an unknown path as an outage', async () => {
    fetchMock.mockResolvedValue(answer(404, { status: 404, error: 'Not Found' }))

    expect(await probeBackendHealth()).toEqual({ api: 'outage', db: 'outage' })
  })

  it('reports an unreachable backend as an outage instead of throwing', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    expect(await probeBackendHealth()).toEqual({ api: 'outage', db: 'outage' })
  })

  it('hands the caller\'s abort signal to fetch, so the offline screen\'s timeout still bounds it', async () => {
    fetchMock.mockResolvedValue(answer(200, { status: 'UP', components: { db: { status: 'UP' } } }))
    const controller = new AbortController()

    await probeBackendHealth(controller.signal)

    expect(fetchMock.mock.calls[0][1]).toEqual({ signal: controller.signal })
  })
})
