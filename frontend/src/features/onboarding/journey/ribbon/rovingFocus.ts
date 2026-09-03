import * as React from 'react'

/**
 * C-109 · the strip's own roving tab stop — CLAUDE.md's keyboard-navigation
 * line applies to this ribbon exactly as it did to the phase-1 one, and a
 * journey of 8 steps (the seeded "Standard SaaS Onboarding" default) is the
 * identical eight-Tab-presses problem `components/ribbon/rovingFocus.ts` was
 * written to avoid. Re-implemented rather than imported — see this
 * directory's README for why nothing in `components/ribbon/` is a dependency
 * of this task.
 *
 * One element in the tab order, `←`/`→` between tiles, `Home`/`End` to the
 * ends, wrapping both ways. Focus moves the tab stop; it does not select —
 * there is no filtered panel below this strip yet for a moved stop to affect
 * (C-110 owns that page), but the same split keeps a later click-to-filter
 * addition from having to relitigate this file.
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

export interface RovingItemProps {
  tabIndex: number
  ref: (node: HTMLElement | null) => void
  onFocus: () => void
}

export interface RovingFocus {
  activeIndex: number
  onKeyDown: (event: React.KeyboardEvent) => void
  itemProps: (index: number) => RovingItemProps
}

/** `preferredIndex` holds the tab stop until the reader moves it themselves —
 * the strip passes the current step's index, so tabbing in lands on where the
 * journey actually is rather than on step 1. */
export function useRovingFocus(count: number, preferredIndex = 0): RovingFocus {
  const nodes = React.useRef<(HTMLElement | null)[]>([])
  const [chosen, setChosen] = React.useState<number | null>(null)
  const handlers = React.useRef(new Map<number, Pick<RovingItemProps, 'ref' | 'onFocus'>>())

  const desired = chosen ?? preferredIndex
  const activeIndex = count <= 0 ? 0 : Math.min(Math.max(desired, 0), count - 1)

  const onKeyDown = (event: React.KeyboardEvent) => {
    const next = nextFocusIndex(event.key, activeIndex, count)
    if (next == null) return
    event.preventDefault()
    setChosen(next)
    nodes.current[next]?.focus()
  }

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
