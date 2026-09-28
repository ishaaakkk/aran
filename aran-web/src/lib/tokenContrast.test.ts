import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

/**
 * WCAG AA for the failure surfaces (UI audit J6-F13/J8-F11's rule, applied to A6's new ones).
 *
 * A failed job's reason, its Retry, the jobs drawer and the top bar's running count are drawn with
 * the skin's semantic tokens rather than raw palette shades — `text-rose-300` on the default light
 * skin's white rows was ~1.9:1, the reason text the whole fix exists to show. The markup tests next
 * to each component pin that the tokens are used; this pins that the tokens themselves reach 4.5:1
 * in both skins, so a palette edit cannot quietly undo it.
 */

const css = readFileSync(new URL('../index.css', import.meta.url), 'utf8')

/** The `--name: #hex` declarations of the first block opened by `selector`. */
function tokens(selector: string): Record<string, string> {
  const start = css.indexOf(`${selector} {`)
  if (start < 0) throw new Error(`no ${selector} block in index.css`)
  const body = css.slice(start, css.indexOf('}', start))
  const out: Record<string, string> = {}
  for (const m of body.matchAll(/--([a-z-]+):\s*(#[0-9a-fA-F]{6})\s*;/g)) out[m[1]] = m[2]
  return out
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** Text token on the surface it is drawn on — each pair a failure surface actually uses. */
const PAIRS: Array<[text: string, surface: string, where: string]> = [
  ['error', 'card', 'a failed row’s reason and Retry; a product’s last failure'],
  ['error', 'background', 'the same, on the page ground'],
  ['error', 'muted', 'a failed job in the jobs drawer (a muted list item)'],
  ['destructive-foreground', 'destructive', 'a job card’s failure box; the drawer’s load error'],
  ['primary-foreground', 'primary', 'the top bar’s running-jobs count'],
]

describe.each([
  ['Field Notes (light, the default)', '.light'],
  ['Graphite (dark)', ':root'],
])('%s', (_skin, selector) => {
  const t = tokens(selector)
  it.each(PAIRS)('--%s on --%s reaches 4.5:1 (%s)', (text, surface) => {
    expect(t[text], `--${text}`).toMatch(/^#/)
    expect(t[surface], `--${surface}`).toMatch(/^#/)
    expect(contrast(t[text], t[surface])).toBeGreaterThanOrEqual(4.5)
  })
})
