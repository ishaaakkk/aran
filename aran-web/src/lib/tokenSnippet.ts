import type { QueryCatalog } from '@/services/types'

/**
 * The "Use it from Python" example on a freshly minted token (J6-F18 / J7-F21). It used to query
 * the demo class `Paper`, so a user's first call with a new token failed on a class their tenant
 * does not have. The example now names the ACTIVE catalog's first class and one of its columns,
 * and says so plainly when nothing is promoted yet instead of inventing one.
 */
export function exampleOqlFrom(catalog: QueryCatalog | null): string | null {
  if (!catalog || catalog.empty) return null
  for (const cls of catalog.classes) {
    const column = cls.sources.flatMap((s) => s.columns).find((c) => c.globalName)
    if (column) return `SELECT ${cls.name}.${column.globalName} FROM ${cls.name}`
  }
  return null
}

const pyString = (s: string) => `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`

export function pythonExportSnippet(origin: string, secret: string, oql: string | null): string {
  const query = oql === null
    ? `# Nothing is promoted in this tenant yet — promote a schema, then name one of its classes:\n    json={"oql": "SELECT <Class>.<property> FROM <Class>", "format": "parquet"},`
    : `    json={"oql": ${pyString(oql)}, "format": "parquet"},`
  return `import io, requests, pandas as pd

r = requests.post(
    ${pyString(`${origin}/api/query/export`)},
    headers={"Authorization": "Bearer ${secret}"},
${oql === null ? '    ' : ''}${query}
)
r.raise_for_status()
df = pd.read_parquet(io.BytesIO(r.content))`
}
