import { describe, it, expect } from 'vitest'
import type { ComponentProps } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { DatasetWrapperTab } from './DatasetWrapperTab'
import { wrapperSeedSelection } from './types'
import type { ModelSource } from '../../services/types'

/**
 * The expansion badge (Phase 3A): the one signal on this tab for whether the query engine can
 * plan around the selected class's wrapper (column pruning / predicate pushdown) or must treat
 * it as an opaque black box. A wrong badge is worse than no badge — it tells a user their SQL
 * is fine to leave alone, or panics them over SQL that verified clean.
 *
 * No jsdom in this project; `renderToStaticMarkup` is enough for output assertions (see
 * DatasetFreshness.test.tsx for the same idiom).
 */

function source(overrides: Partial<ModelSource> = {}): ModelSource {
  return {
    id: 'msrc-1',
    datasetId: 'ds-1',
    classIri: 'https://example.org/onto#Paper',
    wrapperSql: 'SELECT * FROM ${SNAPSHOT}',
    expansionStatus: null,
    expansionReason: null,
    ...overrides,
  }
}

const noop = () => {}

type TabProps = ComponentProps<typeof DatasetWrapperTab>

function render(sources: ModelSource[], opts: Partial<TabProps> = {}): string {
  return renderToStaticMarkup(
    <DatasetWrapperTab
      {...opts}
      modelLoading={false}
      modelSources={sources}
      onEnrollDataset={noop}
      selectedWrapperClass={sources[0]?.classIri ?? null}
      setSelectedWrapperClass={noop}
      wrapperText={opts.wrapperText ?? sources[0]?.wrapperSql ?? ''}
      enforcedConstraintClasses={opts.enforcedConstraintClasses}
      wrapperSeeded={opts.wrapperSeeded}
      setWrapperText={noop}
      wrapperPreviewLoading={false}
      wrapperPreviewData={null}
      onPreviewWrapper={noop}
      onSaveWrapper={noop}
    />,
  )
}

describe('DatasetWrapperTab expansion badge', () => {
  it('marks a verified wrapper as optimizable', () => {
    const html = render([source({ expansionStatus: 'EXPANDED' })])

    expect(html).toContain('Optimizable — verified round-trip')
    expect(html).not.toContain('Opaque')
  })

  it('shows the opaque reason on the badge and in full in its tooltip', () => {
    const html = render([source({ expansionStatus: 'OPAQUE', expansionReason: 'uses a window function' })])

    expect(html).toContain('Opaque — uses a window function')
    expect(html).toContain('title="uses a window function"')
  })

  it('reads an explicitly-unverified wrapper as opaque, not blank', () => {
    // status/reason both null is the server's own "seeded, never edited" verdict
    // (ModelSource's javadoc) — it must read as opaque, the same default the query
    // engine applies, not as an empty badge that looks like a rendering bug.
    const html = render([source({ expansionStatus: null, expansionReason: null })])

    expect(html).toContain('Opaque — not yet verified')
  })

  it('treats a source with no verdict fields at all the same as an explicit null', () => {
    // Fixtures/mocks written before Phase 3A (e.g. services/demo/demoWorld.ts) construct
    // ModelSource without expansionStatus/expansionReason at all — that is exactly why
    // types.ts declares them optional rather than required-nullable. Omitted must behave
    // identically to explicit null, not throw and not silently render nothing.
    const legacySource: ModelSource = {
      id: 'msrc-legacy',
      datasetId: 'ds-1',
      classIri: 'https://example.org/onto#Paper',
      wrapperSql: null,
    }
    const html = render([legacySource])

    expect(html).toContain('Opaque — not yet verified')
  })

  it('truncates a long reason on the badge but keeps the full text in the tooltip', () => {
    const longReason = 'x'.repeat(120)
    const html = render([source({ expansionStatus: 'OPAQUE', expansionReason: longReason })])

    expect(html).toContain(`title="${longReason}"`)
    expect(html).toContain(`Opaque — ${'x'.repeat(80)}…`)
    expect(html).not.toContain(`Opaque — ${longReason}`)
  })

  it('has no verdict to show before the dataset is even enrolled', () => {
    const html = render([])

    expect(html).not.toContain('dataset-wrapper-expansion-badge')
  })
})

/**
 * J4-F12 (the UI half of WRAPPER_VERIFY-9): "Optimizable — verified round-trip" is a fact about
 * the WRAPPER, while the planner refuses to expand a CLASS with an enforced denial constraint or
 * a DECIMAL/TIMESTAMP column. v32's spec pins the badge text itself, so the scope goes in a line
 * of its own beside it rather than into the badge.
 */
