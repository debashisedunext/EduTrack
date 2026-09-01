import { StatusCategory } from '@/api/generated/model/statusCategory'
import type { ListTicketsParams } from '@/api/generated/model'

/**
 * S-05 tab 3, PR 13b — the query behind each of the five weekly accordion
 * sections.
 *
 * <h2>A pure module, for the reason `todaySectionQueries` gives</h2>
 *
 * Each section's filter mirrors a figure already on the page, and getting one
 * subtly wrong does not fail to compile — it renders an accordion that quietly
 * disagrees with the card above it. Worse here than on the Today tab, because
 * the plan's definition of done says the group totals inside a section must
 * equal that section's own badge: a filter that drifts makes two numbers on
 * one screen contradict each other with nothing to explain why.
 *
 * <h2>Every window is the requested week, never the browser's idea of now</h2>
 *
 * The week comes from the endpoint's echoed `weekStart`/`weekEnd`, so a reader
 * deep-linked to a past week gets that week's tickets rather than this one's.
 * `WeeklyTab` already prefers the server's echo over its local guess for the
 * caption; the sections read the same pair for the same reason.
 */
export type WeeklySectionKey =
  | 'critical-should-have-started'
  | 'not-started'
  | 'wip-updated'
  | 'finished'
  | 'wip-overdue'

export interface WeeklySectionDef {
  key: WeeklySectionKey
  title: string
}

/** Rendered in the plan's order. */
export const WEEKLY_SECTIONS: WeeklySectionDef[] = [
  { key: 'critical-should-have-started', title: 'Critical — should have started' },
  { key: 'not-started', title: 'Not started' },
  { key: 'wip-updated', title: 'WIP — updated this week' },
  { key: 'finished', title: 'Finished this week' },
  { key: 'wip-overdue', title: 'WIP — not finishing / overdue' },
]

export interface WeeklySectionScope {
  /** The ISO Monday, `yyyy-MM-dd`, as echoed by the endpoint. */
  weekStart: string
  /** The Sunday, inclusive. */
  weekEnd: string
  projectId?: number
  assigneeId?: number
}

/**
 * Rows fetched per section once expanded. Larger than the Today tab's 50
 * because these are grouped three levels deep before being drawn — a section
 * capped at 50 would routinely show one ticket under each of forty headers,
 * which is a worse reading of the same data. `groupTickets`' own 200-row cap
 * is the matching bound on the other side, so the two agree.
 */
export const WEEKLY_SECTION_PAGE_SIZE = 200

/**
 * The filter for one section.
 *
 * <p>`level` takes a single value on `GET /tickets`, which is why the critical
 * section names `CRITICAL` alone rather than "critical and high" — widening it
 * would need a `levels` parameter the list does not implement, and inventing
 * one client-side is the drift this module exists to prevent.
 */
export function weeklySectionParams(
  key: WeeklySectionKey,
  scope: WeeklySectionScope,
): ListTicketsParams {
  const { weekStart, weekEnd, projectId, assigneeId } = scope
  const base: ListTicketsParams = {
    ...(projectId ? { projectId } : {}),
    ...(assigneeId ? { assigneeId } : {}),
  }

  switch (key) {
    case 'critical-should-have-started':
      // Critical, still not started, and due on or before the end of this
      // week — "should have started" is exactly that: the commitment falls
      // inside the window and nothing has begun.
      return {
        ...base,
        level: 'CRITICAL',
        statusCategory: StatusCategory.TODO,
        excludeClosed: true,
        dueTo: weekEnd,
      }
    case 'not-started':
      // Bounded at both ends, unlike the critical section above: this is the
      // week's own not-started work, not a running backlog. Overdue work from
      // earlier weeks belongs to the critical section and to `wip-overdue`,
      // and counting it here too would put one ticket under two badges.
      return {
        ...base,
        statusCategory: StatusCategory.TODO,
        excludeClosed: true,
        dueFrom: weekStart,
        dueTo: weekEnd,
      }
    case 'wip-updated':
      return {
        ...base,
        statusCategory: StatusCategory.IN_PROGRESS,
        updatedFrom: weekStart,
        updatedTo: weekEnd,
      }
    case 'finished':
      // The per-cycle stamp, not `closedFrom`/`closedTo`: a reopened ticket's
      // new cycle finishing this week is this week's work, which is the whole
      // reason V20260831_1400 added the column.
      return { ...base, finishedFrom: weekStart, finishedTo: weekEnd }
    case 'wip-overdue':
      // No date window. "Not finishing" is a statement about now, not about
      // the week — a WIP ticket already past its due date is overdue whether
      // it was committed for this week or three weeks ago, and bounding it to
      // the window would hide the oldest ones, which are the point.
      return {
        ...base,
        statusCategory: StatusCategory.IN_PROGRESS,
        isDelayed: true,
        excludeClosed: true,
      }
  }
}

/**
 * The full list behind a section, for its "View all" link.
 *
 * Built from the same params the section fetched rather than a second
 * hand-written string — `todaySectionViewAllHref`'s own argument, and the
 * reason the dashboard builds drill-downs server-side everywhere else.
 */
export function weeklySectionViewAllHref(
  key: WeeklySectionKey,
  scope: WeeklySectionScope,
): string {
  const params = weeklySectionParams(key, scope)
  const search = new URLSearchParams()
  for (const [name, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) {
      search.set(name, String(value))
    }
  }
  return `/tickets?${search.toString()}`
}
