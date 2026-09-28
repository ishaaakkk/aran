/// <reference types="node" />
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * The docs quote the UI, and nothing kept the quotes true (UI audit J1-F22, J6-F10): four pages
 * sent readers to "Settings → API tokens" in a UI with no Settings, and five to "Repositories →
 * New" beside a button labelled "Register repository". The `tourRegistry.test` precedent — assert
 * that what the prose points at exists in src/ — applied to the docs site.
 *
 * The contract for docs authors: a UI path is written in bold with arrows, `**A → B**`, and every
 * segment is a label the UI renders — a whole JSX text node or a whole string literal in
 * aran-web/src. A landmark that is not a label ("your avatar") stays outside the bold.
 */

const DOCS = join(process.cwd(), '..', 'docs-site', 'docs')
const SRC = join(process.cwd(), 'src')

function files(dir: string, ext: RegExp): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) return files(path, ext)
    return ext.test(entry.name) ? [path] : []
  })
}

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** True when `label` is a whole JSX text node or a whole string literal somewhere in src. */
function rendered(label: string, sources: string): boolean {
  const re = new RegExp(`[>'"\`]\\s*${escape(label)}\\s*[<'"\`]`)
  return re.test(sources)
}

const docsPresent = existsSync(DOCS)

describe.skipIf(!docsPresent)('docs quote only UI labels this build renders', () => {
  const sources = files(SRC, /\.tsx?$/)
    .filter((f) => !/\.test\.tsx?$/.test(f))
    .map((f) => readFileSync(f, 'utf8'))
    .join('\n')
  const pages = files(DOCS, /\.mdx?$/).map((f) => ({ f, text: readFileSync(f, 'utf8') }))

  it('every segment of a bold UI path (**A → B**) is a rendered label', () => {
    const missing: string[] = []
    for (const { f, text } of pages) {
      // Bold spans are the odd pieces between `**` pairs (outside fenced code); a span may wrap
      // across a line, so segments are compared with whitespace collapsed.
      const bold = text
        .replace(/```[\s\S]*?```/g, '')
        // Inline code can hold a literal `**` (a path glob); a bold span holding code is a code
        // path (`A` → `B`), not a UI path, so it is marked and skipped.
        .replace(/`[^`\n]*`/g, '\u0000')
        .split('**')
        .filter((_, i) => i % 2 === 1)
        .filter((span) => span.includes('→') && !span.includes('\u0000'))
      for (const span of bold) {
        for (const segment of span.split('→').map((s) => s.replace(/\s+/g, ' ').trim())) {
          if (!rendered(segment, sources)) missing.push(`${f.slice(DOCS.length + 1)}: "${segment}" in **${span}**`)
        }
      }
    }
    expect(missing, `UI labels the docs quote but src never renders:\n${missing.join('\n')}`).toEqual([])
  })

  /**
   * Claims the audit found false and fixed. Kept as a list because each is a sentence someone
   * wrote in good faith once and could write again from an old draft.
   */
  it('none of the retired false claims comes back', () => {
    const retired = [
      'Settings → API tokens',
      'key icon in the left rail',
      'never leaves your machine',
      'redirects you to Keycloak',
      'as if to\nPostgres',
      'as if to Postgres',
      'Add colleagues from **People**',
      'It reports the resolved entitlements',
    ]
    const hits: string[] = []
    for (const { f, text } of pages) {
      for (const claim of retired) {
        if (text.includes(claim)) hits.push(`${f.slice(DOCS.length + 1)}: "${claim}"`)
      }
    }
    for (const claim of retired) {
      if (sources.includes(claim)) hits.push(`aran-web/src: "${claim}"`)
    }
    expect(hits).toEqual([])
  })

  /**
   * J3-F2 / J3-F20: while the Profile tab's run profiles text columns only (`varcharOnly: 'true'`)
   * it replaces the profile bootstrap stored with a narrower one, and join discovery loses every
   * numeric key — so the how-to that tells readers to click it must say so. Once the tab stops
   * narrowing (package A2), the caution must go as well: a warning about a defect this build no
   * longer has is a false claim of its own.
   */
  it('the profiling how-to cautions about the narrowing run exactly while the Profile tab narrows', () => {
    const narrows = /varcharOnly:\s*['"]true['"]/.test(sources)
    const page = readFileSync(join(DOCS, 'how-to', 'bootstrap-and-profile-a-dataset.md'), 'utf8')
    const cautions = /:::(?:caution|warning)[^\n]*\n(?:(?!:::)[\s\S])*?text columns only/.test(page)
    expect(
      cautions,
      narrows
        ? 'the Profile tab still profiles text columns only: bootstrap-and-profile-a-dataset.md needs its J3-F2 caution'
        : 'the Profile tab no longer narrows the profile (J3-F2 fixed): remove the caution from bootstrap-and-profile-a-dataset.md',
    ).toBe(narrows)
  })
})
