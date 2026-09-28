import { describe, expect, it } from 'vitest'
import { matchCommands, paletteCommands, shortcutLabel } from './commandPalette'

/**
 * The ⌘K palette is navigation-only (UI audit J8-F17: object search is Tier C). What this pins is
 * that it answers the words people type for a destination — "users", "tokens", "licence"
 * found nothing (J7-F20) — and that the pill advertising it says what it does and the shortcut
 * this machine uses.
 */
describe('palette destinations (J7-F20)', () => {
  it.each(['users', 'people', 'members', 'invite', 'licence', 'license', 'tokens'])(
    'an owner typing "%s" is offered Administration',
    (q) => {
      expect(matchCommands(paletteCommands(true), q).map((c) => c.label)).toContain('Administration')
    },
  )

  it('a member is not offered Administration, whatever they type', () => {
    expect(matchCommands(paletteCommands(false), 'users').map((c) => c.label)).not.toContain('Administration')
  })

  it('still finds a destination by its own name', () => {
    expect(matchCommands(paletteCommands(false), 'workbench').map((c) => c.to)).toEqual(['/oql'])
  })
})

describe('the palette shortcut label (J8-F17)', () => {
  it('is ⌘K on a Mac and Ctrl+K elsewhere', () => {
    expect(shortcutLabel('MacIntel')).toBe('⌘K')
    expect(shortcutLabel('Win32')).toBe('Ctrl+K')
    expect(shortcutLabel('Linux x86_64')).toBe('Ctrl+K')
    expect(shortcutLabel(undefined)).toBe('Ctrl+K')
  })
})
