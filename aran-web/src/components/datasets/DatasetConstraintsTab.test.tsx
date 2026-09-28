import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { DatasetConstraintsTab } from './DatasetConstraintsTab'
import type { CatalogClass, ModelConstraint } from '../../services/types'

/**
 * The origin badge (Phase 3B): the one signal on this tab for whether a listed constraint is a
 * LIMA candidate nobody has reviewed yet, or one a person actually wrote. A wrong badge is worse
 * than no badge — it would tell a user an unreviewed candidate is trusted, hand-typed SQL.
 *
 * No jsdom in this project; `renderToStaticMarkup` is enough for output assertions (see
 * DatasetWrapperTab.test.tsx for the same idiom — this file follows it exactly).
 */

const CLASS_IRI = 'https://example.org/onto#Paper'
const CLASS_A_IRI = CLASS_IRI
const CLASS_B_IRI = 'https://example.org/onto#Researcher'

function catalogClass(overrides: Partial<CatalogClass> = {}): CatalogClass {
  return {
    iri: CLASS_IRI,
    name: 'IPaper',
    intView: 'int_IPaper',
    rowCount: 10,
    enforcedConstraintCount: 0,
    sources: [],
    ...overrides,
  }
}

function constraint(overrides: Partial<ModelConstraint> = {}): ModelConstraint {
  return {
    id: 'con-1',
    classIri: CLASS_IRI,
    expression: 'year <= 2026',
    enforced: false,
    createdAt: '2026-08-01T00:00:00Z',
    origin: 'MANUAL',
    ...overrides,
  }
}

const noop = () => {}

interface DiscoveryOpts {
  discoveringClasses?: Record<string, boolean>
  discoverJobs?: Record<string, string>
  /** Defaults to the first class, same fallback the tab itself applies. Override to render as
   *  though the user has switched the class dropdown to a different one. */
  selectedConstraintClass?: string | null
  /** V42: constraints whose class the active catalog no longer serves. */
  orphanedConstraints?: ModelConstraint[]
  /** V43: the server's sentence about a discovery run that persisted nothing. */
  discoveryNotes?: Record<string, string>
}

function render(
  classes: CatalogClass[],
  constraints: ModelConstraint[],
  opts: DiscoveryOpts = {},
): string {
  return renderToStaticMarkup(
    <DatasetConstraintsTab
      modelLoading={false}
      datasetClasses={classes}
      selectedConstraintClass={opts.selectedConstraintClass ?? (classes[0]?.iri ?? null)}
      setSelectedConstraintClass={noop}
      modelConstraints={constraints}
      orphanedConstraints={opts.orphanedConstraints ?? []}
      constraintExpr=""
      setConstraintExpr={noop}
      savingConstraint={false}
      onAddConstraint={noop}
      onToggleEnforced={noop}
      onDeleteConstraint={noop}
      discoveringClasses={opts.discoveringClasses ?? {}}
      discoverJobs={opts.discoverJobs ?? {}}
      onDiscoverConstraints={noop}
      onDiscoverJobDone={noop}
      discoveryNotes={opts.discoveryNotes ?? {}}
      isDark={false}
    />,
  )
}

