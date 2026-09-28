import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MappingPanel } from './MappingPanel'
import type { ModelMapping } from '@/services/types'

/**
 * The accepted-mappings table's join rows say how the join is queried (J3-F14). The name shown must
 * be the one the server mints — `Names.iriFragment` of the relationship — or a user copies a
 * `-[has author]->` that OQL cannot resolve.
 */

function join(label: string): ModelMapping {
  return {
    id: 'm-1', kind: 'join',
    sourceIri: 'http://x/source/1/acm/author_ref', targetIri: 'http://x/source/2/researchers/rid',
    label, status: 'ACCEPTED', evidence: null, author: null, comment: null, createdAt: '2026-09-01T00:00:00Z',
  } as ModelMapping
}

const noop = () => {}

function render(m: ModelMapping) {
  return renderToStaticMarkup(
    <MappingPanel acceptedMappings={[m]} suggestedMappings={[]} onDelete={noop} onAccept={noop} onReject={noop} />,
  )
}

describe('MappingPanel — a join row names the relationship OQL uses', () => {
  it('shows the minted name of a relationship typed with a space', () => {
    const html = render(join('join_author_ref_rid|has author|forward'))
    expect(html).toContain('-[has_author]-&gt;')
    expect(html).not.toContain('-[has author]-&gt;')
  })

  it('shows <label>_join for a join accepted with the default relationship', () => {
    expect(render(join('join_author_ref_rid'))).toContain('-[join_author_ref_rid_join]-&gt;')
  })
})
