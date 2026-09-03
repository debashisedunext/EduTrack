import { describe, expect, it } from 'vitest'

import { nextFocusIndex } from './rovingFocus'

/**
 * C-109 · the same index maths `components/ribbon/rovingFocus.test.ts`
 * pins for the ticket ribbon, re-proved here rather than assumed shared,
 * since this file is a fresh implementation and not an import.
 */
describe('nextFocusIndex', () => {
  it('moves right and left one step at a time', () => {
    expect(nextFocusIndex('ArrowRight', 2, 8)).toBe(3)
    expect(nextFocusIndex('ArrowLeft', 2, 8)).toBe(1)
  })

  it('wraps at both ends', () => {
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

  it('does not handle the vertical arrows', () => {
    expect(nextFocusIndex('ArrowUp', 2, 8)).toBeNull()
    expect(nextFocusIndex('ArrowDown', 2, 8)).toBeNull()
  })

  it('handles a single-step journey by staying put', () => {
    expect(nextFocusIndex('ArrowRight', 0, 1)).toBe(0)
    expect(nextFocusIndex('ArrowLeft', 0, 1)).toBe(0)
  })

  it('returns null for an empty journey rather than a negative index', () => {
    expect(nextFocusIndex('ArrowRight', 0, 0)).toBeNull()
    expect(nextFocusIndex('Home', 0, 0)).toBeNull()
  })

  it('treats an out-of-range current position as the start', () => {
    expect(nextFocusIndex('ArrowRight', 99, 8)).toBe(1)
    expect(nextFocusIndex('ArrowLeft', -3, 8)).toBe(7)
  })
})
