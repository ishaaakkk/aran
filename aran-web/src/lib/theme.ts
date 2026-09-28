/**
 * Light/dark selection.
 *
 * This lived inside `IconRail`, which was fine while the rail was on every
 * authenticated screen. It no longer is: the administration area has its own shell
 * and does not mount the rail, so a hard reload at `/admin` came up dark no matter
 * what the operator had chosen. Theme is an application-wide concern, so it is
 * applied once at startup and the rail only toggles it.
 */

const STORAGE_KEY = 'aran.ui.mode'

export type ThemeMode = 'dark' | 'light'

/** The stored preference, defaulting to light — Field Notes is the system's default face; Graphite is its dark mode. Never throws — a blocked or full localStorage is not a reason to fail to render. */
export function storedTheme(): ThemeMode {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'dark' ? 'dark' : 'light'
  } catch {
    return 'light'
  }
}

/** Put the mode on `<html>` and remember it. Both classes are managed together so the two can never both be present. */
export function applyTheme(mode: ThemeMode): void {
  const root = document.documentElement
  root.classList.toggle('light', mode === 'light')
  root.classList.toggle('dark', mode === 'dark')
  try {
    localStorage.setItem(STORAGE_KEY, mode)
  } catch {
    // The class is already on; losing the preference across reloads is the whole cost.
  }
}

/** Called once at startup, before the first render, so no screen flashes the wrong theme. */
export function initTheme(): void {
  applyTheme(storedTheme())
}
