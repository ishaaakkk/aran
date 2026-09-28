/**
 * What the audit trail records, in words.
 *
 * Audit J7-F1: Activity was described as the governance log while recording five actions, so an
 * owner asking "who promoted v3?" or "who reset Joan's password?" read the absence of a line as
 * "nobody did". The Activity header now lists exactly what is recorded and says what is not.
 *
 * This list must equal the action identifiers `AuditService.java` writes — `auditActions.test.ts`
 * reads that file and fails when the two drift, the way `tourRegistry.test.ts` pins the tours to
 * the routes.
 */
export const RECORDED_ACTIONS: Record<string, string> = {
  'tenant.created': 'organization created',
  'tenant.member.added': 'member added',
  'tenant.member.reinstated': 'member reinstated',
  'tenant.member.removed': 'member removed',
  'tenant.member.role_changed': 'role changed',
  'tenant.member.password_reset': 'password reset',
  'token.created': 'API token created',
  'token.revoked': 'API token revoked',
  'project.created': 'project created',
  'project.member.added': 'project member added',
  'project.member.removed': 'project member removed',
  'schema.promoted': 'schema promoted',
  'schema.rolled_back': 'schema rolled back',
}

/**
 * Examples of what leaves no line yet — introduced as "everything else, including …", never as a
 * complete list, because it is not one (anything outside RECORDED_ACTIONS is unrecorded). Stated
 * rather than implied: a log that silently omits deletes reads as "nothing was deleted", and a
 * list of omissions that reads as complete makes "who created this product?" look answerable.
 */
export const NOT_YET_RECORDED = [
  'creating or deleting repositories, datasets, products and cubes',
  'accepting or rejecting alignments',
  'compiles',
  'dataset and product refreshes',
  'wrapper and constraint edits',
] as const

/** The label for an action, or the identifier itself for one this list does not know. */
export function actionLabel(action: string): string {
  return RECORDED_ACTIONS[action] ?? action
}
