import type { QueryRunResult } from '@/services/types'

/**
 * CSV (RFC 4180) of the rows a result carries — one page, never the query's whole result. The one
 * writer for the workbench's "this page" and the product preview's, so the two cannot disagree on
 * quoting. Cells are stringified whatever their wire type: the engine sends numbers and booleans as
 * JSON (DuckDbExecutor.jsonSafe), whatever `QueryRunResult.rows` claims.
 */
export function resultToCsv(r: QueryRunResult): string {
  const esc = (cell: unknown): string => {
    if (cell === null || cell === undefined) return ''
    const v = typeof cell === 'string' ? cell : typeof cell === 'object' ? JSON.stringify(cell) : String(cell)
    return /[",\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v
  }
  const lines = [r.headerNames.map(esc).join(',')]
  for (const row of r.rows) lines.push((row as unknown[]).map(esc).join(','))
  return lines.join('\n')
}

/** Hands the page to the browser as a file download. */
export function downloadResultCsv(r: QueryRunResult, filename: string): void {
  const blob = new Blob([resultToCsv(r)], { type: 'text/csv' })
  const url = URL.createObjectURL(blob)
  try {
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
  } finally {
    URL.revokeObjectURL(url)
  }
}