describe('DatasetConstraintsTab origin badge', () => {
  it('flags a discovered candidate with a badge whose tooltip does not promise enforcement', () => {
    const html = render([catalogClass()], [constraint({ origin: 'DISCOVERED' })])

    expect(html).toContain('constraint-discovered-badge')
    expect(html).toContain('discovered')
    // It used to read "review and enforce to apply" — the one control on this tab that promised
    // something the row cannot deliver (V43).
    expect(html).not.toContain('review and enforce to apply')
    expect(html).toContain('not an enforceable filter')
  })

  it('shows no badge on a manually-typed constraint', () => {
    const html = render([catalogClass()], [constraint({ origin: 'MANUAL' })])

    expect(html).not.toContain('constraint-discovered-badge')
  })

  it('treats a constraint with no origin field at all the same as manual (a pre-Phase-3B row)', () => {
    // Fixtures/mocks written before Phase 3B (e.g. services/demo/demoWorld.ts) construct
    // ModelConstraint without `origin` at all — that is exactly why types.ts declares it
    // optional rather than required-nullable. Omitted must render with no badge, not throw.
    const legacyConstraint: ModelConstraint = {
      id: 'con-legacy',
      classIri: CLASS_IRI,
      expression: 'h_index >= 0',
      enforced: true,
      createdAt: '2026-07-02T09:31:00Z',
    }
    const html = render([catalogClass()], [legacyConstraint])

    expect(html).not.toContain('constraint-discovered-badge')
  })

  it('exposes the class picker and the discover control the e2e suite drives', () => {
    // Same contract `components/workbench/e2eContract.test.tsx` keeps for the workbench, for
    // the same reason: `playwright test --list` collects specs but never resolves a locator.
    // `DatasetsPage.openConstraints` waits on the picker as its "the tab is ready" signal and
    // `discoverConstraints` waits on it again after the job, so losing it strands V34 twice.
    const html = render([catalogClass()], [])

    expect(html).toContain('data-testid="dataset-constraint-class-select"')
    expect(html).toContain('data-testid="dataset-discover-constraints"')
  })

  it('mirrors each row onto the attributes the e2e suite reads', () => {
    // `v34-constraint-discovery.spec.ts` reads a whole constraint row off these rather than
    // parsing the rendered line, which interleaves the expression, an optional chip, a
    // checkbox label and a delete button. Losing any of the three makes that spec read
    // `undefined` and assert against it, so they are pinned here — the same contract
    // `e2eContract.test.tsx` keeps for the workbench selectors.
    const html = render([catalogClass()], [
      constraint({ id: 'con-d', origin: 'DISCOVERED', enforced: false, expression: 'NOT (a = a)' }),
    ])

    expect(html).toContain('data-testid="constraint-row"')
    expect(html).toContain('data-expression="NOT (a = a)"')
    expect(html).toContain('data-origin="DISCOVERED"')
    expect(html).toContain('data-enforced="false"')
  })

  it('reports a row with no origin field as MANUAL on the attribute, not as empty', () => {
    const legacyConstraint: ModelConstraint = {
      id: 'con-legacy', classIri: CLASS_IRI, expression: 'h_index >= 0',
      enforced: true, createdAt: '2026-07-02T09:31:00Z',
    }
    const html = render([catalogClass()], [legacyConstraint])

    expect(html).toContain('data-origin="MANUAL"')
    expect(html).toContain('data-enforced="true"')
  })

  it('keeps a manual row enforceable — the checkbox is live and carries no explanation', () => {
    const html = render([catalogClass()], [constraint({ id: 'con-m', origin: 'MANUAL', enforced: false })])

    expect(html).toContain('id="enforced-con-m"')
    expect(html).toContain('type="checkbox"')
    expect(html).not.toContain('data-testid="constraint-enforce-blocked"')
  })

  it('disables the enforce control on a discovered row and says why (V43)', () => {
    // A discovered candidate is the single-row collapse of an inter-tuple constraint, so the
    // server refuses to enforce it (400). Leaving the checkbox live would offer a click whose
    // only possible outcome is an error toast — worse than the pre-fix state, where at least
    // the click appeared to work. The explanation is rendered, not only tooltipped, because
    // the reason a control is dead is not discoverable by hovering a disabled input.
    const html = render([catalogClass()], [constraint({ id: 'con-d', origin: 'DISCOVERED', enforced: false })])

    // A literal adjacency, not a loose `disabled` scan — the Add button above renders its own
    // `disabled=""` in every one of these fixtures (see the discover-button note below).
    expect(html).toContain('id="enforced-con-d" type="checkbox" disabled=""')
    expect(html).toContain('data-testid="constraint-enforce-blocked"')
    expect(html).toContain('evidence only')
    expect(html).toContain('inter-tuple')
  })
})

