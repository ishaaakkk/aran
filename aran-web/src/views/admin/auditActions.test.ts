/// <reference types="node" />
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { RECORDED_ACTIONS, actionLabel } from './auditActions'

/**
 * The Activity header's list of recorded actions is a claim about the backend (audit J7-F1). A
 * claim that can be checked is a test, not a comment: this reads `AuditService.java` — the one
 * place action identifiers are written — and fails when the header would promise a line the server
 * never writes, or stay silent about one it does.
 */
const AUDIT_SERVICE = fileURLToPath(new URL(
  '../../../../aran-core/src/main/java/edu/upc/dtak/aran/core/service/AuditService.java',
  import.meta.url,
))

function actionsWrittenByTheServer(): string[] {
  const source = readFileSync(AUDIT_SERVICE, 'utf8')
  const ids = source.match(/"[a-z]+(?:\.[a-z_]+)+"/g) ?? []
  return [...new Set(ids.map((s) => s.slice(1, -1)))].sort()
}

describe('RECORDED_ACTIONS', () => {
  it('names exactly the actions AuditService writes', () => {
    expect(Object.keys(RECORDED_ACTIONS).sort()).toEqual(actionsWrittenByTheServer())
  })

  it('covers the three J7-F1 asked about: promote, role change and membership changes', () => {
    for (const action of ['schema.promoted', 'tenant.member.role_changed',
      'tenant.member.added', 'tenant.member.removed', 'project.member.removed']) {
      expect(RECORDED_ACTIONS).toHaveProperty([action])
    }
  })

  it('labels an unknown action with its identifier rather than inventing a meaning', () => {
    expect(actionLabel('dataset.deleted')).toBe('dataset.deleted')
    expect(actionLabel('schema.promoted')).toBe('schema promoted')
  })
})
