import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SchemaGraphModule, SchemaGraphSimulationEvents } from './schema-graph'

/**
 * The alignment canvas's fit policy. It used to fit once, on a 700 ms timer: under load a layout
 * still expanding at 700 ms stayed unfitted, nodes sat outside the clipped pane, and the e2e
 * clicks on them missed (b1/b2 failed 4 of 6 full-suite runs). It now fits when the simulation
 * settles, keeps the timer as an early fit, and leaves the view alone once the person moves it.
 */

let AranSchemaGraph: SchemaGraphModule

beforeAll(async () => {
  // The UMD bundle registers itself on `self` when there is no CommonJS module to fill.
  vi.stubGlobal('self', globalThis)
  const mod = (await import('./schema-graph.js')) as { default?: SchemaGraphModule }
  const registered = (globalThis as { AranSchemaGraph?: SchemaGraphModule }).AranSchemaGraph
  AranSchemaGraph = (mod.default && 'autoFit' in mod.default ? mod.default : registered)!
})

afterAll(() => vi.unstubAllGlobals())

/** The event half of a d3 force simulation, fired by hand. */
class FakeSimulation implements SchemaGraphSimulationEvents {
  readonly listeners = new Map<string, () => void>()
  on(typenames: string, listener: (() => void) | null) {
    if (listener) this.listeners.set(typenames, listener)
    else this.listeners.delete(typenames)
    return this
  }
  end() {
    for (const [name, listener] of [...this.listeners]) if (name.startsWith('end')) listener()
  }
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe('autoFit', () => {
  it('fits when the simulation settles, even long after the early timer', () => {
    const sim = new FakeSimulation()
    const fit = vi.fn()
    AranSchemaGraph.autoFit(sim, fit, 700)

    vi.advanceTimersByTime(700)
    expect(fit).toHaveBeenCalledTimes(1)

    // A slow machine: the layout is still expanding at 700 ms and settles seconds later.
    vi.advanceTimersByTime(5_000)
    sim.end()
    expect(fit).toHaveBeenCalledTimes(2)
  })

  it('fits on settle even when the simulation ends before the timer', () => {
    const sim = new FakeSimulation()
    const fit = vi.fn()
    AranSchemaGraph.autoFit(sim, fit, 700)

    sim.end()
    expect(fit).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(700)
    expect(fit).toHaveBeenCalledTimes(1)
  })

  it("leaves the person's own pan or zoom alone", () => {
    const sim = new FakeSimulation()
    const fit = vi.fn()
    const fitting = AranSchemaGraph.autoFit(sim, fit, 700)

    fitting.userMoved()
    vi.advanceTimersByTime(700)
    sim.end()
    expect(fit).not.toHaveBeenCalled()
  })

  it('fits only the first settle — a drag that restarts the simulation does not refit', () => {
    const sim = new FakeSimulation()
    const fit = vi.fn()
    AranSchemaGraph.autoFit(sim, fit, 700)

    sim.end()
    sim.end()
    expect(fit).toHaveBeenCalledTimes(1)
  })

  it('dispose cancels both the timer and the settle listener', () => {
    const sim = new FakeSimulation()
    const fit = vi.fn()
    AranSchemaGraph.autoFit(sim, fit, 700).dispose()

    vi.advanceTimersByTime(700)
    sim.end()
    expect(fit).not.toHaveBeenCalled()
  })
})

/**
 * J4-F3: a keyboard-only or screen-reader engineer could not create a manual alignment — the
 * graph's nodes were its only picker and none could take focus. Nodes are now named, focusable
 * buttons that Enter/Space select.
 */
describe('node accessibility (J4-F3)', () => {
  it('names a node by what a sighted user reads off it: kind, label and source', () => {
    expect(AranSchemaGraph.nodeAccessibleName({ id: 'n1', type: 'class', label: 'Author' })).toBe('class Author')
    expect(AranSchemaGraph.nodeAccessibleName({ id: 'n2', type: 'datatype', label: 'rid', sublabel: 'researchers.csv' }))
      .toBe('property rid, from researchers.csv')
    expect(AranSchemaGraph.nodeAccessibleName({ id: 'n3', type: 'object', label: 'hasAuthor' })).toBe('relationship hasAuthor')
    expect(AranSchemaGraph.nodeAccessibleName({ id: 'n4', type: 'xsd', label: 'string' })).toBe('datatype string')
    expect(AranSchemaGraph.nodeAccessibleName({ id: 'n5', type: 'integrated' })).toBe('integrated class n5')
  })

  it('selects on Enter and Space, like a button, and on nothing else', () => {
    expect(AranSchemaGraph.activatesNode('Enter')).toBe(true)
    expect(AranSchemaGraph.activatesNode(' ')).toBe(true)
    expect(AranSchemaGraph.activatesNode('Tab')).toBe(false)
    expect(AranSchemaGraph.activatesNode('a')).toBe(false)
  })

  // No DOM in this project's unit tests (D8/B0 brings one), so the wiring itself is pinned at the
  // source: every node the renderer joins must be focusable, a button, named, and keyboard-bound.
  it('makes every rendered node a focusable, named button bound to the keyboard', async () => {
    const { readFileSync } = await import('node:fs')
    const source = readFileSync(new URL('./schema-graph.js', import.meta.url), 'utf8')
    const nodeChain = source.slice(source.indexOf("const nodeSel = nodesG.selectAll('g.aran-schema-node')"),
      source.indexOf('// Click outside to deselect'))
    expect(nodeChain).toContain(".attr('tabindex', 0)")
    expect(nodeChain).toContain(".attr('role', 'button')")
    expect(nodeChain).toContain(".attr('aria-label', d => nodeAccessibleName(d))")
    expect(nodeChain).toContain(".attr('aria-pressed'")
    expect(nodeChain).toContain(".on('keydown'")
  })
})
