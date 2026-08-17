import { readFileSync } from 'node:fs'
import { beforeEach, describe, expect, it } from 'vitest'

import {
  DARK_CLASS,
  THEME_STORAGE_KEY,
  applyTheme,
  storedTheme,
  useThemeStore,
} from './themeStore'

/**
 * D-15 · the theme is one piece of state with one writer.
 *
 * The interesting cases are not "does the toggle toggle". They are the three
 * ways a theme switch goes wrong in practice: the class and the store
 * disagreeing after a reload, storage throwing in private mode, and the flash
 * of light theme returning because the inline script in index.html drifted
 * from the constants here.
 */

beforeEach(() => {
  window.localStorage.clear()
  document.documentElement.classList.remove(DARK_CLASS)
  useThemeStore.setState({ theme: 'light' })
})

describe('the theme store', () => {
  it('defaults to light, so nothing changes for anybody who does nothing', () => {
    expect(storedTheme()).toBe('light')
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(false)
  })

  it('puts the class on <html>, not on a React element', () => {
    // Modals and toasts portal to document.body, outside the React tree. A
    // provider that themed its own wrapper would leave every portalled surface
    // on the light tokens.
    useThemeStore.getState().setTheme('dark')
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(true)
  })

  it('persists the choice, and persists light as a choice too', () => {
    useThemeStore.getState().setTheme('dark')
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')

    // Written rather than removed. Absence has to keep meaning "never chose",
    // which is what a later 'system' option would resolve differently.
    useThemeStore.getState().setTheme('light')
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
  })

  it('toggles from whatever is current', () => {
    useThemeStore.getState().toggle()
    expect(useThemeStore.getState().theme).toBe('dark')
    useThemeStore.getState().toggle()
    expect(useThemeStore.getState().theme).toBe('light')
  })

  /**
   * The store reads storage at module load so it agrees with what the inline
   * script already painted. Were it hardcoded to 'light', a returning dark user
   * would get a dark page above a store claiming light — and the first press of
   * the toggle would "switch to dark" while already dark, doing nothing
   * visible. Asserted through `storedTheme`, which is the part that decides it;
   * the module-level read itself runs once per process and cannot be re-run.
   */
  it('reads a stored dark preference rather than assuming light', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    expect(storedTheme()).toBe('dark')
  })

  it('ignores a corrupted stored value instead of applying it', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'DARK-ish')
    expect(storedTheme()).toBe('light')
  })

  /**
   * Private mode and hardened browsers throw on `localStorage` access. A theme
   * preference is not worth breaking a page load over — and the click must
   * still change the colours for this page even when it cannot be remembered.
   */
  it('survives storage being denied, and still applies the theme', () => {
    const denied = () => {
      throw new DOMException('denied')
    }
    const original = window.localStorage.getItem
    const originalSet = window.localStorage.setItem
    window.localStorage.getItem = denied
    window.localStorage.setItem = denied

    try {
      expect(storedTheme()).toBe('light')
      expect(() => useThemeStore.getState().setTheme('dark')).not.toThrow()
      expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(true)
      expect(useThemeStore.getState().theme).toBe('dark')
    } finally {
      window.localStorage.getItem = original
      window.localStorage.setItem = originalSet
    }
  })

  it('applyTheme removes the class as well as adding it', () => {
    applyTheme('dark')
    applyTheme('light')
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(false)
  })
})

/**
 * The duplicated rule, pinned.
 *
 * index.html carries an inline copy of "read the key, set the class" so the
 * theme is applied before the first paint. That is two statements of one
 * contract, and the failure of them drifting is not an error — it is the flash
 * of light theme coming back for dark users only, on page load only, which is
 * the hardest class of bug to notice and the easiest to dismiss as a fluke.
 */
describe('the pre-paint script in index.html', () => {
  const html = readFileSync('index.html', 'utf8')

  it('uses the same storage key this module exports', () => {
    expect(html).toContain(`'${THEME_STORAGE_KEY}'`)
  })

  it('uses the same class name this module exports', () => {
    expect(html).toContain(`classList.add('${DARK_CLASS}')`)
  })

  it('is inline and synchronous, or it runs after the paint it exists to beat', () => {
    const tag = html.match(/<script(?![^>]*\bsrc=)[^>]*>/)
    expect(tag, 'no inline <script> in index.html').not.toBeNull()
    expect(tag![0]).not.toContain('defer')
    expect(tag![0]).not.toContain('async')
  })
})
