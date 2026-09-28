import { describe, expect, it, vi } from 'vitest'
import { licenceLimitMessage } from './errors'

vi.mock('@/services/keycloak', () => ({ ensureFreshToken: async () => 'token', tokenParsed: () => undefined }))

describe('licenceLimitMessage (J1-F6)', () => {
  it('turns the project-limit 402 into a sentence with its numbers and the way on', async () => {
    const { ApiError } = await import('@/services/api')
    const err = new ApiError(402, {
      title: 'License limit exceeded', detail: "license limit 'maxProjects' exceeded: 3 / 3", status: 402,
      limit: 'maxProjects', currentUsage: 3, maxAllowed: 3,
    }, "license limit 'maxProjects' exceeded: 3 / 3")

    expect(licenceLimitMessage(err)).toBe(
      'Your organization has 3 of the 3 projects its plan allows. An owner can raise the limit.')
  })

  it('says nothing about anything else', async () => {
    const { ApiError } = await import('@/services/api')
    expect(licenceLimitMessage(new ApiError(409, null, 'taken'))).toBeNull()
    expect(licenceLimitMessage(new Error('x'))).toBeNull()
  })
})
