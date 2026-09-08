import * as React from 'react'

/**
 * C-110 · Onboarding-Module-Plan.md §9's accordion UX rule, made mechanical:
 *
 * > **expanding/collapsing or selecting a step never scrolls the page —
 * > scroll position is preserved on all same-page interactions.**
 *
 * ## Why "preserve the scroll position" is not the same as "do nothing"
 *
 * The browser already keeps `window.scrollY` across a layout change. That is
 * exactly the problem. Collapse an accordion above the one you are reading and
 * the page keeps its scroll *offset* while the content under it moves up by
 * the height of what just closed — so the thing you were looking at jumps off
 * the screen, and the reader's own click is what did it. On a client with six
 * journeys that is the difference between a page that feels stable and one
 * nobody trusts to keep their place.
 *
 * What has to be preserved is the position of **the row the user acted on**,
 * not the scroll number. So: measure that row's distance from the top of the
 * viewport before React re-renders, measure it again after layout, and scroll
 * by the difference. The row stays exactly where it was under the cursor and
 * everything else moves around it.
 *
 * ## Layout effect, not effect
 *
 * `useLayoutEffect` runs after the DOM is updated and **before the browser
 * paints**. In `useEffect` the correction lands a frame late, which is a
 * visible jump-and-snap rather than no jump at all.
 *
 * ## What it deliberately does not do
 *
 * No smooth scrolling: this is a correction that should be invisible, and an
 * animated one advertises itself. Nothing here reads `prefers-reduced-motion`
 * for the same reason — an instantaneous adjustment is already what that
 * setting asks for.
 */
export interface AnchoredToggle {
  /** Call in the click handler, *before* the state change, with the element
   * that must stay put — normally the accordion header itself. */
  anchor: (element: HTMLElement | null) => void
}

export function useAnchoredToggle(): AnchoredToggle {
  const anchored = React.useRef<{ element: HTMLElement; top: number } | null>(null)

  const anchor = React.useCallback((element: HTMLElement | null) => {
    if (!element) return
    anchored.current = { element, top: element.getBoundingClientRect().top }
  }, [])

  React.useLayoutEffect(() => {
    const pending = anchored.current
    if (!pending) return
    anchored.current = null

    // The element can have been unmounted by the very interaction that
    // anchored it (a filter that removes the row, say). Nothing to correct
    // against, and `getBoundingClientRect` on a detached node reads 0 — which
    // would scroll the page to an arbitrary place rather than leave it alone.
    if (!pending.element.isConnected) return

    const delta = pending.element.getBoundingClientRect().top - pending.top
    if (Math.abs(delta) < 1) return
    window.scrollBy(0, delta)
  })

  return { anchor }
}
