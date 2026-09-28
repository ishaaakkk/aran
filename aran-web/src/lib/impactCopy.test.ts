import { describe, expect, it } from 'vitest'
import {
  constraintToggleToast,
  datasetDeleteConfirm,
  productDeleteConfirm,
  repositoryDeleteConfirm,
  WRAPPER_SAVED_TOAST,
} from './impactCopy'

/**
 * A confirmation is only as good as what it says will happen. Each of these was a sentence
 * that under-stated the effect of the click it guarded (audit §3, A1 truth pass): a repository
 * delete that "stops refreshing" datasets it actually deletes, a dataset delete that removes
 * "metadata", a constraint toggle that says "recompile" for a rule enforced on the next query.
 * The sentences live here, not inline in the god components, so they can be asserted without a
 * DOM and stay one copy per effect.
 */
describe('repositoryDeleteConfirm (J2-F2)', () => {
  it('says the datasets are deleted, with the count, and what happens to the model', () => {
    const text = repositoryDeleteConfirm('warehouse', 3)
    expect(text).toContain('warehouse')
    expect(text).toContain('3 datasets')
    expect(text).toMatch(/deletes/i)
    expect(text).toContain('mappings are rejected')
    expect(text).toContain('next compile')
    expect(text).not.toContain('stop refreshing')
  })

  it('never guesses a count it does not have', () => {
    const text = repositoryDeleteConfirm('warehouse', undefined)
    expect(text).toContain('every dataset registered in it')
    expect(text).not.toMatch(/\bundefined\b|\bNaN\b/)
  })

  it('says one dataset in the singular, and none when there are none', () => {
    expect(repositoryDeleteConfirm('r', 1)).toContain('its 1 dataset')
    expect(repositoryDeleteConfirm('r', 0)).toContain('no datasets')
  })
})

describe('datasetDeleteConfirm (J2-F15)', () => {
  it('names the model-side effects and what is kept', () => {
    const text = datasetDeleteConfirm('acm')
    expect(text).toContain('acm')
    expect(text).not.toContain('metadata')
    expect(text).toContain('mappings are rejected')
    expect(text).toContain('next compile')
    expect(text).toContain('lake copy is kept')
  })
})

describe('productDeleteConfirm', () => {
  it('says who loses the product and that the snapshot stays', () => {
    const text = productDeleteConfirm('papers')
    expect(text).toContain('papers')
    expect(text).toMatch(/JDBC/)
    expect(text).toMatch(/agents/)
    expect(text).toContain('snapshot is kept')
  })
})

describe('constraintToggleToast (J4-F4)', () => {
  it('says an enforced constraint applies from the next query, not after a recompile', () => {
    const text = constraintToggleToast(true, 'IPaper')
    expect(text).not.toMatch(/recompile/i)
    expect(text).toContain('next query')
    expect(text).toContain('IPaper')
    expect(text).toContain('VIRTUAL')
    expect(text).toContain('MATERIALIZED products change at their next refresh')
  })

  it('says a disabled constraint stops filtering on the next query too', () => {
    const text = constraintToggleToast(false, 'IPaper')
    expect(text).toMatch(/disabled/i)
    expect(text).toContain('next query')
  })
})

describe('WRAPPER_SAVED_TOAST (J4-F12)', () => {
  it('keeps the prefix the e2e page object waits on, and says when the edit takes effect', () => {
    // DatasetsPage.saveWrapper waits for this exact prefix.
    expect(WRAPPER_SAVED_TOAST.startsWith('Wrapper SQL saved to the integration model.')).toBe(true)
    expect(WRAPPER_SAVED_TOAST).toContain('Rebuild')
    expect(WRAPPER_SAVED_TOAST).toContain('Promote')
  })
})
