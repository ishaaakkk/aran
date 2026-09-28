import { describe, expect, it } from 'vitest'
import { joinScoreTone } from './joinScore'

// Every exact score below is one FREYJA computes from real profiler output — see
// freyja/tests/api/test_profile_api.py, which pins the anchors and the key-column cases.
describe('joinScoreTone', () => {
  it('reaches the strong band for an exact score — identical value sets at the default strictness give 0.495', () => {
    expect(joinScoreTone(0.4949, 'exact')).toBe('strong')
  })

  it('bands an exact score by the share of distinct values two equal-cardinality columns have in common', () => {
    expect(joinScoreTone(0.2932, 'exact')).toBe('strong') // 80%
    expect(joinScoreTone(0.1404, 'exact')).toBe('good') // 60%
    expect(joinScoreTone(0.0886, 'exact')).toBe('weak') // 50%
  })

  it('colours a foreign key against its primary key strong, and unrelated serial keys against it weak', () => {
    // customers.id 1..1000 against orders.customer_id (random references), products.id 1..300
    // and orders.id 1..5000. Scored from top-10 lists, products.id reached the good band (0.26)
    // and the foreign key ranked last (0.005).
    expect(joinScoreTone(0.4949, 'exact')).toBe('strong') // orders.customer_id
    expect(joinScoreTone(0.0731, 'exact')).toBe('weak') // products.id
    expect(joinScoreTone(0.0245, 'exact')).toBe('weak') // orders.id
  })

  it('keeps the ML model its 0–1 bands', () => {
    expect(joinScoreTone(0.8, 'ml')).toBe('strong')
    expect(joinScoreTone(0.6, 'ml')).toBe('good')
    expect(joinScoreTone(0.4949, 'ml')).toBe('weak')
  })
})
