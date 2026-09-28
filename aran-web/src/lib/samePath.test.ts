import { describe, expect, it } from 'vitest'
import { sameOriginPath } from './samePath'

describe('sameOriginPath — one guard for every return hop', () => {
  it('keeps a same-origin path with its query and hash', () => {
    expect(sameOriginPath('/oql?q=SELECT%20A.x%20FROM%20A#r')).toBe('/oql?q=SELECT%20A.x%20FROM%20A#r')
  })

  it.each(['//evil.example.com/x', '/\\evil.example.com', 'https://evil.example.com', 'oql', '', null, undefined])(
    'refuses %s',
    (value) => {
      expect(sameOriginPath(value)).toBeNull()
    },
  )
})
