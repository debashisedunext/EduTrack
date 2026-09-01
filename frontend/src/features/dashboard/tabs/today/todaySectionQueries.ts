import { StatusCategory } from '@/api/generated/model/statusCategory'
import { StatusCode } from '@/api/generated/model/statusCode'
import type { ListTicketsParams } from '@/api/generated/model'

/**
 * Dashboard Rework Dev 1 · tab 1, PR 8 — the query behind each of the six
 * collapsible sections, kept in its own pure module per the plan: "it is the
 * piece most likely to silently disagree with the card above it."
 *
 * <h2>Why a pure module rather than inline in the component</h2>
 *
 * Every section's filter mirrors a figure already on the page — "Not started"
 * mirrors the Not Started card and the MIS grid's own `overdueStart` /
 * `dueToday` columns, "WIP — updated today" mirrors the WIP card's
 * `updated-today` figure. Getting one of those seven filters subtly wrong
 * (a `dueTo` the card used and the section forgot, say) does not fail to
 * compile — it renders a table that quietly disagrees with the number above
 * it. Testing this module against the exact strings `dashboardTabs.ts` builds
 * for the cards and MIS grid is what catches that; testing it through the
 * rendered component would not.
 */
export type TodaySectionKey =
  | 'not-started'
  | 'started-today'
  | 'finished-today'
  | 'wip-updated-today'
  | 'wip-not-updated-today'
  | 'blocked'

export interface TodaySectionDef {
  key: TodaySectionKey
  title: string
}

/** Rendered in this order — the plan's own ordering, prototype-verified. */
export const TODAY_SECTIONS: TodaySectionDef[] = [
  { key: 'not-started', title: 'Not started — overdue / due today' },
  { key: 'started-today', title: 'Started today' },
  { key: 'finished-today', title: 'Finished today' },
  { key: 'wip-updated-today', title: 'WIP — updated today' },
  { key: 'wip-not-updated-today', title: 'WIP — not updated today' },
  { key: 'blocked', title: 'Blocked / on hold' },
]

export interface TodaySectionScope {
  /**
   * The UTC calendar day, `yyyy-MM-dd` — derive this from the endpoint's
   * `asOf` with {@link todayFromAsOf}, never from the browser clock. A reader
   * in a timezone ahead of UTC opening the tab just after midnight their time
   * would otherwise compute a "today" the server's `daily_ticket_stats` row
   * has not reached yet, which is exactly the disagreement this module exists
   * to prevent.
   */
  today: string
  projectId?: number
}

/** Rows fetched per section once expanded — "limit ≈ 50" in the plan. */
export const SECTION_PAGE_SIZE = 50

/**
 * The filter for one section, matching the semantics `dashboardTabs.ts` uses
 * for the corresponding card figure or MIS column.
 */
export function todaySectionParams(key: TodaySectionKey, scope: TodaySectionScope): ListTicketsParams {
  const { today, projectId } = scope
  const base: ListTicketsParams = projectId ? { projectId } : {}

  switch (key) {
    case 'not-started':
      // `dueTo=today` alone covers overdue-start (due before today) and
      // due-today (due on today) in one bound — the same union the "Overdue
      // start" MIS column and card figure already rely on `dueTo=today` for.
      return { ...base, statusCategory: StatusCategory.TODO, excludeClosed: true, dueTo: today }
    case 'started-today':
      return { ...base, startedFrom: today, startedTo: today }
    case 'finished-today':
      return { ...base, finishedFrom: today, finishedTo: today }
    case 'wip-updated-today':
      return { ...base, statusCategory: StatusCategory.IN_PROGRESS, updatedFrom: today, updatedTo: today }
    case 'wip-not-updated-today':
      // The WIP card's own "not updated" figure bounds this the same way:
      // last touched on or before the day before today.
      return { ...base, statusCategory: StatusCategory.IN_PROGRESS, updatedTo: dayBefore(today) }
    case 'blocked':
      return { ...base, statuses: [StatusCode.ON_HOLD, StatusCode.AWAITING_INFO] }
  }
}

/** The lazy-fetch query — the section's own filter plus the page limit. */
export function todaySectionListParams(key: TodaySectionKey, scope: TodaySectionScope): ListTicketsParams {
  return { ...todaySectionParams(key, scope), limit: SECTION_PAGE_SIZE }
}

/**
 * "View all" — the identical filter with no limit, so the full ticket list
 * shows every row the section counted rather than the 50-row preview.
 */
export function todaySectionViewAllHref(key: TodaySectionKey, scope: TodaySectionScope): string {
  return `/tickets${toQueryString(todaySectionParams(key, scope))}`
}

/**
 * Mirrors `query()` in `api/http.ts`: arrays comma-joined, per the contract's
 * `explode: false`. Not imported from there — that function takes an
 * untyped `Record<string, unknown>` for the generated client's own fetch,
 * where this needs the typed `ListTicketsParams` a caller here already has.
 */
function toQueryString(params: ListTicketsParams): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    search.append(key, Array.isArray(value) ? value.join(',') : String(value))
  }
  const qs = search.toString()
  return qs ? `?${qs}` : ''
}

/** `today`'s previous UTC calendar day — a plain date operation, not a working-calendar one: a section boundary only needs "not today", never the weekend/holiday maths that governs SLA due dates. */
function dayBefore(isoDate: string): string {
  const date = new Date(`${isoDate}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() - 1)
  return date.toISOString().slice(0, 10)
}

/** The UTC calendar day of the endpoint's `asOf`, falling back to now only when the payload has not arrived yet — before the first response, no section has anything to fetch regardless. */
export function todayFromAsOf(asOf: string | null | undefined): string {
  const date = asOf ? new Date(asOf) : new Date()
  return date.toISOString().slice(0, 10)
}
