import * as React from 'react'

/**
 * B-052 · the roving tab stop behind blueprint §4A.3's "fully keyboard-
 * navigable" ribbon.
 *
 * Eight segments should be **one** stop on the way down a ticket detail page,
 * not eight. §4A.3's own list puts the ribbon beside History, Effort and Chat,
 * and a reader who has to press Tab eight times to get past the journey before
 * reaching the tabs below has a ribbon that costs more than it tells them. So
 * the strip is a composite widget in the APG sense: one element in the tab
 * order, arrows to move inside it, `Home`/`End` to the ends.
 *
 * The keys are `TicketDetailTabs`' and the rich-text toolbar's, deliberately —
 * both already ship in this codebase, both wrap at the ends, and a third set of
 * arrow-key semantics on the same screen would be the accessibility problem
 * rather than the fix.
 *
 * ## Focus does not select, and that is the one place this differs from the tabs
 *
 * `TicketDetailTabs` selects on focus, and says why: every panel is already
 * loaded from the one `/full` call, so moving costs nothing. The ribbon's
 * selection is not free — C-052 filters History, Effort and (once `D-047`
 * lands) Chat down to the focused stage and iteration, so arrowing across
 * eight stages would refilter three panels eight times, and a keyboard user
 * would be unable to *read* the ribbon without changing what is under it.
 * Activation is therefore explicit: `Enter` or `Space` on the tile, which is
 * what `RibbonSegment`'s `<button>` already does.
 *
 * ## Split pure from stateful for the same reason `segmentState.ts` is
 *
 * `nextFocusIndex` is where the wrapping is either right or wrong, and it is
 * testable without a DOM, without a fake timer and without `userEvent`.
 * `useRovingFocus` is the ten lines of refs around it.
 */

/**
 * Which segment the keyboard should move to, or `null` for a key this strip
 * does not handle — the caller must not `preventDefault` on those, or a strip
 * would swallow Tab, typing and the browser's own find.
 *
 * Only the horizontal pair. `ArrowUp`/`ArrowDown` are left to the page: the
 * ribbon is a row, and stealing the vertical arrows would trap a keyboard user
 * inside a widget they cannot scroll past.
 *
 * `current` outside `[0, count)` is treated as 0 rather than as an error — the
 * segment list is re-fetched by C-045's handoff and by D-058's `stage.changed`
 * while the page is open, so an index outliving its list is ordinary.
 */
export function nextFocusIndex(key: string, current: number, count: number): number | null {
  if (count <= 0) return null
  const from = current >= 0 && current < count ? current : 0

  switch (key) {
    case 'ArrowRight':
      return (from + 1) % count
    case 'ArrowLeft':
      return (from - 1 + count) % count
    case 'Home':
      return 0
    case 'End':
      return count - 1
    default:
      return null
  }
}

/** What one item spreads onto its focusable element. */
export interface RovingItemProps {
  /** `0` for the single tab stop, `-1` for every other item. */
  tabIndex: number
  ref: (node: HTMLElement | null) => void
  /** Moves the tab stop to an item focused by click, or by anything else. */
  onFocus: () => void
}

export interface RovingFocus {
  /** The item currently holding the strip's tab stop. */
  activeIndex: number
  /** Goes on the container, not on each item — one listener, not eight. */
  onKeyDown: (event: React.KeyboardEvent) => void
  itemProps: (index: number) => RovingItemProps
}

/**
 * `preferredIndex` is where the tab stop sits **until the reader moves it** —
 * the ribbon passes the current segment, so tabbing into the strip lands on
 * the stage the ticket is actually in rather than on a stage finished four
 * days ago.
 *
 * It is a preference and not a controlled value on purpose. The ribbon
 * re-renders on every `stage.changed` frame D-058 pushes, and a live handoff
 * moving `CURRENT` must not yank the tab stop out from under someone who has
 * arrowed somewhere to read it. So the first user move latches, and after that
 * the strip's own position wins.
 */
export function useRovingFocus(count: number, preferredIndex = 0): RovingFocus {
  const nodes = React.useRef<(HTMLElement | null)[]>([])
  const [chosen, setChosen] = React.useState<number | null>(null)
  const handlers = React.useRef(new Map<number, Pick<RovingItemProps, 'ref' | 'onFocus'>>())

  const desired = chosen ?? preferredIndex
  // Clamped every render rather than in an effect: a segment list that shrank
  // would otherwise leave the tab stop past the end for one paint, and a strip
  // with no element at `tabIndex={0}` is a strip Tab skips entirely.
  const activeIndex = count <= 0 ? 0 : Math.min(Math.max(desired, 0), count - 1)

  const onKeyDown = (event: React.KeyboardEvent) => {
    const next = nextFocusIndex(event.key, activeIndex, count)
    if (next == null) return
    event.preventDefault()
    setChosen(next)
    // Focused off the ref rather than waiting for the re-render, the same way
    // `TicketDetailTabs.move` does: `setState` is async and the element that
    // has to receive focus is already on screen.
    nodes.current[next]?.focus()
  }

  /*
   * The two callbacks are cached per index and never rebuilt, which is not a
   * micro-optimisation.
   *
   * A fresh `ref` closure on every render makes React detach and reattach the node —
   * `ref(null)` then `ref(node)` — on every one of the eight tiles, and each
   * tile's ref is composed by Radix's `Slot` with `TooltipTrigger`'s own, which
   * is the Popper **anchor**. So an unstable ref does not just cost a map
   * write: it tears down and re-anchors eight tooltips on every render of a
   * strip that re-renders on selection, on focus, and on every `stage.changed`
   * frame D-058 pushes. Caching them keeps the identity stable for the life of
   * the strip, which is what `React.useCallback` would do if the index were not
   * an argument.
   */
  const itemProps = (index: number): RovingItemProps => {
    let cached = handlers.current.get(index)
    if (!cached) {
      cached = {
        ref: (node: HTMLElement | null) => {
          nodes.current[index] = node
        },
        onFocus: () => setChosen(index),
      }
      handlers.current.set(index, cached)
    }

    return { tabIndex: index === activeIndex ? 0 : -1, ref: cached.ref, onFocus: cached.onFocus }
  }

  return { activeIndex, onKeyDown, itemProps }
}
