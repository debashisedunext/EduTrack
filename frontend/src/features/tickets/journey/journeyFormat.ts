/**
 * C-055 · the formatters the Journey grid needs, kept apart from the component
 * so they can be tested without rendering anything.
 *
 * ⚠ **`formatDuration` and `formatEffortHrs` moved to `@/lib/duration` (B-050)
 * and are re-exported here, so every Journey call site is unchanged.** The
 * ribbon renders the same duration and the same effort for the same hop, on
 * the same page, directly above this grid — a second copy would let the two
 * disagree about one number and nothing would report it. `features/tickets/
 * journey/` is Stream C's directory under the 19 Aug carve-out to Stream D, so
 * this edit is flagged for sign-off rather than made quietly; it is a move and
 * a re-export, with no behaviour change and no test change.
 *
 * `isQueueBound` stays here. It is a display *rule* this grid applies, not a
 * formatter, and the ribbon does not use it — the idle-vs-active split belongs
 * to C-052's hover tooltip, which can decide its own threshold when it exists.
 */
export { formatDuration, formatEffortHrs } from '@/lib/duration'

/**
 * C-056 · is this hop's time mostly waiting?
 *
 * <p>The threshold is a *display* decision and nothing else — no rule anywhere
 * else in the product turns on it, and the server does not compute it. It marks
 * a row the reader should look at twice, and the number that earns that is the
 * blueprint's own example: "a stage with 2 days duration and 2 hours of effort
 * is a queue problem, not a capacity problem". Two hours against two days is
 * 4% active, so anything at or below half is comfortably inside what §4A.4 is
 * pointing at, while not flagging a stage that simply had a lunch break in it.
 *
 * A hop with no measured duration is not idle-dominated; it is unmeasured, and
 * saying otherwise about the stage somebody is working in right now would be
 * both wrong and rude.
 */
export function isQueueBound(durationMins: number | null | undefined, idleMins: number | null | undefined): boolean {
  if (durationMins == null || idleMins == null) return false
  if (durationMins <= 0) return false
  return idleMins / durationMins >= 0.5
}
