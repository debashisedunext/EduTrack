import { create } from 'zustand'

/**
 * D-15 · which theme is showing, and the only thing that writes the class.
 *
 * ## Manual only — the OS setting is deliberately ignored
 *
 * No `prefers-color-scheme`. Light stays the default for everybody who does
 * nothing, exactly as today, and dark is something a person turns on.
 *
 * The reason is not effort, it is that following the OS *as well* would give
 * one piece of state two inputs. A user who chose light on a dark-set machine
 * would have their choice honoured or overridden depending on CSS rule order,
 * and "why is it dark again this morning" is a bug report nobody can reproduce
 * from a screenshot. One writer, one source of truth. Adding system-follow
 * later means adding a third state (`'system'`) to `Theme` below and resolving
 * it here — the shape leaves room for that without a rewrite.
 *
 * ## Why the class lives on <html> and not on a React element
 *
 * `background` and `color` are set on `body` (tokens.css), and a modal or a
 * toast portals to `document.body` — outside the React tree. A provider that
 * put the class on its own wrapper would leave every portalled surface reading
 * the light tokens, which is exactly the set of components hardest to notice.
 *
 * ## The flash of light theme, and why index.html has a copy of this logic
 *
 * React runs after first paint. If the class were applied only here, a user
 * whose choice is dark would see a white page for a frame or two on every
 * load — the worse kind of bug because it is intermittent by nature and
 * invisible on a fast machine. `index.html` carries a small inline script that
 * reads the same key and sets the same class before the bundle is fetched.
 *
 * That is a duplicated rule, so it is worth being explicit about the contract
 * it shares with this file: **the storage key and the class name**. Both are
 * exported below and `themeStore.test.ts` asserts index.html still uses them,
 * so renaming either here fails a test rather than silently reintroducing the
 * flash.
 */

export type Theme = 'light' | 'dark'

/** Shared with the inline script in index.html — see the note above. */
export const THEME_STORAGE_KEY = 'edutrack-theme'
export const DARK_CLASS = 'dark'

/**
 * Light unless dark was explicitly stored.
 *
 * Anything unreadable — private mode with storage denied, a corrupted value, a
 * test environment with no `window` — resolves to light rather than throwing.
 * A theme preference is not worth breaking a page load over, and the failure
 * mode of guessing wrong is a page in the wrong colours, which the user can
 * fix with one click.
 */
export function storedTheme(): Theme {
  try {
    return window.localStorage.getItem(THEME_STORAGE_KEY) === 'dark' ? 'dark' : 'light'
  } catch {
    return 'light'
  }
}

/**
 * Put the theme on the document, and nowhere else.
 *
 * Exported for the one caller that runs before the store exists (bootstrap in
 * `main.tsx`) and for tests. Everything else goes through `setTheme`.
 */
export function applyTheme(theme: Theme): void {
  if (typeof document === 'undefined') return
  document.documentElement.classList.toggle(DARK_CLASS, theme === 'dark')
}

interface ThemeState {
  theme: Theme
  setTheme: (theme: Theme) => void
  toggle: () => void
}

export const useThemeStore = create<ThemeState>((set, get) => ({
  // Read at module load so the store agrees with what the inline script already
  // painted. Initialising to 'light' unconditionally would leave a dark page
  // with a store claiming light — and the toggle's first press would then
  // "switch to dark" while already dark, doing nothing visible.
  theme: typeof window === 'undefined' ? 'light' : storedTheme(),

  setTheme: (theme) => {
    applyTheme(theme)
    try {
      // Written even for 'light', rather than removing the key. An explicit
      // "light" is a decision and has to survive a reload; treating it as
      // absence would make it indistinguishable from never having chosen, and
      // would matter the moment a 'system' option is added.
      window.localStorage.setItem(THEME_STORAGE_KEY, theme)
    } catch {
      // Storage denied — the theme still applies for this page. Failing the
      // click because the choice cannot be remembered would be worse than
      // remembering nothing.
    }
    set({ theme })
  },

  toggle: () => get().setTheme(get().theme === 'dark' ? 'light' : 'dark'),
}))
