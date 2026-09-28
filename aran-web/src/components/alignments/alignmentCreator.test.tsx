import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { AlignmentCreator } from './AlignmentCreator'
import type { SchemaNode } from './types'

const node = (type: string, id = type): SchemaNode => ({ id, iri: `http://x/${id}`, label: id, type })

function render(left: SchemaNode | null, right: SchemaNode | null) {
  return renderToStaticMarkup(
    <AlignmentCreator
      leftSelectedNode={left} rightSelectedNode={right}
      alignmentLabel="doi" setAlignmentLabel={() => {}}
      alignmentType="datatype" setAlignmentType={() => {}}
      relationshipName="" setRelationshipName={() => {}}
      relationshipDirection="forward" setRelationshipDirection={() => {}}
      leftDatasetId="a" rightDatasetId="b" leftGraphLoading={false} rightGraphLoading={false}
      suggesting={false} suggestionError={null} addMappingError={null}
      discoveryCandidates={[]} discoveryError={null} discovering={false}
      onAddAlignment={() => {}} onSuggest={() => {}} onOpenDiscovery={() => {}} onRunDiscovery={() => {}}
    />,
  )
}

const ADD_DISABLED = /<button[^>]*disabled=""[^>]*>(?:(?!<\/button>).)*Add Correspondence/s

describe('AlignmentCreator offers only the kinds a pair admits (J4-F6)', () => {
  it('refuses a class paired with a property: Add disabled, the reason said as an alert', () => {
    const html = render(node('class', 'acm'), node('datatype', 'doi'))
    expect(html).toContain('data-testid="align-kind-refused"')
    expect(html).toMatch(/class aligns only with a class/)
    expect(html).toMatch(ADD_DISABLED)
  })

  it('disables the class kind for two properties, and keeps Add enabled', () => {
    const html = render(node('datatype', 'doi'), node('datatype', 'doi2'))
    expect(html).toMatch(/<option value="class" disabled="">/)
    expect(html).not.toContain('align-kind-refused')
    expect(html).not.toMatch(ADD_DISABLED)
  })
})
