import * as React from 'react'

/**
 * A-055 · the animated count-up on a KPI card.
 *
 * <h2>It stops at the number, and it respects a preference</h2>
 *
 * `prefers-reduced-motion` is honoured by returning the final value
 * immediately. CLAUDE.md makes accessibility non-optional, and a counter
 * spinning through four digits is exactly the vestibular trigger that media
 * query exists for. This is not a nicety that degrades — for a reader who has
 * asked for stillness, the animation simply never happens.
 *
 * <h2>Why requestAnimationFrame and not a CSS transition</h2>
 *
 * The value is text, not a style. A transition can move a number's position or
 * colour but cannot interpolate the glyphs, so the count has to be driven in
 * JavaScript — and driven from the frame clock rather than `setInterval`, which
 * drifts under load and leaves the last frame short of the target.
 *
 * @param target the final value
 * @param durationMs deliberately short. §12.1's motion scale tops out well
 *        below a second; a KPI that takes two seconds to become readable is
 *        slower than no animation at all.
 */
export function useCountUp(target: number, durationMs = 450): number {
  const [value, setValue] = React.useState(target)
  const previous = React.useRef(target)

  React.useEffect(() => {
    const reduced =
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches

    const from = previous.current
    previous.current = target

    if (reduced || from === target || durationMs <= 0) {
      setValue(target)
      return
    }

    let frame = 0
    let start: number | null = null

    const tick = (now: number) => {
      if (start === null) {
        start = now
      }
      const elapsed = now - start
      const progress = Math.min(elapsed / durationMs, 1)
      // Ease-out: the number arrives decisively rather than creeping the last
      // few units, which is what makes a linear count-up feel broken.
      const eased = 1 - Math.pow(1 - progress, 3)
      setValue(Math.round(from + (target - from) * eased))

      if (progress < 1) {
        frame = window.requestAnimationFrame(tick)
      } else {
        // Land exactly on the target. Rounding an eased fraction can stop a
        // unit short, and a card reading 1,283 when the list shows 1,284 is
        // the kind of discrepancy nobody reports and everybody notices.
        setValue(target)
      }
    }

    frame = window.requestAnimationFrame(tick)
    return () => window.cancelAnimationFrame(frame)
  }, [target, durationMs])

  return value
}