describe('DatasetConstraintsTab orphaned constraints (V42)', () => {
  const orphan = (overrides: Partial<ModelConstraint> = {}): ModelConstraint => ({
    id: 'con-orphan',
    // Not in `datasetClasses` — that is what makes it an orphan, and why before V42 it rendered
    // nowhere at all while the API still reported it enforced.
    classIri: 'https://example.org/integration#Int_j_authors',
    expression: 'h_index > 0',
    enforced: false,
    createdAt: '2026-08-01T00:00:00Z',
    origin: 'MANUAL',
    orphaned: true,
    ...overrides,
  })

  it('renders an orphaned row naming its dead class, with the reason', () => {
    const html = render([catalogClass()], [], { orphanedConstraints: [orphan()] })

    expect(html).toContain('data-testid="orphaned-constraint-row"')
    expect(html).toContain('data-expression="h_index &gt; 0"')
    expect(html).toContain('https://example.org/integration#Int_j_authors')
    expect(html).toContain('not enforced — class no longer in the active schema')
  })

  it('disables the enforce toggle on an orphaned row — the control that promised the guarantee says it no longer holds', () => {
    const html = render([catalogClass()], [], { orphanedConstraints: [orphan()] })

    // The attribute itself, by literal adjacency: React SSR renders a true boolean attribute as
    // `disabled=""` and omits it when false, while the static Tailwind token
    // `disabled:opacity-40` appears in class strings regardless of state (see the discover-button
    // note in the next describe for the same trap).
    expect(html).toContain('id="enforced-con-orphan" type="checkbox" disabled=""')
  })

  it('shows nothing at all when there are no orphans — no empty section on the common path', () => {
    const html = render([catalogClass()], [constraint()], { orphanedConstraints: [] })

    expect(html).not.toContain('orphaned-constraints')
    expect(html).not.toContain('no longer in the active schema')
  })

  it('still warns when the dataset has no integrated classes left — the merged-away case lands there', () => {
    // A dataset whose classes were ALL merged away renders the "no integrated classes" empty
    // state. That is precisely when a user most needs to be told their constraints stopped
    // applying, so the section must survive that early return.
    const html = render([], [], { orphanedConstraints: [orphan()] })

    expect(html).toContain('data-testid="orphaned-constraint-row"')
    expect(html).toContain('No integrated classes for this dataset yet.')
  })
})

/** J8-F2 (second review of UA2): a failed model or catalog read is not "no integrated classes". */
describe('DatasetConstraintsTab when the model or catalog could not be read', () => {
  it('says so with a retry instead of the "no integrated classes" onboarding', () => {
    const html = renderToStaticMarkup(
      <DatasetConstraintsTab
        modelLoading={false}
        modelError={Object.assign(new Error('Aran did not answer within 30 s.'), { kind: 'timeout', correlationId: 'corr-cat' })}
        onRetryModel={noop}
        datasetClasses={[]}
        selectedConstraintClass={null}
        setSelectedConstraintClass={noop}
        modelConstraints={[]}
        orphanedConstraints={[]}
        constraintExpr=""
        setConstraintExpr={noop}
        savingConstraint={false}
        onAddConstraint={noop}
        onToggleEnforced={noop}
        onDeleteConstraint={noop}
        discoveringClasses={{}}
        discoverJobs={{}}
        onDiscoverConstraints={noop}
        onDiscoverJobDone={noop}
        discoveryNotes={{}}
        isDark={false}
      />,
    )
    expect(html).toContain('data-testid="dataset-constraints-model-error"')
    expect(html).toContain('corr-cat')
    expect(html).not.toContain('No integrated classes for this dataset yet.')
  })
})


describe('DatasetConstraintsTab honest discovery emptiness (V43)', () => {
  const PAIR_ONLY =
    'Discovery found 2 dependencies that hold between PAIRS of rows, but none of them can be ' +
    'expressed as a constraint on a single row yet, so nothing was added. Support for row-pair ' +
    'constraints is future work (V43).'

  it('shows the server\'s own sentence when a run persisted nothing', () => {
    // The empty-success this replaces: "discovery complete — review candidates below", with an
    // empty list underneath and no way to tell "found nothing" from "found something this
    // product cannot express as a row constraint".
    const html = render([catalogClass()], [], { discoveryNotes: { [CLASS_IRI]: PAIR_ONLY } })

    expect(html).toContain('data-testid="discovery-empty-note"')
    expect(html).toContain('PAIRS of rows')
    expect(html).toContain('nothing was added')
  })

  it('shows no note at all before a run, or after one that produced candidates', () => {
    const html = render([catalogClass()], [constraint({ origin: 'DISCOVERED' })])

    expect(html).not.toContain('discovery-empty-note')
  })

  it('scopes the note to its own class — another class shows nothing', () => {
    // Same scoping rule as the in-flight job state above: a note is about the run on ONE class,
    // and switching the dropdown must not carry it across.
    const classA = catalogClass({ iri: CLASS_A_IRI, name: 'IPaper' })
    const classB = catalogClass({ iri: CLASS_B_IRI, name: 'IResearcher' })
    const html = render([classA, classB], [], {
      discoveryNotes: { [CLASS_A_IRI]: PAIR_ONLY },
      selectedConstraintClass: CLASS_B_IRI,
    })

    expect(html).not.toContain('discovery-empty-note')
  })
})

