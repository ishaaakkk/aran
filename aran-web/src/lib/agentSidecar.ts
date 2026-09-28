import type { DataProduct } from '@/services/types'

/**
 * What the MCP sidecar (`aran-mcp/`) does with a data product, mirrored for the screens that tell
 * a user about it — the agents panel and the delete dialog. The sidecar is Python, so these are
 * copies; agentSidecar.test.ts reads them out of aran_mcp and fails when either side moves.
 */

/** aran_mcp/_products.py `READABLE_STATUSES` — the statuses the sidecar offers an agent. */
export const AGENT_READABLE_STATUSES: ReadonlySet<DataProduct['status']> = new Set(['READY', 'STALE'])

/** aran_mcp/_server.py `MAX_LIMIT` — the most rows one `read_product` call returns. */
export const AGENT_MAX_ROWS = 500

/** aran_mcp/_server.py `DEFAULT_LIMIT` — the rows a `read_product` call returns unless it asks. */
export const AGENT_DEFAULT_ROWS = 20
