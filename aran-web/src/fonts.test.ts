/// <reference types="node" />
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * UI audit J8-F12: the page loaded its three font families from Google. A network that drops
 * Google silently held first paint for 20.5 s, and every page load sent each user's IP address to
 * a third party — from a self-hosted product whose pitch is that data stays inside your
 * infrastructure. The families are now served from the bundle.
 */
const ROOT = process.cwd()
const html = readFileSync(join(ROOT, 'index.html'), 'utf8')
const indexCss = readFileSync(join(ROOT, 'src', 'index.css'), 'utf8')
const fontsCssPath = join(ROOT, 'src', 'fonts.css')

describe('self-hosted fonts (J8-F12)', () => {
  it('index.html fetches nothing from Google', () => {
    expect(html).not.toMatch(/fonts\.googleapis\.com|fonts\.gstatic\.com/)
  })

  it('every family the theme names has a local @font-face, with swap, and its files exist', () => {
    expect(existsSync(fontsCssPath)).toBe(true)
    const fontsCss = readFileSync(fontsCssPath, 'utf8')
    expect(indexCss).toContain('@import "./fonts.css"')
    for (const family of ['Instrument Sans', 'Bricolage Grotesque', 'JetBrains Mono']) {
      expect(indexCss, `the theme no longer names ${family}`).toContain(`"${family}"`)
      const faces = fontsCss.split('@font-face').filter((f) => f.includes(`'${family}'`))
      expect(faces.length, `${family} has no @font-face`).toBeGreaterThan(0)
      for (const face of faces) {
        expect(face).toContain('font-display: swap')
        for (const m of face.matchAll(/url\(([^)]+)\)/g)) {
          const file = resolve(dirname(fontsCssPath), m[1].replace(/['"]/g, ''))
          expect(existsSync(file), `${file} is missing`).toBe(true)
        }
      }
    }
  })

  it('ships the fonts\' licence (SIL OFL 1.1) beside them', () => {
    const fontsCss = readFileSync(fontsCssPath, 'utf8')
    const dirs = new Set([...fontsCss.matchAll(/url\(([^)]+)\)/g)].map((m) => dirname(resolve(dirname(fontsCssPath), m[1].replace(/['"]/g, '')))))
    for (const dir of dirs) {
      expect(existsSync(join(dir, 'OFL.txt')), `${dir} has no OFL.txt`).toBe(true)
    }
  })
})
