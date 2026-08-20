/**
 * C-052 · what a ribbon segment click narrows History and Effort to —
 * blueprint §4A.3: "click a segment filters History/Effort/Chat below to
 * that stage and iteration." Chat is not filtered by this: `TicketDetailPage`
 * still renders `D-047`'s placeholder for that tab, and there is nothing
 * there yet for a filter to reach.
 *
 * `cycleNo` travels with the filter even though the blueprint line only names
 * stage and iteration. `stream-tickets`'s own warning is exactly this trap:
 * iteration and cycle are independent counters, and a stage can read
 * "iteration 1" in both cycle 1 and cycle 2 after a reopen. History and
 * Effort fetch every cycle at once on the live-cycle view (`useTicketHistory`/
 * `useEffortTab`'s own note on why), so matching on stage and iteration alone
 * would fold an unrelated cycle's entries into this one's filter the moment a
 * ticket has been reopened — the same double-count the Journey roll-up query
 * was corrected for. The ribbon shown is always one specific cycle's, so its
 * `cycleNo` is what disambiguates.
 */
export interface SegmentFilter {
  stageCode: string
  iterationNo?: number
  cycleNo?: number
  /** What the chip reads — the segment's own `displayName`, not a re-derived label. */
  displayName: string
}

interface FilterableEntry {
  stageCode?: string | null
  iterationNo?: number
  cycleNo?: number
}

/** Undefined `iterationNo` reads as 1, the same default every ribbon segment
 * and every append-only row carries before a ticket has ever looped. */
export function matchesSegmentFilter(entry: FilterableEntry, filter: SegmentFilter): boolean {
  if (entry.stageCode !== filter.stageCode) return false
  if ((entry.iterationNo ?? 1) !== (filter.iterationNo ?? 1)) return false
  if (filter.cycleNo != null && (entry.cycleNo ?? 1) !== filter.cycleNo) return false
  return true
}
