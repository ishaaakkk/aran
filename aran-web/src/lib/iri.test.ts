import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { IRI_FRAGMENT_UNSAFE, iriFragment, localName } from './iri'

describe('iriFragment — the display twin of Names.iriFragment', () => {
  it('uses exactly the character class aran-core mints with', () => {
    const names = readFileSync(
      join(process.cwd(), '..', 'aran-core/src/main/java/edu/upc/dtak/aran/core/util/Names.java'), 'utf8')
    const javaClass = /public static String iriFragment\(String s\) \{\s*return s\.replaceAll\("([^"]+)", "_"\);/
      .exec(names)?.[1]
    expect(javaClass, 'Names.iriFragment no longer has the shape this twin mirrors').toBeDefined()
    expect(IRI_FRAGMENT_UNSAFE.source).toBe(javaClass)
  })

  it('rewrites every character outside [A-Za-z0-9._-] to _, keeping dots and dashes', () => {
    expect(iriFragment('has author')).toBe('has_author')
    expect(iriFragment('writtenBy')).toBe('writtenBy')
    expect(iriFragment('a.b-c_d')).toBe('a.b-c_d')
    expect(iriFragment('año/2024')).toBe('a_o_2024')
  })
})

describe('localName', () => {
  it('takes what follows the last / or #', () => {
    expect(localName('http://x/y#z')).toBe('z')
    expect(localName('http://x/y/z')).toBe('z')
  })
})