describe('DatasetConstraintsTab discover action', () => {
  it('renders a per-class discover-constraints button', () => {
    const html = render([catalogClass()], [])

    expect(html).toContain('dataset-discover-constraints')
    expect(html).toContain('Discover constraints')
  })

  it('has nothing to discover before the dataset has any integrated classes', () => {
    // Same early-return as the rest of the tab (no classes yet): no button to click on
    // a class picker that itself is not showing.
    const html = render([], [])

    expect(html).not.toContain('dataset-discover-constraints')
  })

  it('disables the discover button while a discovery job is submitting or running', () => {
    const html = render([catalogClass()], [], { discoveringClasses: { [CLASS_IRI]: true } })

    // A literal, not a loose substring scan: the button's own className always contains the
    // static Tailwind token "disabled:opacity-40" regardless of state, so `[^>]*disabled` would
    // false-positive on that class name even when the real `disabled` attribute is absent. React
    // SSR renders a true boolean attribute as `disabled=""` immediately after this prop (the
    // JSX declares data-testid, then disabled, then title) and omits it entirely when false —
    // asserting that exact adjacency is what actually pins the attribute, not the class string.
    expect(html).toContain('data-testid="dataset-discover-constraints" disabled=""')
  })

  it('shows a live job card once the discovery job id is known', () => {
    const html = render([catalogClass()], [], {
      discoveringClasses: { [CLASS_IRI]: true },
      discoverJobs: { [CLASS_IRI]: 'job-123' },
    })

    expect(html).toContain('aran discover constraints')
  })

  it('scopes an in-flight job to the class it belongs to — another class shows neither its card nor its disabled button', () => {
    // The bug this pins: class A's discovery is running when the user switches the class
    // dropdown to B. Before this fix, `discovering`/`discoverJobId` were single unscoped values,
    // so B would inherit A's disabled button, spinner, and job card — and, on completion, A's
    // "discovery complete" toast — while B was the one on screen.
    const classA = catalogClass({ iri: CLASS_A_IRI, name: 'IPaper' })
    const classB = catalogClass({ iri: CLASS_B_IRI, name: 'IResearcher' })
    const html = render([classA, classB], [], {
      discoveringClasses: { [CLASS_A_IRI]: true },
      discoverJobs: { [CLASS_A_IRI]: 'job-a' },
      selectedConstraintClass: CLASS_B_IRI,
    })

    // B's own button must render enabled (B has no job of its own) — see the literal-adjacency
    // note above for why this isn't a loose `disabled` substring scan.
    expect(html).toContain('dataset-discover-constraints')
    expect(html).not.toContain('data-testid="dataset-discover-constraints" disabled=""')
    // ...and A's job card must not leak into a render where B is selected.
    expect(html).not.toContain('aran discover constraints')
  })
})

/**
 * J4-F4: what enforcing does. The tab used to promise rows are "kept out of every query" —
 * the `sources` tier is never filtered and a MATERIALIZED snapshot keeps its rows until its next
 * refresh — while the toast said "recompile to apply" for a rule enforced on the next query.
 * One scoped sentence (`CONSTRAINT_ENFORCE_EFFECT`) now serves the empty state and the tooltip.
 */
describe('DatasetConstraintsTab — what enforcing does (J4-F4)', () => {
  it('scopes the effect to graph-tier queries from the next query, and names MATERIALIZED products', () => {
    const html = render([catalogClass()], [constraint()])
    expect(html).not.toContain('kept out of every query')
    expect(html).toContain('from the next query')
    expect(html).toContain('MATERIALIZED products change at their next refresh')
  })

  it('says the same in the empty state', () => {
    const html = render([catalogClass()], [])
    expect(html).not.toContain('kept out of every query')
    expect(html).toContain('from the next query')
  })
})
