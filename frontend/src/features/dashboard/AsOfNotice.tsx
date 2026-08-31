/**
 * How old the figures on a dashboard tab are, said out loud.
 *
 * These are read from the summary tables the worker refreshes every five
 * minutes (A-051), so they are stale by design and the server reports exactly
 * how stale. Surfaced rather than hidden: a dashboard that silently presents
 * five-minute-old numbers as live is how somebody makes a call on a figure that
 * moved four minutes ago.
 *
 * <h2>Why this is a shared file</h2>
 *
 * `AnalyticsTab` has an identical private copy, and the Today, Overview and
 * Weekly tabs each need the same line. Extracted into a new file rather than
 * exported from `AnalyticsTab` because that file is Dev 1's and frozen after
 * PR 2 — a new file conflicts with nobody. Dev 1 can point the analytics copy
 * at this one whenever they are next in there; until then the duplication is
 * deliberate and one-directional, and the wording is kept identical on purpose.
 */
export function AsOfNotice({ asOf }: { asOf?: string | null }) {
  if (!asOf) {
    // No computed_at means the refresh has not run for this window at all —
    // a different statement from "zero tickets", and worth saying plainly.
    return (
      <p className="text-xs text-[color:var(--warning-text)]">
        These figures have not been computed yet. The summary worker runs every five minutes.
      </p>
    )
  }
  return (
    <p className="text-xs text-[color:var(--text-secondary)]">
      Figures as at {new Date(asOf).toLocaleString()}. Refreshed every five minutes.
    </p>
  )
}
