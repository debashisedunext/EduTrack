import { describe, expect, it } from 'vitest'

import {
  WEEKLY_SECTIONS,
  weeklySectionParams,
  weeklySectionViewAllHref,
  type WeeklySectionKey,
} from './weeklySectionQueries'

/**
 * S-05 tab 3, PR 13b · the five section filters.
 *
 * Tested as strings rather than through the rendered accordion, for
 * `todaySectionQueries.test.ts`' reason: a filter that drifts from the card
 * above it does not fail to compile, it renders a list that quietly disagrees
 * with a number on the same screen. The assertions below are about the
 * boundaries — which sections are bounded by the week and which deliberately
 * are not.
 */

const WEEK = { weekStart: '2026-08-31', weekEnd: '2026-09-06' }

describe('weeklySectionParams', () => {
  it('covers every section the tab renders', () => {
    expect(WEEKLY_SECTIONS).toHaveLength(5)
    WEEKLY_SECTIONS.forEach((section) => {
      expect(weeklySectionParams(section.key, WEEK)).toBeTypeOf('object')
    })
  })

  it('bounds "critical — should have started" at the end of the week, not the start', () => {
    // A critical ticket committed for last week and still not started is the
    // headline case for this section; a dueFrom would hide exactly those.
    const params = weeklySectionParams('critical-should-have-started', WEEK)

    expect(params.level).toBe('CRITICAL')
    expect(params.statusCategory).toBe('TODO')
    expect(params.excludeClosed).toBe(true)
    expect(params.dueTo).toBe(WEEK.weekEnd)
    expect(params.dueFrom).toBeUndefined()
  })

  it('bounds "not started" at both ends, so it cannot double-count the critical section', () => {
    const params = weeklySectionParams('not-started', WEEK)

    expect(params.statusCategory).toBe('TODO')
    expect(params.dueFrom).toBe(WEEK.weekStart)
    expect(params.dueTo).toBe(WEEK.weekEnd)
  })

  it('reads updated-this-week from the update window, inclusive of both ends', () => {
    const params = weeklySectionParams('wip-updated', WEEK)

    expect(params.statusCategory).toBe('IN_PROGRESS')
    expect(params.updatedFrom).toBe(WEEK.weekStart)
    expect(params.updatedTo).toBe(WEEK.weekEnd)
  })

  it('reads "finished" from the per-cycle stamp, never from closedFrom/closedTo', () => {
    // A reopened ticket's new cycle finishing this week is this week's work —
    // the whole reason V20260831_1400 added the column.
    const params = weeklySectionParams('finished', WEEK)

    expect(params.finishedFrom).toBe(WEEK.weekStart)
    expect(params.finishedTo).toBe(WEEK.weekEnd)
    expect(params.closedFrom).toBeUndefined()
    expect(params.closedTo).toBeUndefined()
  })

  it('leaves "wip — not finishing / overdue" unbounded by the week, deliberately', () => {
    // Overdue is a statement about now. Bounding it to the window would hide
    // the oldest overdue tickets, which are the ones worth seeing.
    const params = weeklySectionParams('wip-overdue', WEEK)

    expect(params.isDelayed).toBe(true)
    expect(params.statusCategory).toBe('IN_PROGRESS')
    expect(params.dueFrom).toBeUndefined()
    expect(params.dueTo).toBeUndefined()
    expect(params.updatedFrom).toBeUndefined()
  })

  it('threads project and assignee scope into every section', () => {
    const scoped = { ...WEEK, projectId: 7, assigneeId: 3 }

    WEEKLY_SECTIONS.forEach((section) => {
      const params = weeklySectionParams(section.key, scoped)
      expect(params.projectId, section.key).toBe(7)
      expect(params.assigneeId, section.key).toBe(3)
    })
  })

  it('omits scope keys entirely when unset rather than sending undefined', () => {
    const params = weeklySectionParams('not-started', WEEK)

    expect('projectId' in params).toBe(false)
    expect('assigneeId' in params).toBe(false)
  })
})

describe('weeklySectionViewAllHref', () => {
  it('builds the full list from the same params the section fetched', () => {
    const href = weeklySectionViewAllHref('finished', { ...WEEK, projectId: 7 })

    expect(href.startsWith('/tickets?')).toBe(true)
    const search = new URLSearchParams(href.slice('/tickets?'.length))
    expect(search.get('finishedFrom')).toBe(WEEK.weekStart)
    expect(search.get('finishedTo')).toBe(WEEK.weekEnd)
    expect(search.get('projectId')).toBe('7')
  })

  it('emits only parameters GET /tickets implements', () => {
    // The DrillDownContractTest rule, held on the client side: every key here
    // must be one the ticket list accepts, or Spring drops it silently and the
    // list opens wider than the section that was clicked.
    const accepted = new Set([
      'projectId',
      'assigneeId',
      'level',
      'statusCategory',
      'excludeClosed',
      'dueFrom',
      'dueTo',
      'updatedFrom',
      'updatedTo',
      'finishedFrom',
      'finishedTo',
      'isDelayed',
    ])

    const keys: WeeklySectionKey[] = WEEKLY_SECTIONS.map((s) => s.key)
    keys.forEach((key) => {
      const href = weeklySectionViewAllHref(key, { ...WEEK, projectId: 1, assigneeId: 2 })
      const search = new URLSearchParams(href.slice('/tickets?'.length))
      for (const name of search.keys()) {
        expect(accepted, `${key} emits ${name}`).toContain(name)
      }
    })
  })
})
