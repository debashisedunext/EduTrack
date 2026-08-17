import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

/**
 * D-15 · every token the light theme defines, the dark theme defines too.
 *
 * ## The failure this exists to make impossible
 *
 * A missing token does not error and does not fall back to something sensible.
 * It **inherits the light value**, because `.dark` only overrides what it
 * mentions. So forgetting one leaves a single white card, or one line of
 * near-black text, in an otherwise dark page — and it appears on whichever
 * screen happens to use that token, which may be a screen nobody opens during
 * review. `--ribbon-pending-bg` was `#FFFFFF`: miss it and the Workflow Ribbon
 * grows a white block on a dark ticket page.
 *
 * There are 45 tokens. That is exactly the size where a manual check reads as
 * done and is not.
 *
 * ## Parsed from the CSS rather than measured in a browser
 *
 * A jsdom test cannot resolve a custom property against a stylesheet Vite has
 * not processed, and standing up a real browser for a set-comparison would cost
 * a Playwright dependency to answer a question two regexes answer. What this
 * cannot check is that the *values* are right — that is contrast, and it is
 * asserted in the file's own comments with measured ratios and by review.
 */

const CSS = readFileSync('src/styles/tokens.css', 'utf8')

/** The body of a rule, by selector. Non-greedy to the first closing brace. */
function block(selector: string): string {
  const match = CSS.match(new RegExp(`${selector}\\s*\\{([\\s\\S]*?)\\n\\}`))
  if (!match) throw new Error(`no ${selector} block in tokens.css`)
  return match[1]
}

/**
 * Custom-property names declared in a block.
 *
 * `color-scheme` is excluded deliberately — it is a real CSS property rather
 * than a token, so it is not part of the parity contract even though both
 * blocks happen to set it.
 */
function tokens(selector: string): Set<string> {
  const names = block(selector).matchAll(/^\s*(--[a-z0-9-]+)\s*:/gm)
  return new Set([...names].map((m) => m[1]))
}

describe('the dark theme covers the light theme', () => {
  const light = tokens(':root')
  const dark = tokens('\\.dark')

  it('defines a meaningful number of tokens, so an empty parse cannot pass', () => {
    // Both sides being empty would satisfy every set comparison below. This is
    // the assertion that stops a broken regex reading as a green build.
    expect(light.size).toBeGreaterThan(40)
  })

  it('leaves no light token undefined in dark', () => {
    const missing = [...light].filter((t) => !dark.has(t))
    expect(
      missing,
      'these tokens keep their light value under .dark, which puts a light '
        + 'element in a dark page — add them to the .dark block in tokens.css',
    ).toEqual([])
  })

  it('defines no dark token the light theme does not have', () => {
    // The other direction matters too: a token only in `.dark` is one no light
    // screen can resolve, so it renders as an invalid value — which computes to
    // nothing rather than to an error.
    const orphans = [...dark].filter((t) => !light.has(t))
    expect(orphans, 'defined only under .dark, so unresolvable in the light theme').toEqual([])
  })

  it('states a colour-scheme in both, so native controls follow the theme', () => {
    // Scrollbars, date pickers and the caret are drawn by the browser, not by
    // us. Without this a dark app renders light scrollbars — the give-away that
    // a dark theme was done in CSS only.
    expect(block(':root')).toMatch(/color-scheme:\s*light/)
    expect(block('\\.dark')).toMatch(/color-scheme:\s*dark/)
  })

  it('keeps the chart series in one order across both themes', () => {
    // Charts use the series by index, never by name, so re-ordering the palette
            // in one theme silently recolours every chart when the theme flips —
    // and a reader comparing two screenshots would conclude the data changed.
    const order = (selector: string) =>
      [...block(selector).matchAll(/^\s*(--chart-\d)\s*:/gm)].map((m) => m[1])

    expect(order('\\.dark')).toEqual(order(':root'))
  })

  it('does not touch the light theme, whose values C-002 measured', () => {
    // The deviation is additive. If a light value ever changes it should be a
    // deliberate C-002 decision, not a side effect of adding a dark theme, so
    // the four most load-bearing are pinned to the blueprint's own hex.
    const root = block(':root')
    expect(root).toMatch(/--bg-app:\s*#F7F8FC/)
    expect(root).toMatch(/--bg-surface:\s*#FFFFFF/)
    expect(root).toMatch(/--primary:\s*#4F46E5/)
    expect(root).toMatch(/--text-primary:\s*#111827/)
  })
})
