import { describe, expect, it } from 'vitest'

import { describeDrillDown, drillDownToParams } from './drillDownParams'

/**
 * A-061 · the panel reads the same string the link uses.
 *
 * These assertions are deliberately written against the URLs the server
 * actually emits, copied from `DashboardService` and `WidgetService`, rather
 * than invented ones. A parser tested only on its own idea of the input is a
 * parser that agrees with itself.
 */
describe('drillDownToParams', () => {
  it('parses a KPI card link, coercing each type', () => {
    const params = drillDownToParams(
      '/tickets?level=CRITICAL&excludeClosed=true&reportedFrom=2026-07-18&reportedTo=2026-08-16&projectId=4',
    )

    expect(params).toEqual({
      level: 'CRITICAL',
      excludeClosed: true,
      reportedFrom: '2026-07-18',
      reportedTo: '2026-08-16',
      projectId: 4,
    })
  })

  it('parses a donut slice', () => {
    expect(drillDownToParams('/tickets?taskTypeId=11&excludeClosed=true')).toEqual({
      taskTypeId: 11,
      excludeClosed: true,
    })
  })

  it('parses the open-ended aging bucket, which has no lower bound', () => {
    expect(drillDownToParams('/tickets?excludeClosed=true&reportedTo=2026-07-16')).toEqual({
      excludeClosed: true,
      reportedTo: '2026-07-16',
    })
  })

  it('parses a velocity point, which filters on the closed-date window', () => {
    expect(
      drillDownToParams(
        '/tickets?assigneeId=3&status=CLOSED&closedFrom=2026-07-20&closedTo=2026-07-26',
      ),
    ).toEqual({
      assigneeId: 3,
      status: 'CLOSED',
      closedFrom: '2026-07-20',
      closedTo: '2026-07-26',
    })
  })

  /**
   * `?isDelayed=false` is not something the dashboard emits, and treating the
   * string "false" as truthy would invert the filter — showing on-time tickets
   * under a heading that says overdue.
   */
  it('treats only the literal "true" as a set boolean', () => {
    expect(drillDownToParams('/tickets?isDelayed=false')).toEqual({})
    expect(drillDownToParams('/tickets?isDelayed=true')).toEqual({ isDelayed: true })
  })

  it('drops a non-numeric id rather than sending NaN', () => {
    expect(drillDownToParams('/tickets?projectId=abc')).toEqual({})
  })

  it('drops a key the ticket list does not implement', () => {
    // DrillDownContractTest guarantees the dashboard never emits one, so this
    // is the belt to that test's braces — and it must drop rather than forward,
    // or the silent discard just moves outward to Spring.
    expect(drillDownToParams('/tickets?somethingElse=1&level=LOW')).toEqual({ level: 'LOW' })
  })

  it('survives a link with no query at all', () => {
    expect(drillDownToParams('/tickets')).toEqual({})
  })

  /**
   * Dashboard Rework Dev 1, PR 8 · these seven keys are PR 5's own additions
   * to `GET /tickets`, and every Today-tab card and MIS cell already builds
   * its drill-down from them — `dashboardTabs.ts`'s mock server has done so
   * since PR 6. Missing here, they were the silent-drop this parser exists to
   * avoid: a MIS cell reading "Delayed: 7" would open a panel showing every
   * ticket in progress instead of the seven that are actually late.
   */
  it('parses the Today-tab MIS and section keys PR 5 added', () => {
    expect(
      drillDownToParams(
        '/tickets?assigneeId=3&statusCategory=IN_PROGRESS&updatedFrom=2026-09-01&updatedTo=2026-09-01',
      ),
    ).toEqual({
      assigneeId: 3,
      statusCategory: 'IN_PROGRESS',
      updatedFrom: '2026-09-01',
      updatedTo: '2026-09-01',
    })

    expect(drillDownToParams('/tickets?startedFrom=2026-09-01&startedTo=2026-09-01')).toEqual({
      startedFrom: '2026-09-01',
      startedTo: '2026-09-01',
    })

    expect(
      drillDownToParams('/tickets?finishedFrom=2026-09-01&finishedTo=2026-09-01&isDelayed=true'),
    ).toEqual({ finishedFrom: '2026-09-01', finishedTo: '2026-09-01', isDelayed: true })

    expect(drillDownToParams('/tickets?pendingReview=true')).toEqual({ pendingReview: true })
  })

  it('parses the Blocked card and section link, comma-joined per `explode: false`', () => {
    expect(drillDownToParams('/tickets?statuses=ON_HOLD,AWAITING_INFO')).toEqual({
      statuses: ['ON_HOLD', 'AWAITING_INFO'],
    })
  })
})

describe('describeDrillDown', () => {
  it('describes a card in words, from the same string it fetches', () => {
    expect(
      describeDrillDown('/tickets?level=CRITICAL&excludeClosed=true&reportedFrom=2026-08-01&reportedTo=2026-08-16'),
    ).toBe('critical · still open · raised 2026-08-01 to 2026-08-16')
  })

  it('collapses a single-day window, which the daily charts emit', () => {
    expect(describeDrillDown('/tickets?reportedFrom=2026-08-10&reportedTo=2026-08-10')).toBe(
      'raised on 2026-08-10',
    )
  })

  it('describes the open-ended aging bucket as a bound, not a range', () => {
    expect(describeDrillDown('/tickets?excludeClosed=true&reportedTo=2026-07-16')).toBe(
      'still open · raised on or before 2026-07-16',
    )
  })

  it('says something honest when nothing narrows it', () => {
    expect(describeDrillDown('/tickets')).toBe('all tickets in scope')
  })

  it('describes a MIS cell — category, an assignee-scoped range, and pending review', () => {
    expect(describeDrillDown('/tickets?statusCategory=IN_PROGRESS&isDelayed=true')).toBe(
      'category in progress · overdue',
    )
    expect(describeDrillDown('/tickets?finishedFrom=2026-09-01&finishedTo=2026-09-01')).toBe(
      'finished on 2026-09-01',
    )
    expect(describeDrillDown('/tickets?pendingReview=true')).toBe('pending review')
  })

  it('describes the Blocked statuses list', () => {
    expect(describeDrillDown('/tickets?statuses=ON_HOLD,AWAITING_INFO')).toBe(
      'status on_hold, awaiting_info',
    )
  })
})
