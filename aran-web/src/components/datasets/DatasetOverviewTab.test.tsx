import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { DatasetOverviewTab, type SourceSchemaState } from './DatasetOverviewTab'
import type { AggregatedDataset } from './types'

const DATASET: AggregatedDataset = {
  id: 'ds-1', repositoryId: 'repo-1', name: 'acm', description: '', snapshotId: 'snap-1',
  bootstrapStatus: 'READY',
  idx: '01', projectName: 'P', projectColor: '#000', repoName: 'files', repoType: 'FILE_SYSTEM', projectId: 'p-1',
}

function render(schema: SourceSchemaState, dataset: AggregatedDataset = DATASET): string {
  return renderToStaticMarkup(<DatasetOverviewTab dataset={dataset} schema={schema} onRetrySchema={() => {}} />)
}

describe('DatasetOverviewTab source schema (J2-F13)', () => {
  it('lists the bootstrapped schema: class, property and recorded type', () => {
    const html = render({
      datasetId: 'ds-1', status: 'ready', columns: [
        { className: 'acm', property: 'title', datatype: 'xsd:string', iri: 'x:title' },
        { className: 'acm', property: 'year', datatype: 'xsd:long', iri: 'x:year' },
      ],
    })
    expect(html).toContain('data-testid="dataset-source-schema"')
    expect(html).toContain('title')
    expect(html).toContain('xsd:long')
    // The instruction that pointed at a tab that never existed, for a field never sent.
    expect(html).not.toContain('Column Profiling')
  })

  it('says why there is no schema instead of implying profiling would make one', () => {
    const pending = render({ datasetId: 'ds-1', status: 'ready', columns: [] }, { ...DATASET, bootstrapStatus: 'PENDING' })
    expect(pending).toContain('Bootstrapping')
    const failed = render({ datasetId: 'ds-1', status: 'ready', columns: [] }, { ...DATASET, bootstrapStatus: 'FAILED' })
    expect(failed).toContain('bootstrap failed')
  })

  it('renders a failed load as a failure with a retry, never as an empty schema', () => {
    const html = render({ datasetId: 'ds-1', status: 'error', message: 'Network down' })
    expect(html).toContain('Network down')
    expect(html).toContain('Retry')
    expect(html).not.toContain('No source schema')
  })

  it('ignores an answer that belongs to another dataset', () => {
    const html = render({
      datasetId: 'ds-OTHER', status: 'ready',
      columns: [{ className: 'x', property: 'leaked', datatype: null, iri: 'x:leaked' }],
    })
    expect(html).not.toContain('leaked')
  })
})

describe('DatasetOverviewTab access (J2-F3)', () => {
  it('says a live dataset is read live, and what that means for its queries', () => {
    const html = render({ datasetId: 'ds-1', status: 'ready', columns: [] },
      { ...DATASET, snapshotId: undefined, accessMode: 'LIVE' })
    expect(html).toContain('data-testid="dataset-access"')
    expect(html).toContain('Live')
    expect(html).toContain('Every query reads the source directly')
    expect(html).toContain('none — read live')
  })
})
