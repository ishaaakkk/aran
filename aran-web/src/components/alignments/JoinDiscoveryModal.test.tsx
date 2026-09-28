import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { JoinDiscoveryModal } from './JoinDiscoveryModal'
import { parseDiscoveryResult, type JoinDiscoveryRun } from '@/lib/joinDiscovery'

/**
 * The discovery dialog's settled states (audit A10). The defect this pins: with freyja down the
 * dialog said "No join candidates found above the threshold — try dropping it" at 0.00, and an
 * engineer concluded two sources that obviously join did not (J3-F5). Each state now renders
 * one `align-discovery-outcome` with its own `data-state` — also the e2e page object's contract.
 */

function run(data: Record<string, string>, threshold = 0): JoinDiscoveryRun {
  return parseDiscoveryResult(data, { runId: 7, threshold, method: 'exact' })
}

const CANDIDATE = {
  iriA: 'http://x/source/1/acm/author_ref',
  iriB: 'http://x/source/2/researchers/rid',
  label: 'join_author_ref_rid',
  score: 0.3912,
  columnA: 'author_ref',
  columnB: 'rid',
  evidence: {
    valueOverlap: 0.62, cardinalityRatio: 0.9,
    distinctA: 180, distinctB: 200, uniquenessA: 0.4, uniquenessB: 1,
    nullFractionA: 0, nullFractionB: 0, typeA: 'VARCHAR', typeB: 'VARCHAR',
  },
}

function render(result: JoinDiscoveryRun | null, extra: Partial<Parameters<typeof JoinDiscoveryModal>[0]> = {}) {
  return renderToStaticMarkup(
    <JoinDiscoveryModal
      show
      onClose={() => {}}
      threshold={result?.threshold ?? 0}
      setThreshold={() => {}}
      method="exact"
      setMethod={() => {}}
      strictness={0.25}
      setStrictness={() => {}}
      discovering={false}
      discoveryError={null}
      result={result}
      settledRunId={result?.runId ?? null}
      selectedCandidates={{}}
      setSelectedCandidates={() => {}}
      onRunDiscovery={() => {}}
      onAcceptCandidates={() => {}}
      {...extra}
    />,
  )
}

describe('JoinDiscoveryModal — a failure is never an empty result (J3-F5)', () => {
  it('renders a degraded run as an alert with the reason and Retry, not as "no candidates"', () => {
    const html = render(run({
      candidates: '[]', pairsScored: '0', profiledColumnsA: '2', profiledColumnsB: '3',
      degraded: 'true', reason: '2 of 2 columns of acm could not be scored — the scoring service (freyja) could not be reached (ConnectException)',
      failedColumns: '["venue_code","title"]', datasetNameA: 'acm', datasetNameB: 'researchers',
    }))
    expect(html).toContain('data-state="degraded"')
    expect(html).toContain('data-run="7"')
    expect(html).toContain('role="alert"')
    expect(html).toContain('could not be reached')
    expect(html).toContain('align-discovery-retry')
    expect(html).toContain('Admin › System')
    expect(html).not.toMatch(/shares values|no join candidates|dropping the similarity threshold/i)
  })

  it('keeps the candidates of a partial run, under a warning that the list is incomplete', () => {
    const html = render(run({
      candidates: JSON.stringify([CANDIDATE]), degraded: 'true', reason: '1 of 2 columns of acm could not be scored — boom',
      datasetNameA: 'acm', datasetNameB: 'researchers',
    }))
    expect(html).toContain('data-state="partial"')
    expect(html).toContain('Incomplete')
    expect(html).toContain('align-candidate-author_ref-rid')
  })

  it('says which dataset has no measured profile instead of reporting "no match"', () => {
    const html = render(run({
      candidates: '[]', pairsScored: '0', profiledColumnsA: '3', profiledColumnsB: '0',
      datasetNameA: 'acm', datasetNameB: 'researchers',
    }))
    expect(html).toContain('data-state="no-profile"')
    expect(html).toContain('researchers has no measured column profile')
  })

  it('renders a failed job as an error with Retry', () => {
    const html = render(null, { discoveryError: 'the join-discovery job was failed', settledRunId: 4 })
    expect(html).toContain('data-state="error"')
    expect(html).toContain('data-run="4"')
    expect(html).toContain('align-discovery-retry')
  })
})

