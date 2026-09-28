import { describe, it, expect } from 'vitest'
import { statementFor, editorDiverged, resultState, limitOf, exportNotice, type LastRun } from './workbenchRun'
import type { QueryRunResult } from '../services/types'

const page1: QueryRunResult = {
  headers: ['t'], headerNames: ['t'], rows: [['a']], rowCount: 1000, offset: 0, pageSize: 1000,
  hasMore: true, truncated: true, elapsedMs: 12,
}

const ranA: LastRun = {
  text: 'SELECT Paper.title FROM Paper WHERE Paper.venue = \'VLDB\'',
  lang: 'oql',
  schemaVersion: 3,
  result: page1,
}

/** The analyst edited the WHERE after the run — the J5-F1 story. */
const edited = { text: 'SELECT Paper.title FROM Paper WHERE Paper.venue = \'SIGMOD\'', lang: 'oql' as const }

describe('statementFor — which text an action executes (J5-F1, rule R3)', () => {
  it('Run executes the editor text: the text is the query of record (R1)', () => {
    expect(statementFor('run', edited, ranA)).toEqual(edited)
    expect(statementFor('run', edited, null)).toEqual(edited)
  })

  it.each(['page', 'export', 'save'] as const)(
    '%s executes the run that produced the result, never the edited editor',
    (action) => {
      expect(statementFor(action, edited, ranA)).toEqual({ text: ranA.text, lang: 'oql' })
    },
  )

  it('a result action with no result on screen has nothing to execute', () => {
    expect(statementFor('page', edited, null)).toBeNull()
    expect(statementFor('export', edited, null)).toBeNull()
    expect(statementFor('save', edited, null)).toBeNull()
  })

  it('keeps the language of the run, so a SQL result is never paged as OQL', () => {
    const sqlRun: LastRun = { ...ranA, text: 'SELECT * FROM acm', lang: 'sql' }
    expect(statementFor('page', { text: 'SELECT X.* FROM X', lang: 'oql' }, sqlRun))
      .toEqual({ text: 'SELECT * FROM acm', lang: 'sql' })
  })
})

describe('editorDiverged', () => {
  it('is false while the editor still holds the run text (whitespace at the ends aside)', () => {
    expect(editorDiverged(ranA, ranA.text, 'oql')).toBe(false)
    expect(editorDiverged(ranA, `  ${ranA.text}\n`, 'oql')).toBe(false)
  })

  it('is true once the text or the language changed', () => {
    expect(editorDiverged(ranA, edited.text, 'oql')).toBe(true)
    expect(editorDiverged(ranA, ranA.text, 'sql')).toBe(true)
  })

  it('is false with no run on screen — there is nothing to diverge from', () => {
    expect(editorDiverged(null, 'anything', 'oql')).toBe(false)
  })
})

describe('resultState — the lifecycle the result pane reports as data-state', () => {
  const base = { running: false, stopped: false, error: null as string | null, hasResult: true, stale: false }

  it('running wins over everything: a running query shows neither a result nor an error (R4)', () => {
    expect(resultState({ ...base, running: true, error: 'boom' })).toBe('running')
  })

  it('a failure replaces the result', () => {
    expect(resultState({ ...base, error: 'boom' })).toBe('error')
  })

  it('stopped waiting is its own state, not success and not failure', () => {
    expect(resultState({ ...base, stopped: true, hasResult: false })).toBe('stopped')
  })

  it('reports stale once the editor diverged from the run on screen', () => {
    expect(resultState({ ...base, stale: editorDiverged(ranA, edited.text, 'oql') })).toBe('stale')
    expect(resultState(base)).toBe('ok')
  })

  it('is idle before anything ran', () => {
    expect(resultState({ ...base, hasResult: false })).toBe('idle')
  })
})

describe('limitOf — the row cap a query sets itself (J5-F14)', () => {
  it('reads a final LIMIT, where the grammar puts it', () => {
    expect(limitOf('SELECT A.x FROM A\nLIMIT 100')).toBe(100)
    expect(limitOf('SELECT A.x FROM A limit 5;')).toBe(5)
  })

  it('is null when the query sets none, or when "LIMIT" is only inside a string', () => {
    expect(limitOf('SELECT A.x FROM A')).toBeNull()
    expect(limitOf("SELECT A.x FROM A WHERE A.note = 'LIMIT 5'")).toBeNull()
  })
})

describe('exportNotice — what a finished export says (J5-F14)', () => {
  it('says how many rows the file holds', () => {
    expect(exportNotice(50213, 'csv', null)).toBe('Downloaded 50,213 rows as CSV.')
    expect(exportNotice(1, 'parquet', null)).toBe('Downloaded 1 row as Parquet.')
  })

  it("says when the query's own LIMIT capped the file — the builder appends one", () => {
    expect(exportNotice(100, 'csv', 100)).toMatch(/LIMIT 100 applied/)
    // Fewer rows than the LIMIT: it cut nothing, so it is not mentioned.
    expect(exportNotice(40, 'csv', 100)).toBe('Downloaded 40 rows as CSV.')
  })

  it('states no number when the server reported none — never "0 rows" for a full file', () => {
    const unknown = exportNotice(null, 'csv', null)
    expect(unknown).toBe('Downloaded the file as CSV; the server did not report its row count.')
    expect(unknown).not.toMatch(/\b0 rows\b/)
    // What is still known: the query's own LIMIT bounds the file.
    expect(exportNotice(null, 'parquet', 100)).toMatch(/LIMIT 100, so it holds at most 100 rows\.$/)
  })
})
