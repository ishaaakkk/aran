import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { AGENT_DEFAULT_ROWS, AGENT_MAX_ROWS, AGENT_READABLE_STATUSES } from './agentSidecar'

const sidecar = (file: string) => readFileSync(join(process.cwd(), '..', 'aran-mcp', 'aran_mcp', file), 'utf8')

const pyInt = (source: string, name: string) => {
  const m = new RegExp(`^${name} = (\\d+)$`, 'm').exec(source)
  return m ? Number(m[1]) : null
}

describe('the sidecar facts the UI states, against aran_mcp itself', () => {
  it('readable statuses match _products.py READABLE_STATUSES', () => {
    const declared = /READABLE_STATUSES = frozenset\(\{([^}]*)\}\)/.exec(sidecar('_products.py'))?.[1] ?? ''
    const statuses = [...declared.matchAll(/"([A-Z_]+)"/g)].map((m) => m[1]).sort()
    expect(statuses).toEqual([...AGENT_READABLE_STATUSES].sort())
  })

  it('the row cap and default match _server.py MAX_LIMIT / DEFAULT_LIMIT', () => {
    const server = sidecar('_server.py')
    expect(pyInt(server, 'MAX_LIMIT')).toBe(AGENT_MAX_ROWS)
    expect(pyInt(server, 'DEFAULT_LIMIT')).toBe(AGENT_DEFAULT_ROWS)
  })
})