describe('JoinDiscoveryModal — a clean empty run says what it compared (J4-F21)', () => {
  it('at 0.00 says no pair shares values and does not advise lowering the threshold', () => {
    const html = render(run({
      candidates: '[]', pairsScored: '6', profiledColumnsA: '2', profiledColumnsB: '3',
      datasetNameA: 'acm', datasetNameB: 'semantic',
    }))
    expect(html).toContain('data-state="none"')
    expect(html).toContain('data-pairs-scored="6"')
    expect(html).toContain('No column pair of acm × semantic shares values (6 pairs compared).')
    expect(html).not.toMatch(/lower the minimum join score/i)
  })

  it('above 0 names the threshold and only then suggests lowering it', () => {
    const html = render(run({
      candidates: '[]', pairsScored: '6', profiledColumnsA: '2', profiledColumnsB: '3',
      datasetNameA: 'acm', datasetNameB: 'semantic',
    }, 0.3))
    expect(html).toContain('none scored 0.30 or more')
    expect(html).toMatch(/lower the minimum join score/i)
  })

  it('never calls a run whose pairs were all unsampled "no pair shares values" — it says nothing was measured and how to fix it', () => {
    const html = render(run({
      candidates: '[]', pairsScored: '12', unsampledPairs: '12', profiledColumnsA: '3', profiledColumnsB: '4',
      datasetNameA: 'acm', datasetNameB: 'researchers',
    }))
    expect(html).toContain('data-state="unmeasured"')
    expect(html).toContain('data-testid="align-discovery-unmeasured"')
    expect(html).toContain('Not measured')
    expect(html).toContain('Re-run profiling on acm and researchers')
    expect(html).not.toMatch(/shares values/)
  })

  it('counts only the measured pairs as compared, and says how many could not be', () => {
    const html = render(run({
      candidates: '[]', pairsScored: '6', unsampledPairs: '2', profiledColumnsA: '2', profiledColumnsB: '3',
      datasetNameA: 'acm', datasetNameB: 'semantic',
    }))
    expect(html).toContain('data-state="none"')
    expect(html).toContain('(4 pairs compared)')
    expect(html).toContain('2 of 6 pairs could not be compared')
  })

  it('no longer names the sidecar in its progress line', () => {
    const html = render(null, { discovering: true })
    expect(html).not.toMatch(/FREYJA/)
    expect(html).not.toContain('align-discovery-outcome')
  })
})

