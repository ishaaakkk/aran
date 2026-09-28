/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'

/**
 * WCAG AA for the small text this package introduced (the owner's standing rule for the UI
 * audit sessions): 4.5:1 for text under 18 px, in both skins. The success toast was the success
 * token on a white card (4.33:1 in Field Notes) and the demo badge the warning token on its own
 * 10 % tint (about 4.1:1) — both a little short, at 11–12 px. The pairs are read from the markup
 * and the token values from index.css, so a class change or a token change that drops a pair
 * below AA fails here rather than in a contrast audit.
 */

vi.mock('../../services/keycloak', () => ({
  tokenParsed: () => ({ preferred_username: 'demo' }),
  ensureFreshToken: async () => 'demo-token',
}))

const { Toast } = await import('./Toast')
const { SimulationBadge } = await import('../SimulationBadge')

afterEach(() => {
  vi.unstubAllEnvs()
})

const css = readFileSync(join(process.cwd(), 'src', 'index.css'), 'utf8')

function tokens(block: RegExp): Record<string, string> {
  const body = css.match(block)?.[1] ?? ''
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*(#[0-9A-Fa-f]{6})\s*;/g)].map((m) => [m[1], m[2]]))
}

const SKINS: Array<[name: string, palette: Record<string, string>]> = [
  ['Graphite (dark)', tokens(/:root\s*\{([^}]*)\}/)],
  ['Field Notes (light)', tokens(/\.light\s*\{([^}]*)\}/)],
]

type Rgb = [number, number, number]

const rgb = (hex: string): Rgb => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16)) as Rgb

const luminance = ([r, g, b]: Rgb): number => {
  const lin = (c: number) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
}

const ratio = (a: Rgb, b: Rgb): number => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** `bg-token/NN` composited over an opaque surface, as the browser paints it. */
const over = (fg: Rgb, alpha: number, bg: Rgb): Rgb => fg.map((c, i) => c * alpha + bg[i] * (1 - alpha)) as Rgb

/** The class list of the element carrying `data-testid`. */
function classesOf(html: string, testId: string): string[] {
  const tag = html.match(new RegExp(`<[a-z]+\\b[^>]*data-testid="${testId}"[^>]*>`))?.[0] ?? ''
  return (tag.match(/class="([^"]*)"/)?.[1] ?? '').split(/\s+/)
}

/**
 * Every (text, painted background) pair an element's classes produce in one skin. A translucent
 * or absent background is painted over each surface the element is used on (card, page).
 */
function pairs(classes: string[], palette: Record<string, string>): Array<[Rgb, Rgb]> {
  const text = classes.map((c) => c.match(/^text-([a-z-]+)$/)?.[1]).find((t) => t && palette[t])
  expect(text, `a text-<token> class among: ${classes.join(' ')}`).toBeDefined()
  const bg = classes.map((c) => c.match(/^bg-([a-z-]+)(?:\/(\d+))?$/)).find((m) => m && palette[m[1]])
  const surfaces = [rgb(palette.card), rgb(palette.background)]
  const painted = !bg
    ? surfaces
    : bg[2] === undefined
      ? [rgb(palette[bg[1]])]
      : surfaces.map((s) => over(rgb(palette[bg[1]]), Number(bg[2]) / 100, s))
  return painted.map((p) => [rgb(palette[text!]), p])
}

function render(what: 'success toast' | 'error toast' | 'simulation badge'): [string, string] {
  if (what === 'simulation badge') {
    vi.stubEnv('VITE_DEMO', 'true')
    return [renderToStaticMarkup(<SimulationBadge />), 'demo-simulation-badge']
  }
  const type = what === 'success toast' ? 'success' : 'error'
  return [renderToStaticMarkup(<Toast toast={{ message: 'x', type }} onDismiss={() => {}} testId="t" />), 't']
}

describe('small text meets WCAG AA (4.5:1) in both skins', () => {
  it('reads both skins from index.css', () => {
    for (const [, palette] of SKINS) {
      for (const token of ['card', 'background', 'foreground', 'success', 'warning', 'destructive']) {
        expect(palette[token]).toMatch(/^#[0-9A-Fa-f]{6}$/)
      }
    }
  })

  it.each(['success toast', 'error toast', 'simulation badge'] as const)('the %s', (what) => {
    const [html, testId] = render(what)
    const classes = classesOf(html, testId)
    for (const [skin, palette] of SKINS) {
      for (const [fg, bg] of pairs(classes, palette)) {
        expect(ratio(fg, bg), `${what} in ${skin}`).toBeGreaterThanOrEqual(4.5)
      }
    }
  })
})