describe('DatasetWrapperTab — what "Optimizable" does not promise (J4-F12)', () => {
  it('scopes a verified badge with the two cases the planner still runs as written', () => {
    const html = render([source({ expansionStatus: 'EXPANDED' })])

    expect(html).toContain('Optimizable — verified round-trip')
    expect(html).toContain('dataset-wrapper-expansion-scope')
    expect(html).toContain('enforced denial constraint')
    expect(html).toContain('DECIMAL or TIMESTAMP')
  })

  it('names the class when one this dataset feeds has an enforced constraint right now', () => {
    const html = render([source({ expansionStatus: 'EXPANDED' })], { enforcedConstraintClasses: ['IPaper'] })

    expect(html).toContain('IPaper has an enforced denial constraint')
  })

  it('shows no scope line on an opaque wrapper, which promises nothing to scope', () => {
    const html = render([source({ expansionStatus: 'OPAQUE', expansionReason: 'uses a window function' })])

    expect(html).not.toContain('dataset-wrapper-expansion-scope')
  })
})

/**
 * J4-F12: switching class used to drop unsaved SQL silently (the seed effect replaces the
 * editor on selection change). While the editor differs from what is saved, the tab says so and
 * the class picker waits — save or revert first — instead of discarding the edit.
 */
describe('DatasetWrapperTab — unsaved edits (J4-F12)', () => {
  it('marks an edited wrapper as unsaved and holds the class picker', () => {
    const html = render([source()], { wrapperText: 'SELECT title FROM ${SNAPSHOT}' })

    expect(html).toContain('data-testid="dataset-wrapper-unsaved"')
    expect(html).toMatch(/data-testid="dataset-wrapper-class-select"[^>]*disabled=""/)
    expect(html).toContain('data-testid="dataset-wrapper-revert"')
  })

  it('shows no marker and keeps the picker live when the editor matches what is saved', () => {
    const html = render([source()])

    expect(html).not.toContain('dataset-wrapper-unsaved')
    expect(html).not.toMatch(/data-testid="dataset-wrapper-class-select"[^>]*disabled=""/)
  })
})

/**
 * The same contract `components/workbench/e2eContract.test.tsx` keeps for the workbench:
 * `npx playwright test --list` only *collects* specs, it never resolves a locator, so a UI
 * refactor can bury a selector with every unit test and the whole vitest suite still green.
 * `DatasetsPage` drives these; adding one there means adding it here.
 */
describe('DatasetWrapperTab e2e selector contract', () => {
  it('exposes the save control and the class picker the suite drives', () => {
    // `DatasetsPage.saveWrapper` clicks the first and `wrapperSql`/`wrapperExpansionBadge`
    // depend on the second having selected a class — losing either silently strands the
    // V32 loop on a locator that will never resolve.
    const html = render([source()])

    expect(html).toContain('data-testid="dataset-wrapper-save"')
    expect(html).toContain('data-testid="dataset-wrapper-class-select"')
    expect(html).toContain('data-testid="dataset-wrapper-editor"')
    expect(html).toContain('data-testid="dataset-wrapper-preview"')
  })

  it('offers the enrol button, and only it, before the dataset is enrolled', () => {
    // `enrolInModel` re-evaluates between these two branches on every attempt precisely
    // because they are mutually exclusive; that is only true while this holds.
    const html = render([])

    expect(html).toContain('data-testid="dataset-enrol"')
    expect(html).not.toContain('data-testid="dataset-wrapper-editor"')
    expect(html).not.toContain('data-testid="dataset-wrapper-save"')
  })
})

/**
 * V49 — the editor's seed key.
 *
 * `DatasetsListView` re-seeds the wrapper textarea from the model only when this value changes.
 * The code it replaces keyed that effect on the `modelSources` array, which `loadModelForDataset`
 * re-allocates on every call, so a model reload landing mid-edit replaced the typed SQL and
 * Validate & Preview then sent the STORED wrapper — watched live on the e2e stack, POST body and
 * server log both.
 *
 * What these assertions pin is the property the guard rests on: **the key does not move when only
 * the model is re-fetched.** They cannot pin the effect itself — this project has no jsdom, so no
 * test here can mount a component and type into it. `a1-single-csv.spec.ts` ("a wrapper query
 * filters the bootstrapped source") is the pin for the end-to-end behaviour, and it is the test
 * that was red.
 */