describe('JoinDiscoveryModal — candidates carry their evidence and their fate (J3-F6, J3-F13, J3-F14)', () => {
  const result = run({
    candidates: JSON.stringify([CANDIDATE]), pairsScored: '6', profiledColumnsA: '2', profiledColumnsB: '3',
    datasetNameA: 'acm', datasetNameB: 'researchers',
  })

  it('shows dataset.column, the estimated overlap and the n..1 shape on the row', () => {
    const html = render(result)
    expect(html).toContain('data-state="candidates"')
    expect(html).toContain('acm.author_ref')
    expect(html).toContain('≈ 62 % shared')
    expect(html).toContain('n..1')
  })

  it('labels the score a join score, as a number with its band — not a percentage "confidence"', () => {
    const html = render(result)
    expect(html).toContain('Join score')
    expect(html).not.toContain('Confidence')
    expect(html).toContain('0.39')
    expect(html).not.toContain('39%')
    expect(html).toMatch(/>strong<|>good<|>weak</)
  })

  it('reads a foreign key by containment — the share of the smaller column found in the other — not only the Jaccard', () => {
    const fk = { ...CANDIDATE, evidence: { ...CANDIDATE.evidence, valueOverlap: 0.3, containment: 1, containmentOf: 'A' } }
    const html = render(run({ candidates: JSON.stringify([fk]), datasetNameA: 'acm', datasetNameB: 'researchers' }))
    expect(html).toContain('≈ 100 % of author_ref in rid')
    expect(html).not.toContain('≈ 30 % shared')
  })

  it('says, beside the candidates, how many pairs could not be compared', () => {
    const html = render(run({
      candidates: JSON.stringify([CANDIDATE]), pairsScored: '6', unsampledPairs: '1',
      datasetNameA: 'acm', datasetNameB: 'researchers',
    }))
    expect(html).toContain('of 5 pairs compared')
    expect(html).toContain('data-testid="align-discovery-unsampled-note"')
    expect(html).toContain('1 of 6 pairs could not be compared')
  })

  it('says an accepted join enters as a HYPOTHESIS', () => {
    expect(render(result)).toContain('HYPOTHESIS')
  })

  it('warns when both columns are unique numeric sequences', () => {
    const surrogate = { ...CANDIDATE, evidence: { ...CANDIDATE.evidence, uniquenessA: 1, typeA: 'BIGINT', typeB: 'BIGINT' } }
    const html = render(run({ candidates: JSON.stringify([surrogate]), datasetNameA: 'acm', datasetNameB: 'researchers' }))
    expect(html).toContain('align-surrogate-warning-author_ref-rid')
  })

  it('offers an editable relationship name and direction once a candidate is ticked, prefilled with the minted default', () => {
    const unticked = render(result)
    expect(unticked).not.toContain('align-relationship-author_ref-rid')
    const html = render(result, { selectedCandidates: { 0: true } })
    expect(html).toContain('data-testid="align-relationship-author_ref-rid"')
    expect(html).toContain('value="join_author_ref_rid_join"')
    expect(html).toContain('data-testid="align-direction-author_ref-rid"')
    expect(html).toContain('-[join_author_ref_rid_join]-&gt;')
  })

  it('keeps the e2e contract: candidate rows, their checkboxes and the accept button', () => {
    const html = render(result)
    expect(html).toContain('data-testid="align-candidate-author_ref-rid"')
    expect(html).toContain('data-left="author_ref"')
    expect(html).toContain('data-testid="align-candidate-check-author_ref-rid"')
    expect(html).toContain('data-testid="align-candidates-accept"')
    expect(html).toContain('data-testid="align-discovery-close"')
    expect(html).toContain('data-testid="align-discovery-run"')
  })

  it('names the threshold slider and each checkbox for assistive technology', () => {
    const html = render(result)
    expect(html).toContain('for="discovery-threshold"')
    expect(html).toContain('aria-label="Accept author_ref ↔ rid"')
    expect(html).toContain('role="dialog"')
  })
})

describe('JoinDiscoveryModal accessibility and plain language (J3-F18, J3-F19 — carried from the A1 truth pass)', () => {
  const result = run({
    candidates: JSON.stringify([CANDIDATE]), pairsScored: '6', profiledColumnsA: '2', profiledColumnsB: '3',
    datasetNameA: 'acm', datasetNameB: 'researchers',
  })

  it('is a real dialog with a name, on the shared Dialog primitive, keeping the e2e panel testid', () => {
    const html = render(result)
    expect(html).toContain('role="dialog"')
    expect(html).toContain('aria-modal="true"')
    expect(html).toContain('aria-label="Join discovery"')
    expect(html).toContain('data-testid="align-discovery-panel"')
  })

  it('names the close button and both sliders', () => {
    const html = render(result)
    expect(html).toContain('aria-label="Close join discovery"')
    expect(html).toContain('aria-label="Similarity threshold"')
    expect(html).toContain('aria-label="Strictness"')
  })

  it('names the methods without formula jargon, and keeps the formula behind "How is this scored?"', () => {
    const html = render(result, { method: 'exact' })
    expect(html).not.toContain('MJ &amp; K Formula')
    expect(html).not.toContain('truncnorm')
    expect(html).toContain('How is this scored?')
  })
})

describe('JoinDiscoveryModal marks a join that already exists (J3-F16 — carried from A2/A9)', () => {
  const second = { ...CANDIDATE, iriA: 'http://x/source/1/acm/venue', iriB: 'http://x/source/2/researchers/affiliation', label: 'join_venue_affiliation', columnA: 'venue', columnB: 'affiliation', score: 0.05 }
  const result = run({
    candidates: JSON.stringify([CANDIDATE, second]), pairsScored: '6', profiledColumnsA: '2', profiledColumnsB: '3',
    datasetNameA: 'acm', datasetNameB: 'researchers',
  })

  it('disables the row and names the join it duplicates', () => {
    const html = render(result, { unavailable: { 0: 'already aligned as hasAuthor' } })
    expect(html).toContain('already aligned as hasAuthor')
    expect(html).toMatch(/data-testid="align-candidate-check-author_ref-rid"[^>]*disabled/)
    expect(html).not.toMatch(/data-testid="align-candidate-check-venue-affiliation"[^>]*disabled/)
  })
})
