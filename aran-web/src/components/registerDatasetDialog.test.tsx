import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { TableImportUnchecked } from './RegisterDatasetDialog'

describe('RegisterDatasetDialog — a failed capability read is said, not hidden (J2-F7)', () => {
  // A 5xx on GET /api/relational-engines used to leave a Postgres user with "Upload file" alone
  // and no hint that table import exists — the capability's absence and its unknown looked alike.
  it('names the failure, keeps upload on offer, and offers a retry', () => {
    const html = renderToStaticMarkup(<TableImportUnchecked message="HTTP 503" onRetry={() => {}} />)
    expect(html).toContain('data-testid="dataset-import-unchecked"')
    expect(html).toContain('role="status"')
    expect(html).toContain('Could not check whether this engine supports table import (HTTP 503)')
    expect(html).toContain('only file')
    expect(html).toMatch(/<button type="button"[^>]*>Retry<\/button>/)
  })
})
