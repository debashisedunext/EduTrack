import { describe, expect, it } from 'vitest'

import {
  SECTION_PAGE_SIZE,
  todaySectionListParams,
  todaySectionParams,
  todaySectionViewAllHref,
  todayFromAsOf,
} from './todaySectionQueries'

const scope = { today: '2026-09-01' }

describe('todaySectionParams', () => {
  it('bounds "not started" by due-on-or-before-today, covering overdue and due-today in one filter', () => {
    expect(todaySectionParams('not-started', scope)).toEqual({
      statusCategory: 'TODO',
      excludeClosed: true,
      dueTo: '2026-09-01',
    })
  })

  it('filters "started today" on the current cycle\'s start stamp', () => {
    expect(todaySectionParams('started-today', scope)).toEqual({
      startedFrom: '2026-09-01',
      startedTo: '2026-09-01',
    })
  })

  it('filters "finished today" on the current cycle\'s finish stamp', () => {
    expect(todaySectionParams('finished-today', scope)).toEqual({
      finishedFrom: '2026-09-01',
      finishedTo: '2026-09-01',
    })
  })

  it('filters "WIP updated today" the same way the WIP card\'s figure does', () => {
    expect(todaySectionParams('wip-updated-today', scope)).toEqual({
      statusCategory: 'IN_PROGRESS',
      updatedFrom: '2026-09-01',
      updatedTo: '2026-09-01',
    })
  })

  it('bounds "WIP not updated today" at the day before, not today itself', () => {
    expect(todaySectionParams('wip-not-updated-today', scope)).toEqual({
      statusCategory: 'IN_PROGRESS',
      updatedTo: '2026-08-31',
    })
  })

  it('rolls the day-before boundary across a month end', () => {
    expect(todaySectionParams('wip-not-updated-today', { today: '2026-09-01' })).toMatchObject({
      updatedTo: '2026-08-31',
    })
    expect(todaySectionParams('wip-not-updated-today', { today: '2026-03-01' })).toMatchObject({
      updatedTo: '2026-02-28',
    })
  })

  it('filters "Blocked" on the exact two statuses the Blocked card counts', () => {
    expect(todaySectionParams('blocked', scope)).toEqual({
      statuses: ['ON_HOLD', 'AWAITING_INFO'],
    })
  })

  it('narrows every section to the dashboard\'s project filter when one is set', () => {
    expect(todaySectionParams('blocked', { ...scope, projectId: 4 })).toEqual({
      projectId: 4,
      statuses: ['ON_HOLD', 'AWAITING_INFO'],
    })
  })

  it('omits projectId entirely rather than sending it undefined', () => {
    expect(Object.keys(todaySectionParams('blocked', scope))).not.toContain('projectId')
  })
})

describe('todaySectionListParams', () => {
  it('adds the page limit on top of the section filter', () => {
    expect(todaySectionListParams('started-today', scope)).toEqual({
      startedFrom: '2026-09-01',
      startedTo: '2026-09-01',
      limit: SECTION_PAGE_SIZE,
    })
  })
})

describe('todaySectionViewAllHref', () => {
  it('builds a /tickets link with no limit, so "View all" is not capped at the preview size', () => {
    const href = todaySectionViewAllHref('finished-today', scope)
    expect(href).toBe('/tickets?finishedFrom=2026-09-01&finishedTo=2026-09-01')
    expect(href).not.toContain('limit')
  })

  it('comma-joins the Blocked statuses, matching the contract\'s explode: false', () => {
    expect(todaySectionViewAllHref('blocked', scope)).toBe('/tickets?statuses=ON_HOLD%2CAWAITING_INFO')
  })

  it('carries the project filter into the link', () => {
    expect(todaySectionViewAllHref('started-today', { ...scope, projectId: 7 })).toBe(
      '/tickets?projectId=7&startedFrom=2026-09-01&startedTo=2026-09-01',
    )
  })
})

describe('todayFromAsOf', () => {
  it('reads the UTC calendar day off the endpoint\'s as-of timestamp', () => {
    expect(todayFromAsOf('2026-09-01T23:30:00.000Z')).toBe('2026-09-01')
  })

  it('falls back to the current day when there is no as-of yet', () => {
    expect(todayFromAsOf(null)).toBe(new Date().toISOString().slice(0, 10))
  })
})