describe('wrapperSeedSelection', () => {
  it('is stable across a model reload of the same dataset and class', () => {
    // Those two arguments are all a reload can change, and it changes neither.
    expect(wrapperSeedSelection('ds-1', 'https://example.org/onto#Paper'))
      .toBe(wrapperSeedSelection('ds-1', 'https://example.org/onto#Paper'))
  })

  it('moves when the selected class changes', () => {
    expect(wrapperSeedSelection('ds-1', 'https://example.org/onto#Paper'))
      .not.toBe(wrapperSeedSelection('ds-1', 'https://example.org/onto#Venue'))
  })

  it('moves when the same class IRI is reached through a different dataset', () => {
    // A source-class IRI already embeds its dataset id, so this cannot happen today; the dataset
    // id is in the key so it stays correct if that ever stops being true.
    expect(wrapperSeedSelection('ds-1', 'https://example.org/onto#Paper'))
      .not.toBe(wrapperSeedSelection('ds-2', 'https://example.org/onto#Paper'))
  })

  it('is null with no class selected, so closing the panel clears the editor', () => {
    // Closing the detail sets the selected class to null; that IS a selection change, and the
    // editor must not keep the last dataset's SQL for the next one to inherit.
    expect(wrapperSeedSelection('ds-1', null)).toBeNull()
    expect(wrapperSeedSelection(undefined, null)).toBeNull()
  })
})

/**
 * J8-F13 residue (review of UA2): between the render that mounts the editor and the effect that
 * seeds it, the textarea held the previous selection's text, and anything typed there was
 * overwritten — the walkthrough told users to "wait for the editor to load before typing".
 */
describe('DatasetWrapperTab before its selection is seeded', () => {
  const tag = (html: string, testId: string) =>
    html.match(new RegExp(`<(?:textarea|button)[^>]*data-testid="${testId}"[^>]*>`))?.[0] ?? ''

  it('keeps the editor read-only and busy, and Save and Preview off', () => {
    const html = render([source()], { wrapperSeeded: false })
    expect(tag(html, 'dataset-wrapper-editor')).toContain('readOnly=""')
    expect(tag(html, 'dataset-wrapper-editor')).toContain('aria-busy="true"')
    expect(tag(html, 'dataset-wrapper-save')).toContain('disabled=""')
    expect(tag(html, 'dataset-wrapper-preview')).toContain('disabled=""')
  })

  it('is an ordinary editable editor once seeded', () => {
    const html = render([source()], { wrapperSeeded: true })
    expect(tag(html, 'dataset-wrapper-editor')).not.toContain('readOnly')
    expect(tag(html, 'dataset-wrapper-editor')).not.toContain('aria-busy')
    expect(tag(html, 'dataset-wrapper-save')).not.toContain('disabled=""')
  })
})

/**
 * J4-F11, wrapper half (second and third reviews of UA2): with the save's deadline raised to 150 s,
 * a save that took twenty seconds against a live source showed nothing at all — Save looked idle,
 * and a second click started a second verify scan of the production source. The third review
 * added: the status arrived already holding its text (often not announced), a disabled Save
 * dropped keyboard focus to <body>, and a model that could not be read replaced the whole tab,
 * save check included.
 */
