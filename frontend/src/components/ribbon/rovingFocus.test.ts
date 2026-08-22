import { describe, expect, it } from 'vitest'

import { nextFocusIndex } from './rovingFocus'

/**
 * B-052 · the index maths behind §4A.3's "fully keyboard-navigable" ribbon,
 * tested without a DOM.
 *
 * `RibbonStrip.test.tsx` drives the same behaviour through real key presses on
 * real tiles — that is the test that would catch a hook wired to the wrong
 * element. This is the one that catches an off-by-one at the wrap, which is
 * where roving tab stops actually break, and it does so in a millisecond
 * rather than in a jsdom render.
 */
describe('nextFocusIndex · B-052', () => {
  it('moves right and left one segment at a time', () => {
    expect(nextFocusIndex('ArrowRight', 2, 8)).toBe(3)
    expect(nextFocusIndex('ArrowLeft', 2, 8)).toBe(1)
  })

  it('wraps at both ends, the same way TicketDetailTabs does', () => {
    expect(nextFocusIndex('ArrowRight', 7, 8)).toBe(0)
    expect(nextFocusIndex('ArrowLeft', 0, 8)).toBe(7)
  })

  it('jumps to the ends on Home and End', () => {
    expect(nextFocusIndex('Home', 5, 8)).toBe(0)
    expect(nextFocusIndex('End', 5, 8)).toBe(7)
  })

  it('leaves every other key alone, so Tab still escapes the strip', () => {
    for (const key of ['Tab', 'Enter', ' ', 'a', 'Escape', 'PageDown']) {
      expect(nextFocusIndex(key, 2, 8)).toBeNull()
    }
  })

  /**
   * The vertical arrows are the page's. A ribbon that swallowed them would
   * trap a keyboard reader inside a widget they cannot scroll past — the
   * failure mode that makes a carousel unusable, on a strip that is a row.
   */
  it('does not handle the vertical arrows', () => {
    expect(nextFocusIndex('ArrowUp', 2, 8)).toBeNull()
    expect(nextFocusIndex('ArrowDown', 2, 8)).toBeNull()
  })

  it('handles a single-segment strip by staying put', () => {
    expect(nextFocusIndex('ArrowRight', 0, 1)).toBe(0)
    expect(nextFocusIndex('ArrowLeft', 0, 1)).toBe(0)
    expect(nextFocusIndex('End', 0, 1)).toBe(0)
  })

  it('returns null for an empty strip rather than a negative index', () => {
    expect(nextFocusIndex('ArrowRight', 0, 0)).toBeNull()
    expect(nextFocusIndex('Home', 0, 0)).toBeNull()
    expect(nextFocusIndex('End', 0, 0)).toBeNull()
  })

  /**
   * A handoff (C-045) or a `stage.changed` frame (D-058) re-cuts the segment
   * list while the page is open, so an index outliving its list is ordinary
   * rather than a bug. It is read as 0, never as a crash and never as an index
   * off the end.
   */
  it('treats an out-of-range current position as the start', () => {
    expect(nextFocusIndex('ArrowRight', 99, 8)).toBe(1)
    expect(nextFocusIndex('ArrowLeft', -3, 8)).toBe(7)
    expect(nextFocusIndex('ArrowRight', -1, 8)).toBe(1)
  })
})
