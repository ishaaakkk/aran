import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

/**
 * J6-F20: the product preview renders the same ResultTable as the workbench, but its cell padding
 * lived under `.workbench-app` in workbench.css — so outside the workbench the cells ran together
 * ("RDF Graphs2022 Maria Garcia18") and an analyst misread values. The table now brings its own
 * stylesheet, unscoped, wherever it is mounted. There is no DOM in this suite to measure padding,
 * so the claim is asserted where it lives: in the stylesheets and the import.
 */
const here = dirname(fileURLToPath(import.meta.url))
const read = (rel: string) => readFileSync(join(here, rel), 'utf8')

describe('ResultTable styles do not depend on the workbench scope (J6-F20)', () => {
  it('imports its own stylesheet', () => {
    expect(read('ResultTable.tsx')).toMatch(/import '\.\/resultTable\.css'/)
  })

  it('pads and separates cells without a .workbench-app ancestor', () => {
    const css = read('resultTable.css')
    expect(css).toMatch(/(^|\n)\.rtable td\s*\{[^}]*padding/)
    expect(css).toMatch(/(^|\n)\.rtable th\s*\{[^}]*padding/)
    expect(css).not.toContain('.workbench-app')
  })

  it('leaves no scoped copy behind in workbench.css to drift from it', () => {
    expect(read('../views/workbench.css')).not.toMatch(/\.workbench-app \.rtable (td|th)\s*\{/)
  })
})