describe('DatasetWrapperTab while a save is in flight', () => {
  const button = (html: string, testId: string) =>
    html.match(new RegExp(`<button[^>]*data-testid="${testId}"[^>]*>`))?.[0] ?? ''
  const status = (html: string) =>
    html.match(/<p id="dataset-wrapper-status" role="status" data-testid="dataset-wrapper-status"[^>]*>(.*?)<\/p>/)

  it('turns Save busy and "Saving…" without taking focus from it, and says why it can take a while', () => {
    const html = render([source()], { savingWrapper: true })
    const save = button(html, 'dataset-wrapper-save')
    expect(save).toContain('aria-disabled="true"')
    expect(save).not.toContain('disabled=""')
    expect(save).toContain('aria-busy="true"')
    expect(save).toContain('aria-describedby="dataset-wrapper-status"')
    expect(html).toContain('Saving…')
    expect(html).not.toContain('Save Wrapper')
    expect(status(html)?.[1]).toContain('data-testid="dataset-wrapper-saving"')
    expect(status(html)?.[1]).toContain('this can take a while')
  })

  it('is an ordinary Save button when nothing is in flight, with its status region mounted and empty', () => {
    const html = render([source()])
    const save = button(html, 'dataset-wrapper-save')
    expect(save).not.toContain('disabled=""')
    expect(save).not.toContain('aria-disabled="')
    expect(save).not.toContain('aria-busy')
    expect(html).toContain('Save Wrapper')
    expect(html).not.toContain('dataset-wrapper-saving')
    expect(status(html)?.[1]).toBe('')
  })

  it('keeps a save the page stopped waiting for on the tab, with Check again beside the status rather than in it', () => {
    const html = render([source()], {
      saveCheck: { kind: 'not-yet', message: 'Not stored yet — the server may still be verifying this wrapper against its source.' },
      onCheckSave: noop,
    })
    expect(status(html)?.[1]).toContain('Not stored yet')
    expect(status(html)?.[1]).not.toContain('Check again')
    expect(button(html, 'dataset-wrapper-check-again')).not.toBe('')
    expect(html).toContain('Check again')
  })

  it('keeps Check again focusable while it checks', () => {
    const html = render([source()], {
      saveCheck: { kind: 'unknown', message: 'The page stopped waiting for this save.' },
      checkingSave: true,
      onCheckSave: noop,
    })
    const again = button(html, 'dataset-wrapper-check-again')
    expect(again).toContain('aria-disabled="true"')
    expect(again).not.toContain('disabled=""')
    expect(html).toContain('Checking…')
    expect(button(html, 'dataset-wrapper-save')).toContain('aria-disabled="true"')
  })

  it('a save the re-read found stored offers no Check again', () => {
    const html = render([source()], {
      saveCheck: { kind: 'saved', message: 'Saved — the server finished after the page stopped waiting.' },
      onCheckSave: noop,
    })
    expect(html).toContain('Saved — the server finished after the page stopped waiting.')
    expect(html).not.toContain('Check again')
  })

  it('turns the enrol button busy while an enrol is in flight, without taking focus from it', () => {
    const html = render([], { enrolling: true })
    const enrol = button(html, 'dataset-enrol')
    expect(enrol).toContain('aria-disabled="true"')
    expect(enrol).not.toContain('disabled=""')
    expect(enrol).toContain('aria-busy="true"')
    expect(html).toContain('Enrolling…')
  })
})

/**
 * Third review of UA2 (MINOR): an enrol the page stopped waiting for, that a re-read had not seen
 * land, was a 4 s toast — and Enroll was clickable again once it faded.
 */
describe('DatasetWrapperTab after an enrol the page stopped waiting for', () => {
  const button = (html: string, testId: string) =>
    html.match(new RegExp(`<button[^>]*data-testid="${testId}"[^>]*>`))?.[0] ?? ''

  it('keeps a "not enrolled yet" note beside Enroll, with Check again', () => {
    const html = render([], {
      enrolCheck: { kind: 'not-yet', message: 'Not enrolled yet — the server may still be seeding this dataset’s wrappers.' },
      onCheckEnrol: noop,
    })
    expect(html).toMatch(/role="status" data-testid="dataset-wrapper-status"[^>]*><span data-testid="dataset-enrol-check" data-kind="not-yet">Not enrolled yet/)
    expect(button(html, 'dataset-wrapper-check-again')).not.toBe('')
    expect(button(html, 'dataset-enrol')).toContain('aria-describedby="dataset-wrapper-status"')
  })

  it('keeps Enroll off while that check runs', () => {
    const html = render([], {
      enrolCheck: { kind: 'not-yet', message: 'Not enrolled yet.' },
      checkingEnrol: true,
      onCheckEnrol: noop,
    })
    expect(button(html, 'dataset-enrol')).toContain('aria-disabled="true"')
    expect(html).toContain('Checking…')
  })
})

/** J8-F2: a model that could not be read is not a dataset with nothing enrolled. */
describe('DatasetWrapperTab when the model could not be read', () => {
  const modelError = Object.assign(new Error('Aran hit an unexpected error (HTTP 500).'), { kind: 'server', correlationId: 'corr-9' })

  it('says so, with a retry, and offers no enrol on that guess', () => {
    const html = render([], { modelError, onRetryModel: noop })
    expect(html).toContain('Couldn&#x27;t load the integration model')
    expect(html).toContain('corr-9')
    expect(html).toContain('Retry')
    expect(html).not.toContain('data-testid="dataset-enrol"')
  })

  // Third review of UA2 (MINOR): a save that timed out and whose re-read failed showed only the
  // generic model error — nothing about the save.
  it('still answers for a save the page stopped waiting for', () => {
    const html = render([], {
      modelError,
      onRetryModel: noop,
      saveCheck: { kind: 'unknown', message: 'The page stopped waiting for this save, and the model could not be read to check whether it landed.' },
      onCheckSave: noop,
    })
    expect(html).toContain('dataset-wrapper-model-error')
    expect(html).toMatch(/role="status" data-testid="dataset-wrapper-status"[^>]*><span data-testid="dataset-wrapper-save-check" data-kind="unknown">The page stopped waiting for this save/)
    expect(html).toContain('data-testid="dataset-wrapper-check-again"')
  })
})
