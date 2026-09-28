import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { MappingPanel } from './MappingPanel';
import { addedBy } from '@/lib/alignmentStanding';
import { JoinDiscoveryModal } from './JoinDiscoveryModal';
import { SourceEnrollmentPanel } from './SourceEnrollmentPanel';
import { CompilePanel } from './CompilePanel';
import type { ModelMapping } from '@/services/types';
import type { JoinCandidate } from './types';
import { parseDiscoveryResult } from '@/lib/joinDiscovery';

/**
 * The alignments panels' A7 promises, pinned on static markup (no jsdom here): each accepted row
 * says whether it is live (J4-F5) and how far a join can be trusted (J4-F17); row actions name
 * their object (J4-F3); discovery says what happened to each candidate (J3-F4); a restored draft
 * does not pretend to know the compile report it never saw (J4-F2).
 */
const mapping = (over: Partial<ModelMapping>): ModelMapping => ({
  id: 'm1', kind: 'class', sourceIri: 'http://ex/source/d1/Author', targetIri: 'http://ex/source/d2/Person',
  label: 'Author', status: 'ACCEPTED', evidence: null, author: null, comment: null, createdAt: '2026-09-25T00:00:00Z',
  ...over,
});

describe('MappingPanel', () => {
  const render = (m: ModelMapping[], extra: Partial<Parameters<typeof MappingPanel>[0]> = {}) => renderToStaticMarkup(
    <MappingPanel acceptedMappings={m} suggestedMappings={[]} onDelete={() => {}} onAccept={() => {}} onReject={() => {}}
      standing={{ activeVersion: { id: 'v3', versionNumber: 3 }, draft: null, baselineVersionId: 'v3' }}
      joinEdges={[]} {...extra} />,
  );

  it('says whether each accepted alignment is live or not yet compiled', () => {
    const html = render([
      mapping({ id: 'live', application: { state: 'APPLIED', versionId: 'v3', reason: null } }),
      mapping({ id: 'new', sourceIri: 'http://ex/source/d1/Paper', application: null }),
    ]);
    expect(html).toMatch(/data-testid="align-state-live"[^>]*data-state="live"/);
    expect(html).toMatch(/data-testid="align-state-new"[^>]*data-state="pending"/);
    expect(html).toContain('Compiled into the active schema v3.');
  });

  it('calls an alignment an incremental promote carried forward live, not "not live"', () => {
    // Compiled into v1; v2 (an incremental add) and then v3 were promoted on top of it.
    const html = render([mapping({ id: 'old', application: { state: 'APPLIED', versionId: 'v1', reason: null } })]);
    expect(html).toMatch(/data-testid="align-state-old"[^>]*data-state="live"/);
    expect(html).not.toContain('no longer active');
  });

  it('claims no trust tier while the evidence is loading, instead of flashing "hypothesis"', () => {
    const html = render([mapping({ id: 'j1', kind: 'join', label: 'k|hasAuthor|forward' })], { joinEdges: null });
    expect(html).not.toContain('data-testid="align-tier-j1"');
    expect(html).toContain('data-testid="align-tier-loading-j1"');
  });

  it('marks an accepted join a hypothesis unless the active catalog declares its key, and says why', () => {
    const html = render([
      mapping({ id: 'j1', kind: 'join', label: 'authorKey|hasAuthor|forward' }),
      mapping({ id: 'j2', kind: 'join', label: 'venueKey|inVenue|forward' }),
    ], {
      joinEdges: [{ propertyIri: 'x', propertyName: 'inVenue', leftClassIri: 'a', rightClassIri: 'b', leftColIri: 'a/x', rightColIri: 'b/y', tier: 'DECLARED' }],
    });
    expect(html).toMatch(/data-testid="align-tier-j1"[^>]*>hypothesis/);
    expect(html).toMatch(/data-testid="align-tier-j2"[^>]*>declared key/);
    expect(html).toContain('not a uniqueness guarantee');
  });

  it('says the tiers could not be read rather than showing none', () => {
    const html = render([mapping({ id: 'j1', kind: 'join', label: 'k|hasAuthor|forward' })], { joinEdges: null, joinEdgesError: 'timed out' });
    expect(html).toContain('Trust tiers could not be read (timed out)');
    expect(html).not.toContain('align-tier-j1');
  });

  it('says when each accepted row was added, with its zone, and whether the viewer added it', () => {
    // J4-F14: before deleting or un-enrolling, a reviewer can see who added a row and when.
    const html = render([
      mapping({ id: 'mine', author: 'u1', createdAt: '2026-09-20T08:00:00Z' }),
      mapping({ id: 'theirs', sourceIri: 'http://ex/source/d1/Paper', author: 'u2', createdAt: '2026-09-21T08:00:00Z' }),
    ], { currentUserId: 'u1' });
    expect(html).toMatch(/data-testid="align-added-mine"[^>]*><time dateTime="2026-09-20T08:00:00Z">[^<]*2026[^<]*<\/time><span[^>]*>by you</);
    expect(html).toMatch(/data-testid="align-added-theirs"[^>]*>.*by another member/);
  });

  it('names no author it does not know, and never guesses one', () => {
    expect(addedBy(null, 'u1')).toBeNull();
    expect(addedBy('u1', 'u1')).toBe('by you');
    expect(addedBy('u2', 'u1')).toBe('by another member');
    expect(addedBy('u2', null)).toBe('by another member');
  });

  it('names the object of every row action', () => {
    const html = render([mapping({})]);
    expect(html).toContain('aria-label="Delete alignment Author ↔ Person"');
  });

  it('never adds a testid the page object counts as an alignment row', () => {
    // AlignmentsPage counts `[data-testid^="align-mapping-"]` to prove an add persisted.
    const html = render([mapping({ application: { state: 'APPLIED', versionId: 'v3', reason: null } })]);
    expect(html.match(/data-testid="align-mapping-/g)?.length).toBe(1);
  });
});

describe('JoinDiscoveryModal', () => {
  const candidates: JoinCandidate[] = [
    { iriA: 'http://ex/a/author_ref', iriB: 'http://ex/b/rid', label: 'k1', score: 0.9, columnA: 'author_ref', columnB: 'rid' },
    { iriA: 'http://ex/a/id', iriB: 'http://ex/b/id', label: 'join_id_id', score: 0.5, columnA: 'id', columnB: 'id' },
    { iriA: 'http://ex/a/venue', iriB: 'http://ex/b/vid', label: 'k3', score: 0.4, columnA: 'venue', columnB: 'vid' },
  ];
  const run = parseDiscoveryResult(
    { candidates: JSON.stringify(candidates), pairsScored: '3', profiledColumnsA: '3', profiledColumnsB: '3', datasetNameA: 'a', datasetNameB: 'b' },
    { runId: 1, threshold: 0, method: 'exact' },
  );
  const render = (extra: Partial<Parameters<typeof JoinDiscoveryModal>[0]> = {}) => renderToStaticMarkup(
    <JoinDiscoveryModal show onClose={() => {}} threshold={0} setThreshold={() => {}} method="exact" setMethod={() => {}}
      strictness={0.25} setStrictness={() => {}} discovering={false} discoveryError={null} result={run} settledRunId={1}
      selectedCandidates={{ 0: true, 1: true }} setSelectedCandidates={() => {}} onRunDiscovery={() => {}}
      onAcceptCandidates={() => {}} {...extra} />,
  );

  it('is a real dialog the page object still finds by its testids', () => {
    const html = render();
    expect(html).toContain('role="dialog"');
    expect(html).toContain('data-testid="align-discovery-panel"');
    expect(html).toContain('data-testid="align-discovery-close"');
  });

  it('keeps each candidate\'s outcome on its row and offers to retry only the refused ones', () => {
    const html = render({ outcomes: { 0: { state: 'added' }, 1: { state: 'failed', message: 'label join_id_id already names another alignment' } } });
    expect(html).toMatch(/data-testid="align-candstatus-author_ref-rid"[^>]*><span[^>]*>added/);
    expect(html).toContain('not added: label join_id_id already names another alignment');
    expect(html).toContain('Retry the 1 not added');
  });

  it('lets a refused candidate be relabelled, and never offers to re-send a 4xx unchanged', () => {
    // Review UA5 / J4-F9: D2 refuses a second id↔id join on its label. Retrying the same request
    // could only be refused again, so the row's label is editable and Retry waits for a change.
    const refused = { state: 'failed' as const, message: "a join mapping labelled 'join_id_id' already exists",
      retryable: false, sentLabel: 'join_id_id' };
    const stuck = render({ selectedCandidates: { 1: true }, outcomes: { 1: refused } });
    expect(stuck).toMatch(/<input[^>]*data-testid="align-candlabel-id-id"[^>]*value="join_id_id"/);
    expect(stuck).toContain('aria-label="Label for id ↔ id"');
    expect(stuck).not.toContain('Retry the');
    expect(stuck).toMatch(/<button[^>]*data-testid="align-candidates-accept"[^>]*\sdisabled=""/);
    expect(stuck).toContain('change the label on its row, or align it by hand');

    const relabelled = render({ selectedCandidates: { 1: true }, outcomes: { 1: refused }, labels: { 1: 'venue_id_join' } });
    expect(relabelled).toMatch(/value="venue_id_join"/);
    expect(relabelled).toContain('Retry the 1 not added');
    expect(relabelled).not.toMatch(/<button[^>]*data-testid="align-candidates-accept"[^>]*\sdisabled=""/);
  });

  it('never adds a testid the page object counts as a candidate row', () => {
    // AlignmentsPage reads `[data-testid^="align-candidate-"]:not([data-testid*="check"])` as the rows.
    const html = render({ outcomes: { 1: { state: 'failed', message: 'x', retryable: false, sentLabel: 'join_id_id' } } });
    expect(html.match(/data-testid="align-candidate-(?!check)/g)?.length).toBe(candidates.length);
  });

  it('marks a candidate that is already aligned and does not let it be ticked', () => {
    const html = render({ unavailable: { 2: 'already aligned' } });
    expect(html).toContain('already aligned');
    expect(html).toMatch(/<input[^>]*data-testid="align-candidate-check-venue-vid"[^>]*\sdisabled=""/);
  });

  it('disables Accept while an accept is in flight', () => {
    expect(render({ accepting: true })).toMatch(/<button[^>]*data-testid="align-candidates-accept"[^>]*\sdisabled=""/);
  });
});

describe('SourceEnrollmentPanel', () => {
  it('names what the remove button removes, and shows its failure in the strip', () => {
    const html = renderToStaticMarkup(
      <SourceEnrollmentPanel enrolledDatasetIds={['d1']} datasetName={() => 'researchers'} onRemoveSource={() => {}}
        modelError={null} removeError="Could not remove researchers: timeout" />,
    );
    expect(html).toContain('aria-label="Remove researchers from this model"');
    expect(html).toContain('role="alert"');
    expect(html).toContain('Could not remove researchers: timeout');
  });
});

describe('CompilePanel with a draft restored after a reload (J4-F2)', () => {
  it('offers the promote and says the compile report is not stored, instead of claiming zero unused alignments', () => {
    const html = renderToStaticMarkup(
      <CompilePanel rebuildRequired={false} onIntegrate={() => {}} integrating={false} compileJobId={null}
        selectedProjectId="p1" enrolledDatasetIds={['d1']} incrementalDatasetId="" setIncrementalDatasetId={() => {}}
        canIncrement={false} staleBaseline addableDatasetIds={['d2']} datasetName={(id) => id} onIncrementalAdd={() => {}}
        hasBaseline integrationResult={{
          draftVersionId: 'v7', draftVersionNumber: '7', tripleCount: '', unusedAlignments: '[]', turtle: '',
          integrationRunId: 'run-7', mode: 'rebuild', restored: true,
        }}
        onCompileDone={() => {}} integrationError={null} promoted={false} onPromoteDraft={() => {}} promoting={false}
        integratedGraph={null} integratedGraphLoading={false} integratedGraphError={null} integratedSelectedNode={null}
        setIntegratedSelectedNode={() => {}} />,
    );
    expect(html).toContain('data-testid="align-promote"');
    expect(html).toContain('Promote v7 (tenant-wide)');
    expect(html).toContain('data-testid="align-draft-restored"');
    expect(html).not.toContain('Every accepted alignment was used by the compiler');
    expect(html).toContain('no longer this model');
  });
});
