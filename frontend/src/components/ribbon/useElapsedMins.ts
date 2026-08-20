import { useEffect, useState } from 'react'

/**
 * B-050 · §4A.3's "elapsed timer running" on the current segment.
 *
 * ## This number is wall-clock, and every other duration on the ribbon is not
 *
 * `RibbonSegment.durationMins` is **working** minutes — the contract says so on
 * the field, and the mock computes it through `workingMinutes()` when a stage
 * is sealed. The working calendar lives on the server (B-024: org holidays and
 * per-resource leave), so the browser cannot compute a working-hours figure for
 * a stage that has not been handed over yet, and a stage that has not been
 * handed over is exactly the one this timer is for.
 *
 * So the two numbers are in different units and the tile has to say which is
 * which. A ticket handed to Deployment at 18:00 on Friday reads `62h` here on
 * Monday morning against the ~8h the server will record when it seals — and
 * printing that under the same "in stage" caption as the sealed segments beside
 * it is the exact confusion CLAUDE.md's working-calendar rule exists to
 * prevent. `RibbonSegment.tsx` labels it `elapsed` rather than `in stage`, and
 * spells the difference out in the tooltip and the accessible name.
 *
 * **Prefer the server's figure whenever there is one.** The caller only reaches
 * for this hook when `durationMins` is null. If the ribbon endpoint ever starts
 * serving a live working-minutes duration for the open hop, that branch stops
 * being taken and nothing here needs changing.
 *
 * ## Ticking
 *
 * Every 30 s, because the display granularity is a minute and half a minute of
 * lag is not visible. Not every second: one interval per ribbon is fine, sixty
 * times the re-renders for a digit that does not change is not.
 *
 * `active` is a parameter rather than a caller-side `if`, because a hook cannot
 * be called conditionally. Inactive means no interval is registered at all, so
 * the seven segments that are not current cost nothing.
 *
 * @param enteredAt ISO-8601 instant the stage was entered, or null
 * @param active    whether this segment is the one the ticket is sitting in
 * @returns elapsed wall-clock minutes, or null when there is nothing to count
 */
export function useElapsedMins(enteredAt: string | null | undefined, active: boolean): number | null {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (!active || !enteredAt) return
    // Re-read immediately as well as on the interval: a tab restored from the
    // background has a `now` from whenever it was last painted, which can be
    // hours stale, and waiting 30 s to correct it shows a wrong number to
    // somebody who is looking straight at it.
    setNow(Date.now())
    const id = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(id)
  }, [active, enteredAt])

  if (!active || !enteredAt) return null

  const entered = Date.parse(enteredAt)
  if (Number.isNaN(entered)) return null

  // Clamped at zero. A clock skew of a few seconds between the server's
  // `enteredAt` and the browser is ordinary, and `-1m in stage` is not.
  return Math.max(0, Math.floor((now - entered) / 60_000))
}
