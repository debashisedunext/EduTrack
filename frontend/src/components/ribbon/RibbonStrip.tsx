import type { Ribbon } from '@/api/generated/model/ribbon'
import { EmptyState } from '@/components/ui/empty-state'
import { RibbonSegment } from './RibbonSegment'

/**
 * C-051 · lays B-050's tiles out into the strip blueprint §4A.3 describes —
 * the ribbon pinned to the top of every ticket detail page.
 *
 * ## What this task is not
 *
 * - **Interactions.** No `onSelect` is wired here, so every tile renders as
 *   the read-only `<div role="group">` `RibbonSegment` falls back to without
 *   one. Deciding what a click does — filtering History/Effort/Chat below —
 *   is `C-052`'s, and wiring it in here first would make a tile that looks
 *   clickable and does nothing.
 * - **The cycle selector and the `Cycle 2 · Iteration 3` chips.** `C-053` and
 *   `C-054` render above this strip; this component only ever draws the one
 *   cycle's segments it is given; which cycle is already resolved by
 *   `GET /tickets/{id}/full`'s own `?cycle=` (C-019), so there is nothing
 *   here to select from.
 * - **The auto-centred scroll and the collapsed `…` group past eight
 *   stages** — `B-053`. `overflow-x-auto` below is the floor a strip needs
 *   to not visibly break at eight segments, not that enhancement.
 *
 * `role="list"`/`role="listitem"` rather than nothing: `TicketDetailPage`'s
 * own tests already distinguish "the ribbon's list" from the description's
 * `<ol>` and the tab strip's `tablist`, and a screen reader announcing "list,
 * 8 items" here is the ribbon's shape, the same way the roll-up grid's rows
 * are a table.
 */
export function RibbonStrip({ ribbon }: { ribbon?: Ribbon }) {
  const segments = ribbon?.segments ?? []

  if (segments.length === 0) {
    return (
      <EmptyState
        title="No workflow ribbon"
        description="This ticket has no workflow template, so there is no stage journey to show."
      />
    )
  }

  return (
    <div role="list" aria-label="Workflow stages" className="flex items-start overflow-x-auto pb-1">
      {segments.map((segment, index) => (
        <div role="listitem" key={segment.stageCode ?? index}>
          <RibbonSegment segment={segment} isLast={index === segments.length - 1} />
        </div>
      ))}
    </div>
  )
}
